package dev.lab43.mcplanner.core;

import static dev.lab43.mcplanner.core.TestMain.check;
import static dev.lab43.mcplanner.core.TestMain.eq;
import static dev.lab43.mcplanner.core.TestMain.expectThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * Schematic files (made here with a tiny NBT writer — no real builds in the repo, they belong to their creators):
 * Litematica bit packing across longs, several and negative regions, Sponge v2/v3 varints, structure files with
 * jigsaw blocks, damaged files, and finding a file by its hash.
 */
final class SchematicTest {
    private SchematicTest() {}

    static void run() throws Exception {
        nbt();
        litematic();
        litematicRegions();
        sponge();
        structure();
        broken();
        keys();
        files();
    }

    // ---------------- a tiny NBT writer ----------------

    static final class Tag {
        final int type;
        final Object value;

        Tag(int type, Object value) {
            this.type = type;
            this.value = value;
        }
    }

    static Tag i(int v) { return new Tag(3, v); }
    static Tag s(short v) { return new Tag(2, v); }
    static Tag str(String v) { return new Tag(8, v); }
    static Tag longs(long[] v) { return new Tag(12, v); }
    static Tag bytes(byte[] v) { return new Tag(7, v); }

    static Tag c(Object... kv) {
        Map<String, Tag> m = new LinkedHashMap<>();
        for (int k = 0; k < kv.length; k += 2) m.put((String) kv[k], (Tag) kv[k + 1]);
        return new Tag(10, m);
    }

    static Tag list(Tag... items) { return new Tag(9, List.of(items)); }

