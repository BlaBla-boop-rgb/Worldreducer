package de.syntaxium.worldtrim;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Liest aus den (komprimierten) NBT-Daten eines Chunks nur den Wert "InhabitedTime".
 * Es wird nicht der ganze Chunk geparst: sobald der Wert gefunden ist, wird abgebrochen.
 */
public final class InhabitedTimeReader {

    /** Ergebnis, wenn kein Wert gefunden oder der Chunk nicht lesbar ist. */
    public static final long UNKNOWN = -1L;

    private static final Constructor<?> LZ4_STREAM = findLz4();

    private InhabitedTimeReader() {}

    private static Constructor<?> findLz4() {
        try {
            // Wird vom Minecraft-Server selbst mitgeliefert (für region-file-compression=lz4)
            return Class.forName("net.jpountz.lz4.LZ4BlockInputStream").getConstructor(InputStream.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * @param data        Rohdaten der Region-Datei
     * @param offset      Start der Kompressionsdaten (nach Längenfeld und Kompressions-Byte)
     * @param length      Länge der komprimierten Daten
     * @param compression Kompressionstyp (1 = GZip, 2 = Zlib, 3 = keine, 4 = LZ4)
     * @return InhabitedTime in Ticks oder {@link #UNKNOWN}
     */
    public static long read(byte[] data, int offset, int length, int compression) {
        try (InputStream raw = new ByteArrayInputStream(data, offset, length);
             InputStream in = decompress(raw, compression)) {
            if (in == null) return UNKNOWN;
            DataInputStream dis = new DataInputStream(in);
            int rootType = dis.readUnsignedByte();
            if (rootType != 10) return UNKNOWN; // Wurzel muss ein Compound sein
            dis.skipNBytes(dis.readUnsignedShort()); // Name der Wurzel
            return searchCompound(dis, 0);
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    private static InputStream decompress(InputStream raw, int compression) throws Exception {
        return switch (compression) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            case 4 -> LZ4_STREAM == null ? null : (InputStream) LZ4_STREAM.newInstance(raw);
            default -> null;
        };
    }

    /** Durchsucht ein Compound. Alte Chunks (vor 1.18) haben den Wert unter "Level". */
    private static long searchCompound(DataInputStream in, int depth) throws IOException {
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) return UNKNOWN; // TAG_End
            String name = in.readUTF();
            if (type == 4 && name.equals("InhabitedTime")) {
                return in.readLong();
            }
            if (type == 10 && depth == 0 && name.equals("Level")) {
                long v = searchCompound(in, depth + 1);
                if (v != UNKNOWN) return v;
                continue;
            }
            skipPayload(in, type);
        }
    }

    private static void skipPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1 -> in.skipNBytes(1);
            case 2 -> in.skipNBytes(2);
            case 3, 5 -> in.skipNBytes(4);
            case 4, 6 -> in.skipNBytes(8);
            case 7 -> in.skipNBytes(in.readInt());
            case 8 -> in.skipNBytes(in.readUnsignedShort());
            case 9 -> {
                int elemType = in.readUnsignedByte();
                int count = in.readInt();
                for (int i = 0; i < count; i++) skipPayload(in, elemType);
            }
            case 10 -> {
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    in.skipNBytes(in.readUnsignedShort());
                    skipPayload(in, t);
                }
            }
            case 11 -> in.skipNBytes(4L * in.readInt());
            case 12 -> in.skipNBytes(8L * in.readInt());
            default -> throw new IOException("Unbekannter NBT-Typ " + type);
        }
    }
}
