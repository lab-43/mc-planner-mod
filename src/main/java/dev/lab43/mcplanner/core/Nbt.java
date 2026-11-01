package dev.lab43.mcplanner.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * A small reader for Minecraft's NBT format (what schematic files are made of), independent of Minecraft's own
 * classes so it works the same for every game version (port of packages/world nbt.ts).
 *
 * <p>Values become plain Java objects: compound → {@code Map<String, Object>} (in file order), list →
 * {@code List<Object>}, byte/short/int/long/float/double → their boxed types, string → String, byte/int/long arrays →
 * {@code byte[]}, {@code int[]}, {@code long[]}. Gzip and zlib compressed files are unpacked first.
 */
public final class Nbt {
    private static final int MAX_DEPTH = 512;

    private final DataInputStream in;
    private long remaining;

    private Nbt(DataInputStream in, long size) {
        this.in = in;
        this.remaining = size;
    }

    /** Reads a whole NBT file (gzip, zlib or plain): the root compound. */
    public static Map<String, Object> read(byte[] file) throws IOException {
        byte[] raw = unpack(file);
        Nbt r = new Nbt(new DataInputStream(new ByteArrayInputStream(raw)), raw.length);
        int type = r.u8();
        if (type != 10) throw new IOException("not NBT (root tag " + type + ")");
        r.string(); // root name
        return r.compound(0);
    }

    /** gzip (1f 8b) / zlib (78 xx) → raw bytes; anything else as it is. Capped at 512 MB unpacked. */
    static byte[] unpack(byte[] file) throws IOException {
        InputStream s;
        if (file.length >= 2 && (file[0] & 0xFF) == 0x1F && (file[1] & 0xFF) == 0x8B) s = new GZIPInputStream(new ByteArrayInputStream(file));
        else if (file.length >= 2 && (file[0] & 0xFF) == 0x78 && ((file[0] & 0xFF) * 256 + (file[1] & 0xFF)) % 31 == 0) s = new InflaterInputStream(new ByteArrayInputStream(file));
        else return file;
        try (InputStream z = s) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(1024, file.length * 4));
            byte[] buf = new byte[65536];
            long total = 0;
            for (int n; (n = z.read(buf)) > 0; ) {
                total += n;
                if (total > 512L * 1024 * 1024) throw new IOException("file unpacks to more than 512 MB");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private Object tag(int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested too deep");
        switch (type) {
            case 1: return (byte) u8();
            case 2: need(2); return in.readShort();
            case 3: need(4); return in.readInt();
            case 4: need(8); return in.readLong();
            case 5: need(4); return in.readFloat();
            case 6: need(8); return in.readDouble();
            case 7: {
                int n = array(1);
                byte[] a = new byte[n];
                in.readFully(a);
                return a;
            }
            case 8: return string();
            case 9: {
                int t = u8();
                need(4);
                int n = in.readInt();
                if (n < 0 || n > remaining) throw new IOException("bad NBT list length " + n);
                if (t == 0) n = 0; // a list of "end" tags holds nothing
                List<Object> l = new ArrayList<>(Math.min(n, 4096));
                for (int i = 0; i < n; i++) l.add(tag(t, depth + 1));
                return l;
            }
            case 10: return compound(depth + 1);
            case 11: {
                int n = array(4);
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = in.readInt();
                return a;
            }
            case 12: {
                int n = array(8);
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = in.readLong();
                return a;
            }
            default: throw new IOException("unknown NBT tag " + type);
        }
    }

    private Map<String, Object> compound(int depth) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        for (;;) {
            int t = u8();
            if (t == 0) return m;
            String name = string();
            m.put(name, tag(t, depth));
        }
    }

    private String string() throws IOException {
        need(2);
        int n = in.readUnsignedShort();
        need(n);
        byte[] b = new byte[n];
        in.readFully(b);
        // modified UTF-8 (Java's DataInput format); plain UTF-8 decoding is right for everything but NUL / surrogates
        try {
            return new DataInputStream(new ByteArrayInputStream(withLength(b))).readUTF();
        } catch (IOException e) {
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static byte[] withLength(byte[] b) {
        byte[] out = new byte[b.length + 2];
        out[0] = (byte) (b.length >> 8);
        out[1] = (byte) b.length;
        System.arraycopy(b, 0, out, 2, b.length);
        return out;
    }

    private int u8() throws IOException {
        need(1);
        int v = in.read();
        if (v < 0) throw new EOFException();
        return v;
    }

    /** an array's length; its bytes must be in what's left of the file (and are counted as read) */
    private int array(int bytesEach) throws IOException {
        need(4);
        int n = in.readInt();
        if (n < 0) throw new IOException("bad NBT array length " + n);
        need((long) n * bytesEach);
        return n;
    }

    private void need(long n) throws IOException {
        if (n > remaining) throw new EOFException("NBT ends early");
        remaining -= n;
    }

    // ---------- reading helpers for callers ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object v) {
        return v instanceof List ? (List<Object>) v : null;
    }

    /** any number as int (0 if missing) */
    public static int num(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** a non-blank string, else null */
    public static String str(Object v) {
        return v instanceof String s && !s.isBlank() ? s.strip() : null;
    }
}
