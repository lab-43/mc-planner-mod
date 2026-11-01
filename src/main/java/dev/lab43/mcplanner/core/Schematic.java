package dev.lab43.mcplanner.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A schematic file's blocks as one grid — port of packages/world schematic.ts, so the game shows exactly what the app
 * shows. Reads Litematica {@code .litematic} (all regions merged into their enclosing box), Sponge / WorldEdit
 * {@code .schem} (versions 1–3) and vanilla structure files {@code .nbt}. Old MCEdit {@code .schematic} files (pre-1.13
 * number ids) are only shown by the app for now.
 *
 * <p>Block index order is {@code (y * l + z) * w + x}; palette index 0 is always air. Palette entries are block-state
 * keys with sorted properties: {@code minecraft:oak_stairs[facing=east,half=bottom]}.
 */
public final class Schematic {
    /** largest box we load (blocks): 16 M — a 256 × 256 × 256 build */
    public static final int MAX_VOLUME = 16_000_000;

    public static final List<String> EXTENSIONS = List.of(".litematic", ".schem", ".schematic", ".nbt");

    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void");

    /** "litematic" | "sponge" | "structure" */
    public final String format;
    /** size in blocks: x (width), y (height), z (length) */
    public final int w, h, l;
    /** block-state keys; index 0 = air */
    public final List<String> palette;
    /** palette index per block */
    public final char[] blocks;
    /** non-air blocks */
    public final int count;
    public final String name, author;

    private Schematic(String format, int w, int h, int l, List<String> palette, char[] blocks, String name, String author) {
        this.format = format;
        this.w = w;
        this.h = h;
        this.l = l;
        this.palette = List.copyOf(palette);
        this.blocks = blocks;
        int n = 0;
        for (char b : blocks) if (b != 0) n++;
        this.count = n;
        this.name = name;
        this.author = author;
    }