    static byte[] file(Tag root, boolean gzip) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(10);
        out.writeUTF("");
        body(out, root);
        out.flush();
        if (!gzip) return raw.toByteArray();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(z)) {
            g.write(raw.toByteArray());
        }
        return z.toByteArray();
    }

    @SuppressWarnings("unchecked")
    static void body(DataOutputStream out, Tag t) throws IOException {
        switch (t.type) {
            case 2 -> out.writeShort((Short) t.value);
            case 3 -> out.writeInt((Integer) t.value);
            case 7 -> {
                byte[] b = (byte[]) t.value;
                out.writeInt(b.length);
                out.write(b);
            }
            case 8 -> out.writeUTF((String) t.value);
            case 9 -> {
                List<Tag> l = (List<Tag>) t.value;
                out.writeByte(l.isEmpty() ? 0 : l.get(0).type);
                out.writeInt(l.size());
                for (Tag x : l) body(out, x);
            }
            case 10 -> {
                for (Map.Entry<String, Tag> e : ((Map<String, Tag>) t.value).entrySet()) {
                    out.writeByte(e.getValue().type);
                    out.writeUTF(e.getKey());
                    body(out, e.getValue());
                }
                out.writeByte(0);
            }
            case 12 -> {
                long[] a = (long[]) t.value;
                out.writeInt(a.length);
                for (long v : a) out.writeLong(v);
            }
            default -> throw new IllegalArgumentException("type " + t.type);
        }
    }

    /** Litematica's tight packing: value k at bits [k·bits, (k+1)·bits), spanning longs */
    static long[] pack(int[] values, int bits) {
        long[] out = new long[(int) (((long) values.length * bits + 63) / 64)];
        for (int k = 0; k < values.length; k++) {
            long bit = (long) k * bits;
            int w = (int) (bit >>> 6), off = (int) (bit & 63);
            out[w] |= ((long) values[k]) << off;
            if (off + bits > 64) out[w + 1] |= ((long) values[k]) >>> (64 - off);
        }
        return out;
    }

    static Tag state(String name, Object... props) {
        if (props.length == 0) return c("Name", str(name));
        Object[] kv = new Object[props.length];
        for (int k = 0; k < props.length; k += 2) {
            kv[k] = props[k];
            kv[k + 1] = str((String) props[k + 1]);
        }
        return c("Name", str(name), "Properties", c(kv));
    }

    static Tag xyz(int x, int y, int z) {
        return c("x", i(x), "y", i(y), "z", i(z));
    }

    // ---------------- tests ----------------

    static void nbt() throws Exception {
        Map<String, Object> m = Nbt.read(file(c("a", i(5), "b", str("hé"), "l", list(i(1), i(2)), "e", new Tag(9, List.of())), true));
        eq(5, m.get("a"), "int");
        eq("hé", m.get("b"), "modified UTF-8 string");
        eq(List.of(1, 2), m.get("l"), "list");
        eq(List.of(), m.get("e"), "empty list");
        Map<String, Object> plain = Nbt.read(file(c("a", i(7)), false));
        eq(7, plain.get("a"), "uncompressed NBT too");
        byte[] cut = file(c("a", longs(new long[1000])), false);
        expectThrows(IOException.class, () -> Nbt.read(java.util.Arrays.copyOf(cut, cut.length - 100)), "a cut-off file is refused, not misread");
    }

    static void litematic() throws Exception {
        // 70 blocks with 17 states → 5 bits each: values cross long boundaries
        int w = 7, h = 2, l = 5, n = w * h * l;
        List<Tag> pal = new ArrayList<>();
        pal.add(state("minecraft:air"));
        for (int k = 1; k < 17; k++) pal.add(state("minecraft:stone_stairs", "facing", k % 2 == 0 ? "east" : "west", "shape", "s" + k, "half", "bottom"));
        int[] v = new int[n];
        for (int k = 0; k < n; k++) v[k] = k % 17;
        Tag region = c("Position", xyz(0, 0, 0), "Size", xyz(w, h, l), "BlockStatePalette", list(pal.toArray(new Tag[0])), "BlockStates", longs(pack(v, 5)));
        byte[] f = file(c("Version", i(6), "MinecraftDataVersion", i(4903), "Metadata", c("Name", str("Test"), "Author", str("Someone")),
                "Regions", c("Main", region)), true);
        Schematic s = Schematic.read(f, "test.litematic");
        eq("litematic", s.format, "format");
        eq(List.of(7, 2, 5), List.of(s.w, s.h, s.l), "size");
        eq("Someone", s.author, "author");
        boolean all = true;
        for (int y = 0; y < h; y++)
            for (int z = 0; z < l; z++)
                for (int x = 0; x < w; x++) {
                    int k = (y * l + z) * w + x;
                    String want = k % 17 == 0 ? "minecraft:air" : "minecraft:stone_stairs[facing=" + (k % 17 % 2 == 0 ? "east" : "west") + ",half=bottom,shape=s" + (k % 17) + "]";
                    if (!s.palette.get(s.at(x, y, z)).equals(want)) all = false;
                }
        check(all, "every block right, also across long boundaries (5 bits, sorted property keys)");
        eq(n - (n + 16) / 17, s.count, "non-air blocks counted");
        eq(0, s.at(-1, 0, 0), "outside the box = air");
    }

    static void litematicRegions() throws Exception {
        // region A at (0,0,0) size 2×1×1 (stone, dirt); region B with a negative size: from (5,0,0) towards -x, 2 long
        Tag a = c("Position", xyz(0, 0, 0), "Size", xyz(2, 1, 1), "BlockStatePalette", list(state("minecraft:air"), state("minecraft:stone"), state("minecraft:dirt")),
                "BlockStates", longs(pack(new int[] {1, 2}, 2)));
        Tag b = c("Position", xyz(5, 0, 0), "Size", xyz(-2, 1, 1), "BlockStatePalette", list(state("minecraft:air"), state("minecraft:glass")),
                "BlockStates", longs(pack(new int[] {1, 0}, 2)));
        Schematic s = Schematic.read(file(c("Regions", c("A", a, "B", b)), true), "two.litematic");
        eq(6, s.w, "regions merged into their box (x 0..5)");
        eq("minecraft:stone", s.palette.get(s.at(0, 0, 0)), "region A first block");
        eq("minecraft:dirt", s.palette.get(s.at(1, 0, 0)), "region A second block");
        eq("minecraft:glass", s.palette.get(s.at(4, 0, 0)), "negative-size region starts at its low corner (x 4)");
        eq(0, s.at(5, 0, 0), "…and its air stays air");
        eq(3, s.count, "3 blocks");
    }

    static byte[] varints(int... v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int x : v) {
            while ((x & ~0x7F) != 0) {
                out.write((x & 0x7F) | 0x80);
                x >>>= 7;
            }
            out.write(x);
        }
        return out.toByteArray();
    }

    static void sponge() throws Exception {
        // v2: palette at the top level; index 200 needs a two-byte varint
        Tag v2 = c("Version", i(2), "Width", s((short) 2), "Height", s((short) 1), "Length", s((short) 2),
                "Palette", c("minecraft:air", i(0), "minecraft:oak_log[axis=y]", i(1), "minecraft:glass", i(200)),
                "BlockData", bytes(varints(1, 0, 200, 1)));
        Schematic s = Schematic.read(file(v2, true), "house.schem");
        eq("sponge", s.format, "v2 format");
        eq("minecraft:oak_log[axis=y]", s.palette.get(s.at(0, 0, 0)), "v2 block (0,0,0)");
        eq("minecraft:glass", s.palette.get(s.at(0, 0, 1)), "v2 two-byte varint");
        eq(3, s.count, "v2 count");
        // v3: under "Schematic", blocks in a container
        Tag v3 = c("Schematic", c("Version", i(3), "Width", s((short) 1), "Height", s((short) 1), "Length", s((short) 1),
                "Metadata", c("Name", str("Tiny")), "Blocks", c("Palette", c("minecraft:stone", i(0)), "Data", bytes(varints(0)))));
        Schematic s3 = Schematic.read(file(v3, true), "tiny.schem");
        eq("minecraft:stone", s3.palette.get(s3.at(0, 0, 0)), "v3 block");
        eq(1, s3.count, "v3 count");
    }

    static void structure() throws Exception {
        Tag root = c("DataVersion", i(4903), "size", list(i(2), i(1), i(1)),
                "palette", list(state("minecraft:stone"), state("minecraft:jigsaw", "orientation", "up_north")),
                "blocks", list(c("pos", list(i(0), i(0), i(0)), "state", i(0)),
                        c("pos", list(i(1), i(0), i(0)), "state", i(1), "nbt", c("final_state", str("minecraft:oak_planks"))),
                        c("pos", list(i(9), i(0), i(0)), "state", i(0))));
        Schematic s = Schematic.read(file(root, true), "piece.nbt");
        eq("structure", s.format, "structure file");
        eq("minecraft:stone", s.palette.get(s.at(0, 0, 0)), "block");
        eq("minecraft:oak_planks", s.palette.get(s.at(1, 0, 0)), "jigsaw shows its final state");
        eq(2, s.count, "blocks outside the size are ignored");
    }

    static void broken() throws Exception {
        Schematic.SchematicException e = expectThrows(Schematic.SchematicException.class, () -> Schematic.read(new byte[] {1, 2, 3}, "x.litematic"), "not NBT");
        check(e != null && e.getMessage().contains("x.litematic"), "the message names the file");
        Schematic.SchematicException old = expectThrows(Schematic.SchematicException.class,
                () -> Schematic.read(file(c("Width", s((short) 1), "Height", s((short) 1), "Length", s((short) 1), "Blocks", bytes(new byte[] {1})), true), "old.schematic"),
                "MCEdit files are explained, not misread");
        check(old != null && old.getMessage().contains("before Minecraft 1.13"), "MCEdit message");
        expectThrows(Schematic.SchematicException.class, () -> Schematic.read(file(c("Regions", c()), true), "empty.litematic"), "no regions");
        Schematic.SchematicException big = expectThrows(Schematic.SchematicException.class,
                () -> Schematic.read(file(c("Width", s((short) 300), "Height", s((short) 300), "Length", s((short) 300), "Palette", c("minecraft:stone", i(0)), "BlockData", bytes(new byte[0])), true), "huge.schem"),
                "too big");
        check(big != null && big.getMessage().contains("too big"), "too big is said as such, not \"damaged\"");
        expectThrows(Schematic.SchematicException.class,
                () -> Schematic.read(file(c("Width", s((short) 0), "Height", s((short) 1), "Length", s((short) 1), "Palette", c(), "BlockData", bytes(new byte[0])), true), "zero.schem"),
                "size 0");
    }

    static void keys() {
        Schematic.State st = Schematic.parseKey("oak_stairs[half=bottom,facing=east]{nbt}");
        eq("minecraft:oak_stairs", st.name(), "bare names get minecraft:");
        eq("minecraft:oak_stairs[facing=east,half=bottom]", Schematic.stateKey(st.name(), st.props()), "sorted, NBT dropped");
        eq("minecraft:stone", Schematic.stateKey("minecraft:stone", Map.of()), "no properties");
    }

    static void files() throws Exception {
        Path dir = TestMain.tempDir("schematics");
        Path sub = Files.createDirectories(dir.resolve("a").resolve("b"));
        byte[] data = file(c("Width", s((short) 1), "Height", s((short) 1), "Length", s((short) 1), "Palette", c("minecraft:stone", i(0)), "BlockData", bytes(new byte[] {0})), true);
        Path f = Files.write(sub.resolve("castle.schem"), data);
        Files.write(dir.resolve("other.litematic"), new byte[] {9, 9, 9});
        Files.write(dir.resolve("notes.txt"), data);
        String hash = SchematicFiles.sha256(data);
        eq(64, hash.length(), "sha256 hex");
        SchematicFiles sf = new SchematicFiles(List.of(new SchematicFiles.Folder(dir, 3)));
        Map<String, Path> found = sf.find(Set.of(hash), Set.of("castle.schem"));
        eq(f.toAbsolutePath().normalize(), found.get(hash) == null ? null : found.get(hash).toAbsolutePath().normalize(), "found two folders down by its hash");
        SchematicFiles shallow = new SchematicFiles(List.of(new SchematicFiles.Folder(dir, 2)));
        check(shallow.find(Set.of(hash), Set.of()).isEmpty(), "not deeper than the folder's depth");
        check(sf.find(Set.of("0".repeat(64)), Set.of()).isEmpty(), "an unknown hash finds nothing");
        Files.write(dir.resolve("renamed.SCHEM"), data);
        Files.delete(f);
        Map<String, Path> again = sf.find(Set.of(hash), Set.of("castle.schem"));
        check(again.get(hash) != null && again.get(hash).getFileName().toString().equals("renamed.SCHEM"), "a renamed copy is found by its content");
        check(new SchematicFiles(List.of(new SchematicFiles.Folder(dir.resolve("missing"), 2))).find(Set.of(hash), Set.of()).isEmpty(), "missing folder: nothing, no error");
    }
}
