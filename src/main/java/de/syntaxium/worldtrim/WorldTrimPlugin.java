package de.syntaxium.worldtrim;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorldTrimPlugin extends JavaPlugin {

    private static final String PREFIX = "§6[WorldTrim] §7";
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Path pendingFile() { return getDataFolder().toPath().resolve("pending-trim.txt"); }
    private Path reportFile()  { return getDataFolder().toPath().resolve("last-report.txt"); }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PluginCommand cmd = getCommand("worldtrim");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // Läuft dank "load: STARTUP" bevor die Welten geladen werden.
        if (Files.exists(pendingFile())) {
            try {
                Files.delete(pendingFile()); // zuerst löschen, damit ein Absturz keine Endlosschleife auslöst
            } catch (IOException e) {
                getLogger().severe("Konnte pending-trim.txt nicht löschen, breche ab: " + e.getMessage());
                return;
            }
            getLogger().warning("=== Geplante Bereinigung startet (Server wartet, bis sie fertig ist) ===");
            String report = runAll(false);
            getLogger().warning("=== Bereinigung fertig ===");
            for (String line : report.split("\n")) getLogger().info(line);
        }
    }

    /** Führt Analyse (dryRun) oder Bereinigung für alle konfigurierten Welten aus. */
    private String runAll(boolean dryRun) {
        reloadConfig();
        FileConfiguration c = getConfig();
        RegionTrimmer.Settings settings = new RegionTrimmer.Settings(
                Math.max(0, c.getLong("min-inhabited-minutes", 5)) * 60L * 20L,
                c.getInt("protect.center-x", 0),
                c.getInt("protect.center-z", 0),
                c.getInt("protect.radius", 1000),
                c.getBoolean("include-entities-and-poi", true),
                dryRun);
        RegionTrimmer trimmer = new RegionTrimmer(settings, getLogger());

        StringBuilder sb = new StringBuilder();
        sb.append(dryRun ? "ANALYSE (nichts wurde verändert)" : "BEREINIGUNG").append(" – ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))).append('\n');
        sb.append(String.format("Einstellungen: < %d Min. Aufenthalt, Schutzradius %d um (%d, %d)%n",
                c.getLong("min-inhabited-minutes", 5), settings.protectRadius(), settings.centerX(), settings.centerZ()));

        RegionTrimmer.Stats total = new RegionTrimmer.Stats();
        Path container = Bukkit.getWorldContainer().toPath();
        for (String world : c.getStringList("worlds")) {
            Path worldFolder = container.resolve(world);
            List<Path> dims = RegionTrimmer.dimensionFolders(worldFolder);
            if (dims.isEmpty()) {
                sb.append("\n").append(world).append(": Ordner oder region/ nicht gefunden, übersprungen\n");
                continue;
            }
            for (Path dim : dims) {
                String label = world + (dim.equals(worldFolder) ? "" : "/" + dim.getFileName());
                getLogger().info((dryRun ? "Analysiere " : "Bereinige ") + label + " ...");
                try {
                    RegionTrimmer.Stats s = trimmer.processDimension(dim);
                    total.add(s);
                    sb.append('\n').append(label).append(":\n").append(s.summary()).append('\n');
                } catch (IOException e) {
                    sb.append('\n').append(label).append(": FEHLER ").append(e.getMessage()).append('\n');
                }
            }
        }
        sb.append("\nGESAMT:\n").append(total.summary()).append('\n');

        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(reportFile(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warning("Bericht konnte nicht gespeichert werden: " + e.getMessage());
        }
        return sb.toString();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "analyse", "analyze" -> {
                if (!running.compareAndSet(false, true)) {
                    sender.sendMessage(PREFIX + "§cEs läuft bereits eine Analyse.");
                    return true;
                }
                sender.sendMessage(PREFIX + "Analyse gestartet. Das kann bei großen Welten einige Minuten dauern ...");
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    try {
                        String report = runAll(true);
                        Bukkit.getScheduler().runTask(this, () -> {
                            for (String line : report.split("\n")) sender.sendMessage("§7" + line);
                            sender.sendMessage(PREFIX + "Bericht gespeichert in plugins/WorldTrim/last-report.txt");
                        });
                    } finally {
                        running.set(false);
                    }
                });
            }
            case "schedule" -> {
                try {
                    Files.createDirectories(getDataFolder().toPath());
                    Files.writeString(pendingFile(), "geplant am " + LocalDateTime.now());
                    sender.sendMessage(PREFIX + "§aBereinigung geplant. §7Sie läuft beim nächsten §eNeustart§7, bevor die Welten laden.");
                    sender.sendMessage(PREFIX + "§cMach vorher ein Backup! §7Abbrechen mit /worldtrim cancel");
                } catch (IOException e) {
                    sender.sendMessage(PREFIX + "§cFehler: " + e.getMessage());
                }
            }
            case "cancel" -> {
                try {
                    boolean removed = Files.deleteIfExists(pendingFile());
                    sender.sendMessage(PREFIX + (removed ? "§aGeplante Bereinigung abgebrochen." : "Es war keine Bereinigung geplant."));
                } catch (IOException e) {
                    sender.sendMessage(PREFIX + "§cFehler: " + e.getMessage());
                }
            }
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(PREFIX + "Config neu geladen.");
            }
            default -> {
                boolean pending = Files.exists(pendingFile());
                sender.sendMessage(PREFIX + "Bereinigung beim nächsten Neustart: " + (pending ? "§eJA" : "§anein"));
                sender.sendMessage(PREFIX + "/worldtrim analyse §8– §7zeigt, wie viel Platz frei würde");
                sender.sendMessage(PREFIX + "/worldtrim schedule §8– §7beim nächsten Neustart aufräumen");
                sender.sendMessage(PREFIX + "/worldtrim cancel §8– §7Planung abbrechen");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("analyse", "schedule", "cancel", "status", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