    /** Palette index at a block (0 = air, also outside the box). */
    public int at(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= l) return 0;
        return blocks[(y * l + z) * w + x];
    }

    /** A schematic that can't be read, with a message for people. */
    public static final class SchematicException extends Exception {
        private static final long serialVersionUID = 1L;

        public SchematicException(String message) {
            super(message);
        }
    }

    /** Reads a schematic file (any supported format). */
    public static Schematic read(byte[] bytes, String fileName) throws SchematicException {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String shown = fileName == null || fileName.isBlank() ? "This file" : "“" + fileName + "”";
        Map<String, Object> root;
        try {
            root = Nbt.read(bytes);
        } catch (IOException | RuntimeException e) {
            throw new SchematicException(shown + " isn't a schematic the mod can read (" + (lower.endsWith(".zip") ? "unpack the .zip first" : "supported: .litematic, .schem, .nbt") + ").");
        }
        try {
            if (Nbt.compound(root.get("Regions")) != null) return litematic(root);
            Map<String, Object> sp = Nbt.compound(root.get("Schematic")) != null ? Nbt.compound(root.get("Schematic")) : root;
            if (Nbt.compound(sp.get("Palette")) != null || Nbt.compound(sp.get("Blocks")) != null || sp.get("BlockData") instanceof byte[]) return sponge(root);
            if (root.get("Blocks") instanceof byte[]) {
                throw new SchematicException(shown + " is an old MCEdit/Schematica schematic (before Minecraft 1.13). The app shows it, the mod can't yet.");
            }
            if (Nbt.list(root.get("size")) != null && Nbt.list(root.get("blocks")) != null) return structure(root);
            if (Nbt.compound(root.get("Schematic")) != null) return sponge(root);
        } catch (TooBig e) {
            throw new SchematicException(shown + " is too big for the game view (" + e.getMessage() + " blocks; up to " + MAX_VOLUME / 1_000_000 + " million). The app shows it.");
        } catch (RuntimeException e) {
            throw new SchematicException(shown + " looks damaged (" + e.getClass().getSimpleName() + ").");
        }
        throw new SchematicException(shown + " is NBT, but not a schematic format the mod knows (supported: .litematic, .schem, .nbt).");
    }

    // ---------------- block-state keys ----------------

    /** name + properties of a block-state key ("minecraft:oak_stairs[facing=east]"; an NBT "{…}" suffix is ignored) */
    public record State(String name, Map<String, String> props) {}

    public static State parseKey(String key) {
        String k = key.strip();
        int brace = k.indexOf('{');
        if (brace >= 0) k = k.substring(0, brace);
        int open = k.indexOf('[');
        String name = (open >= 0 ? k.substring(0, open) : k).strip();
        if (!name.isEmpty() && name.indexOf(':') < 0) name = "minecraft:" + name;
        Map<String, String> props = new TreeMap<>();
        if (open >= 0) {
            int close = k.lastIndexOf(']');
            String inner = close > open ? k.substring(open + 1, close) : k.substring(open + 1);
            for (String kv : inner.split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) props.put(kv.substring(0, eq).strip(), kv.substring(eq + 1).strip());
            }
        }
        return new State(name, props);
    }

    /** "name[a=1,b=2]" with the properties sorted (same as the app's stateKey) */
    public static String stateKey(String name, Map<String, String> props) {
        if (props == null || props.isEmpty()) return name;
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(props).entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    private static final class PaletteBuilder {
        final List<String> keys = new ArrayList<>(List.of("minecraft:air"));
        final Map<String, Integer> map = new HashMap<>(Map.of("minecraft:air", 0));

        int add(String name, Map<String, String> props) {
            if (name == null || name.isEmpty() || AIR.contains(name)) return 0;
            String key = stateKey(name, props);
            Integer i = map.get(key);
            if (i == null) {
                i = keys.size();
                if (i > 65535) throw new IllegalStateException("more than 65 535 block states");
                keys.add(key);
                map.put(key, i);
            }
            return i;
        }

        int addKey(String key) {
            State s = parseKey(key);
            return add(s.name(), s.props());
        }

        /** a palette entry: {Name, Properties}, 26.3's {id, properties}, or a bare name */
        int addEntry(Object entry) {
            if (entry instanceof String s) return add(s.indexOf(':') < 0 ? "minecraft:" + s : s, null);
            Map<String, Object> c = Nbt.compound(entry);
            if (c == null) return 0;
            Object n = c.containsKey("") ? c.get("") : c.containsKey("Name") ? c.get("Name") : c.containsKey("id") ? c.get("id") : c.get("name");
            if (!(n instanceof String name)) return 0;
            Map<String, Object> raw = Nbt.compound(c.containsKey("Properties") ? c.get("Properties") : c.get("properties"));
            Map<String, String> props = new LinkedHashMap<>();
            if (raw != null) raw.forEach((k, v) -> props.put(k, String.valueOf(v)));
            return add(name.indexOf(':') < 0 ? "minecraft:" + name : name, props);
        }
    }

    private static final class TooBig extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TooBig(String size) {
            super(size);
        }
    }

    private static void checkVolume(int w, int h, int l) {
        if (w <= 0 || h <= 0 || l <= 0) throw new IllegalArgumentException("empty schematic");
        long v = (long) w * h * l;
        if (v > MAX_VOLUME) throw new TooBig(w + " × " + h + " × " + l);
    }

    // ---------------- Litematica (.litematic) ----------------

    private static Schematic litematic(Map<String, Object> root) {
        Map<String, Object> regions = Nbt.compound(root.get("Regions"));
        Map<String, Object> meta = Nbt.compound(root.get("Metadata"));
        if (meta == null) meta = Map.of();
        record Region(int[] min, int[] size, List<Object> pal, long[] states) {}
        List<Region> rs = new ArrayList<>();
        for (Object o : regions.values()) {
            Map<String, Object> r = Nbt.compound(o);
            if (r == null) continue;
            Map<String, Object> pos = Nbt.compound(r.get("Position")), size = Nbt.compound(r.get("Size"));
            if (pos == null || size == null) continue;
            int[] p = {Nbt.num(pos.get("x")), Nbt.num(pos.get("y")), Nbt.num(pos.get("z"))};
            int[] s = {Nbt.num(size.get("x")), Nbt.num(size.get("y")), Nbt.num(size.get("z"))};
            if (s[0] == 0 || s[1] == 0 || s[2] == 0) continue;
            // negative sizes grow towards -x/-y/-z from the region's origin corner
            int[] min = new int[3];
            for (int i = 0; i < 3; i++) min[i] = p[i] + (s[i] < 0 ? s[i] + 1 : 0);
            List<Object> pal = Nbt.list(r.get("BlockStatePalette"));
            rs.add(new Region(min, new int[] {Math.abs(s[0]), Math.abs(s[1]), Math.abs(s[2])}, pal == null ? List.of() : pal,
                    r.get("BlockStates") instanceof long[] a ? a : null));
        }
        if (rs.isEmpty()) throw new IllegalArgumentException("no regions");
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Region r : rs) {
            for (int i = 0; i < 3; i++) {
                lo[i] = Math.min(lo[i], r.min()[i]);
                hi[i] = Math.max(hi[i], r.min()[i] + r.size()[i] - 1);
            }
        }
        int w = hi[0] - lo[0] + 1, h = hi[1] - lo[1] + 1, l = hi[2] - lo[2] + 1;
        checkVolume(w, h, l);
        PaletteBuilder pb = new PaletteBuilder();
        char[] blocks = new char[w * h * l];
        for (Region r : rs) {
            int[] remap = new int[r.pal().size()];
            for (int i = 0; i < remap.length; i++) remap[i] = pb.addEntry(r.pal().get(i));
            long[] words = r.states();
            if (words == null) continue;
            // Litematica packs values tightly across long boundaries (unlike chunk sections since 1.16)
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, remap.length - 1)));
            long mask = (1L << bits) - 1;
            int sx = r.size()[0], sy = r.size()[1], sz = r.size()[2];
            int ox = r.min()[0] - lo[0], oy = r.min()[1] - lo[1], oz = r.min()[2] - lo[2];
            long i = 0;
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    int row = ((y + oy) * l + (z + oz)) * w + ox;
                    for (int x = 0; x < sx; x++, i++) {
                        long bit = i * bits;
                        int k = (int) (bit >>> 6);
                        if (k >= words.length) continue;
                        int off = (int) (bit & 63);
                        long v = words[k] >>> off;
                        if (off + bits > 64 && k + 1 < words.length) v |= words[k + 1] << (64 - off);
                        int idx = (int) (v & mask);
                        int b = idx < remap.length ? remap[idx] : 0;
                        if (b != 0) blocks[row + x] = (char) b;
                    }
                }
            }
        }
        return new Schematic("litematic", w, h, l, pb.keys, blocks, Nbt.str(meta.get("Name")), Nbt.str(meta.get("Author")));
    }

    // ---------------- Sponge / WorldEdit (.schem) ----------------

    private static Schematic sponge(Map<String, Object> root) {
        Map<String, Object> sch = Nbt.compound(root.get("Schematic")) != null ? Nbt.compound(root.get("Schematic")) : root;
        int w = Nbt.num(sch.get("Width")) & 0xFFFF, h = Nbt.num(sch.get("Height")) & 0xFFFF, l = Nbt.num(sch.get("Length")) & 0xFFFF;
        checkVolume(w, h, l);
        // v3 keeps blocks in a "Blocks" container, v1/v2 at the top level
        Map<String, Object> container = Nbt.compound(sch.get("Blocks")) != null ? Nbt.compound(sch.get("Blocks")) : sch;
        Map<String, Object> pal = Nbt.compound(container.get("Palette"));
        Object data = container == sch ? sch.get("BlockData") : container.get("Data");
        Map<String, Object> meta = Nbt.compound(sch.get("Metadata"));
        if (meta == null) meta = Map.of();
        PaletteBuilder pb = new PaletteBuilder();
        Map<Integer, Integer> remap = new HashMap<>();
        if (pal != null) pal.forEach((key, idx) -> remap.put(Nbt.num(idx), pb.addKey(key)));
        char[] blocks = new char[w * h * l];
        if (data instanceof byte[] bytes && pal != null) {
            int p = 0;
            for (int i = 0; i < blocks.length && p < bytes.length; i++) {
                // unsigned LEB128 varint
                int v = 0, shift = 0, b;
                do {
                    b = bytes[p++] & 0xFF;
                    v |= (b & 0x7F) << shift;
                    shift += 7;
                } while ((b & 0x80) != 0 && shift < 35 && p < bytes.length);
                blocks[i] = (char) (int) remap.getOrDefault(v, 0);
            }
        }
        return new Schematic("sponge", w, h, l, pb.keys, blocks, Nbt.str(meta.get("Name")), Nbt.str(meta.get("Author")));
    }

    // ---------------- vanilla structure files (.nbt) ----------------

    private static Schematic structure(Map<String, Object> root) {
        List<Object> size = Nbt.list(root.get("size"));
        int w = Nbt.num(size.get(0)), h = Nbt.num(size.get(1)), l = Nbt.num(size.get(2));
        checkVolume(w, h, l);
        List<Object> pal = Nbt.list(root.get("palette"));
        // structures with random variants (shipwrecks …) have several palettes: show the first
        if (pal == null && Nbt.list(root.get("palettes")) != null && !Nbt.list(root.get("palettes")).isEmpty()) pal = Nbt.list(Nbt.list(root.get("palettes")).get(0));
        if (pal == null) pal = List.of();
        PaletteBuilder pb = new PaletteBuilder();
        int[] remap = new int[pal.size()];
        boolean[] jigsaw = new boolean[pal.size()];
        for (int i = 0; i < remap.length; i++) {
            remap[i] = pb.addEntry(pal.get(i));
            jigsaw[i] = pb.keys.get(remap[i]).startsWith("minecraft:jigsaw");
        }
        char[] blocks = new char[w * h * l];
        List<Object> bl = Nbt.list(root.get("blocks"));
        for (Object o : bl) {
            Map<String, Object> b = Nbt.compound(o);
            List<Object> pos = b == null ? null : Nbt.list(b.get("pos"));
            if (pos == null || pos.size() < 3) continue;
            int x = Nbt.num(pos.get(0)), y = Nbt.num(pos.get(1)), z = Nbt.num(pos.get(2));
            if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= l) continue;
            int st = Nbt.num(b.get("state"));
            int v = st >= 0 && st < remap.length ? remap[st] : 0;
            // jigsaw blocks turn into their "final state" when the structure is placed
            if (st >= 0 && st < jigsaw.length && jigsaw[st]) {
                Map<String, Object> nbt = Nbt.compound(b.get("nbt"));
                v = nbt != null && nbt.get("final_state") instanceof String fs ? pb.addKey(fs) : 0;
            }
            blocks[(y * l + z) * w + x] = (char) v;
        }
        return new Schematic("structure", w, h, l, pb.keys, blocks, null, Nbt.str(root.get("author")));
    }
}
