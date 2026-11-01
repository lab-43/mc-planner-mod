package dev.lab43.mcplanner.mc.render;

import java.lang.reflect.Field;

import dev.lab43.mcplanner.core.Kind;
import dev.lab43.mcplanner.core.ModConfig;
import dev.lab43.mcplanner.core.PlanObject;
import dev.lab43.mcplanner.core.Status;
import dev.lab43.mcplanner.mc.Texts;

/**
 * Checks the in-world geometry without a running game: shapes and meshes for each kind on flat, sloped and unloaded
 * terrain, plus the HUD arrows. Needs Minecraft's jar on the classpath (Vec3, ARGB) — run with tools/test-mc.sh.
 */
public final class MeshTest {
    private static int checks, failed;

    public static void main(String[] args) throws Exception {
        area();
        slopedArea();
        lShapedArea();
        path();
        building();
        marker();
        unloaded();
        textured();
        crops();
        texturedBuilding();
        bigPlainBuilding();
        emitted();
        arrows();
        colours();
        GhostTest.run();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static PlanObject obj(Kind k, int[][] pts, int y, int width, int height, Status s) {
        return PlanObject.builder("id-" + k.id(), k).name(k.label() + " 1").color(0x4AB0E8).status(s).dimension("minecraft:overworld")
                .points(pts).y(y).width(width).height(height).storey(4).build();
    }

    static int lines(Mesh m) throws Exception { return field(m, "lines"); }
    static int quads(Mesh m) throws Exception { return field(m, "quads"); }

    static int field(Mesh m, String name) throws Exception {
        Field f = Mesh.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(m);
    }

    static Mesh mesh(PlanObject o, Overlay.Heights h, ModConfig c, Overlay.Entry e) {
        Shape s = Shape.of(o);
        check(s != null, o.kind + " has a shape");
        return Overlay.build(s, h, Paint.PLAIN, c, false, e);
    }

    static void area() throws Exception {
        PlanObject o = obj(Kind.AREA, new int[][] {{0, 0}, {4, 0}, {4, 4}, {0, 4}}, 64, 0, 0, Status.PLANNED);
        Shape s = Shape.of(o);
        eq(s.unitEdges.length / 6, 20, "5×5 area: 20 unit edges");
        eq(s.tiles.length / 2, 25, "5×5 area: 25 tiles");
        Overlay.Entry e = new Overlay.Entry();
        Mesh m = Overlay.build(s, (x, z) -> 64, Paint.PLAIN, new ModConfig(), false, e);
        eq(lines(m), 20 + 4, "flat area: outline + 4 corner posts, no risers");
        eq(quads(m), 0, "area fill off by default");
        near(m.minY, 64, "posts start on the ground");
        near(m.maxY, 67, "posts are 3 high");
        near(e.labelY, 66.2, "label above the ground");
        near(s.labelX, 2.5, "label x in the middle");
        ModConfig fill = new ModConfig();
        fill.areaFill = true;
        Mesh filled = Overlay.build(s, (x, z) -> 64, Paint.PLAIN, fill, false, new Overlay.Entry());
        eq(quads(filled), 25, "area fill: one tile per block");
        check(filled.hasFills(false) && !filled.hasFills(true), "plain fill uses the white texture, not the block atlas");
    }

    static void slopedArea() throws Exception {
        PlanObject o = obj(Kind.AREA, new int[][] {{0, 0}, {4, 0}, {4, 4}, {0, 4}}, 64, 0, 0, Status.PLANNED);
        Mesh m = mesh(o, (x, z) -> 64 + x, new ModConfig(), new Overlay.Entry());
        // 20 edges + 4 posts + risers where neighbouring edges differ: the 4 inner steps on each of the two x-runs
        eq(lines(m), 20 + 4 + 8, "sloped area: risers join the steps");
        near(m.minY, 64, "lowest post on the lowest block");
        near(m.maxY, 68 + 3, "post on the high side");
    }

    static void lShapedArea() {
        PlanObject o = obj(Kind.AREA, new int[][] {{0, 0}, {10, 0}, {10, 2}, {2, 2}, {2, 10}, {0, 10}}, 64, 0, 0, Status.PLANNED);
        Shape s = Shape.of(o);
        check(s.grid.has((int) Math.floor(s.labelX - 0.5), (int) Math.floor(s.labelZ - 0.5)), "L-shaped area: label over a covered block");
    }

    static void path() throws Exception {
        PlanObject o = obj(Kind.PATH, new int[][] {{0, 0}, {10, 0}}, 64, 3, 0, Status.IN_PROGRESS);
        Shape s = Shape.of(o);
        int tiles = s.tiles.length / 2;
        eq(tiles, o.grid().count, "path: a tile per paved block");
        eq(tiles, 33, "11 long × 3 wide");
        Mesh m = mesh(o, (x, z) -> 70, new ModConfig(), new Overlay.Entry());
        eq(quads(m), 33, "path tiles drawn");
        int centre = s.centre.length / 2 - 1;
        eq(lines(m), s.unitEdges.length / 6 + centre, "path: outline + centre line, flat → no risers");
        near(m.minY, 70.02, "tiles on the ground");
        ModConfig noTiles = new ModConfig();
        noTiles.pathTiles = false;
        eq(quads(mesh(o, (x, z) -> 70, noTiles, new Overlay.Entry())), 0, "path tiles can be switched off");
    }

    static void building() throws Exception {
        PlanObject o = obj(Kind.BUILDING, new int[][] {{0, 0}, {4, 0}, {4, 4}, {0, 4}}, 70, 0, 8, Status.PLANNED);
        Overlay.Entry e = new Overlay.Entry();
        Mesh m = mesh(o, (x, z) -> Overlay.NONE, new ModConfig(), e);
        eq(lines(m), 4 + 4 + 4 + 4, "building: bottom, top, one storey line per wall, 4 corners");
        eq(quads(m), 4 + 1, "building: 4 see-through walls and a roof (one rectangle)");
        near(m.minY, 70, "starts at its y");
        near(m.maxY, 78, "8 high");
        near(m.minX, 0, "covers the corner blocks (x0)");
        near(m.maxX, 5, "covers the corner blocks (x1 + 1)");
        near(e.labelY, 78.8, "label on the roof");
        PlanObject legacy = obj(Kind.BUILDING, new int[][] {{0, 0}, {4, 4}}, 70, 0, 8, Status.PLANNED);
        eq(quads(mesh(legacy, (x, z) -> 64, new ModConfig(), new Overlay.Entry())), 4 + 1, "2-corner building = rectangle");
        Overlay.Entry withBlocks = new Overlay.Entry();
        withBlocks.showsBlocks = true;
        Mesh sm = mesh(o, (x, z) -> 64, new ModConfig(), withBlocks);
        eq(quads(sm), 0, "schematic building: its blocks replace walls and roof");
        eq(lines(sm), 4 + 4 + 4, "…and the storey lines; bottom, top and corners stay");
        PlanObject ell = obj(Kind.BUILDING, new int[][] {{0, 0}, {10, 0}, {10, 2}, {2, 2}, {2, 10}, {0, 10}}, 70, 0, 8, Status.PLANNED);
        Shape es = Shape.of(ell);
        Mesh em = Overlay.build(es, (x, z) -> 64, Paint.PLAIN, new ModConfig(), false, new Overlay.Entry());
        eq(quads(em), es.outline.length / 4 + 2, "L-shaped building: a wall per outline segment, roof as 2 rectangles");
    }

    static void marker() throws Exception {
        PlanObject o = obj(Kind.MARKER, new int[][] {{10, -5}}, 64, 0, 0, Status.PLANNED);
        Overlay.Entry e = new Overlay.Entry();
        Mesh m = mesh(o, (x, z) -> 64, new ModConfig(), e);
        eq(lines(m), 12 + 1 + 4, "marker: block outline, beam, diamond");
        near(m.minX, 10, "marker block x");
        near(m.minZ, -5, "marker block z");
        check(e.labelY > 64 + 12, "marker label above the beam");
    }

    static void unloaded() throws Exception {
        PlanObject o = obj(Kind.PATH, new int[][] {{0, 0}, {10, 0}}, 64, 3, 0, Status.PLANNED);
        Mesh m = mesh(o, (x, z) -> Overlay.NONE, new ModConfig(), new Overlay.Entry());
        check(m.isEmpty(), "nothing drawn over unloaded chunks");
    }

    /** a stand-in for the game's block textures: every material has a grass-tinted top and a plain side */
    static Paint fakeTextures(boolean under) {
        return new Paint() {
            @Override
            public Look look(String material, int x, int y, int z) {
                if (material == null) return null;
                return new Look(new Skin(0.10f, 0.20f, 0.15f, 0.25f, 0x91BD59), new Skin(0.30f, 0.30f, 0.35f, 0.35f, 0xFFFFFF),
                        under ? new Skin(0.50f, 0.50f, 0.55f, 0.55f, 0xFFFFFF) : null);
            }

            @Override
            public int light(int x, int y, int z) {
                return 0xF00000; // sky 15, block 0
            }
        };
    }

    static float[] uv(Mesh m, int quad) throws Exception {
        Field f = Mesh.class.getDeclaredField("fillUv");
        f.setAccessible(true);
        return java.util.Arrays.copyOfRange((float[]) f.get(m), quad * 8, quad * 8 + 8);
    }

    static int intAt(Mesh m, String name, int i) throws Exception {
        Field f = Mesh.class.getDeclaredField(name);
        f.setAccessible(true);
        return ((int[]) f.get(m))[i];
    }

    static void textured() throws Exception {
        PlanObject o = PlanObject.builder("gravel", Kind.PATH).name("Road").color(0xFFFFFF).status(Status.PLANNED).dimension("minecraft:overworld")
                .points(new int[][] {{0, 0}, {10, 0}}).y(64).width(3).material("minecraft:gravel").build();
        Shape s = Shape.of(o);
        ModConfig noTiles = new ModConfig();
        noTiles.pathTiles = false;
        Mesh m = Overlay.build(s, (x, z) -> 70, fakeTextures(false), noTiles, false, new Overlay.Entry());
        eq(quads(m), 33, "a path with a block shows it on every tile (even with plain path tiles off)");
        check(m.hasFills(true) && !m.hasFills(false), "textured tiles use the block atlas");
        float[] uv = uv(m, 0);
        near(uv[0], 0.10f, "tile corner 1 = u0");
        near(uv[1], 0.20f, "tile corner 1 = v0");
        near(uv[4], 0.15f, "tile corner 3 = u1");
        near(uv[5], 0.25f, "tile corner 3 = v1");
        eq(intAt(m, "fillLights", 0), 0xF00000, "tiles are lit like the block they lie on");
        int color = intAt(m, "fillColors", 0);
        eq(color & 0xFFFFFF, 0x91BD59, "texture keeps its biome tint (white object colour adds nothing)");
        check((color >>> 24) > (Overlay.colors(o, new ModConfig(), false)[1] >>> 24), "textures are more solid than a plain fill");
        Mesh off = Overlay.build(s, (x, z) -> 70, Paint.PLAIN, noTiles, false, new Overlay.Entry());
        eq(quads(off), 0, "textures off + path tiles off → no tiles");

        PlanObject area = PlanObject.builder("pond", Kind.AREA).name("Pond").color(0x3F76E4).status(Status.PLANNED).dimension("minecraft:overworld")
                .points(new int[][] {{0, 0}, {4, 0}, {4, 4}, {0, 4}}).y(64).material("minecraft:water").build();
        ModConfig noFill = new ModConfig();
        noFill.areaFill = false;
        eq(quads(Overlay.build(Shape.of(area), (x, z) -> 64, fakeTextures(false), noFill, false, new Overlay.Entry())), 25,
                "an area with a block is filled with it even with the plain area fill off");

        PlanObject done = o.toBuilder().status(Status.DONE).build(), idea = o.toBuilder().status(Status.IDEA).build();
        ModConfig c = new ModConfig();
        check(Overlay.textureAlpha(done, c) < Overlay.textureAlpha(o, c), "built (done) textures are fainter");
        check(Overlay.textureAlpha(idea, c) < Overlay.textureAlpha(o, c), "idea textures are fainter");
        c.fillOpacity = 1f;
        check(Overlay.textureAlpha(o, c) <= 0.95f, "textures never fully hide the world");
        eq(Overlay.textureColor(new Paint.Skin(0, 0, 1, 1, 0xFFFFFF), o.toBuilder().color(0xFF0000).build(), 1f) & 0xFFFFFF, 0xFFD9D9,
                "a touch (15 %) of the object's colour");
    }

    static void crops() throws Exception {
        PlanObject o = PlanObject.builder("wheat", Kind.AREA).name("Field").color(0xFFFFFF).status(Status.PLANNED).dimension("minecraft:overworld")
                .points(new int[][] {{0, 0}, {4, 0}, {4, 4}, {0, 4}}).y(64).material("minecraft:wheat").build();
        Mesh m = Overlay.build(Shape.of(o), (x, z) -> 64, fakeTextures(true), new ModConfig(), false, new Overlay.Entry());
        eq(quads(m), 50, "see-through plants: ground (farmland) under every tile");
        near(m.minY, 64, "fill starts on the ground");
        float[] first = uv(m, 0), second = uv(m, 1);
        near(first[0], 0.50f, "first the ground under the crop");
        near(second[0], 0.10f, "then the crop on top");
    }

    static void texturedBuilding() throws Exception {
        PlanObject o = PlanObject.builder("house", Kind.BUILDING).name("House").color(0xFFFFFF).status(Status.PLANNED).dimension("minecraft:overworld")
                .points(new int[][] {{0, 0}, {4, 0}, {4, 4}, {0, 4}}).y(70).height(8).storey(4).material("minecraft:stone_bricks").build();
        Mesh m = Overlay.build(Shape.of(o), (x, z) -> 64, fakeTextures(false), new ModConfig(), false, new Overlay.Entry());
        eq(quads(m), 20 * 8 + 25, "textured building: a wall quad per block (20 around × 8 high) + a roof tile per block");
        check(m.hasFills(true) && !m.hasFills(false), "walls and roof from the block atlas");
        float[] wall = uv(m, 0);
        near(wall[0], 0.30f, "walls use the side texture");
        near(m.maxY, 78, "walls end at the top");
    }

    static void bigPlainBuilding() throws Exception {
        PlanObject o = obj(Kind.BUILDING, new int[][] {{0, 0}, {39, 0}, {39, 4}, {0, 4}}, 70, 0, 8, Status.PLANNED);
        Mesh m = Overlay.build(Shape.of(o), (x, z) -> 64, Paint.PLAIN, new ModConfig(), false, new Overlay.Entry());
        // walls along x (40 long) split at x = 16, 32 → 3 pieces each; walls along z (5 long) whole; roof 3 pieces
        eq(quads(m), 3 + 3 + 1 + 1 + 3, "long walls and the roof are split at the 16-block cell borders");
        Mesh lines = new Mesh();
        lines.line(0, 64, 0, 40, 64, 0, 0xFFFFFFFF, 2f);
        eq(lines(lines), 3, "a 40-block line becomes 3 pieces (cells 0, 1, 2)");
        Mesh diag = new Mesh();
        diag.line(-1, 64, -1, 17, 64, 17, 0xFFFFFFFF, 2f);
        eq(lines(diag), 3, "a diagonal line crossing x = 0 and z = 0 at once and x = 16, z = 16 at once: 3 pieces");
    }

    /** what reaches the game's vertex buffer */
    static void emitted() {
        PlanObject o = obj(Kind.BUILDING, new int[][] {{0, 0}, {4, 0}, {4, 4}, {0, 4}}, 70, 0, 8, Status.DONE);
        ModConfig c = new ModConfig();
        c.fillOpacity = 0.1f;
        Mesh m = Overlay.build(Shape.of(o), (x, z) -> 64, Paint.PLAIN, c, false, new Overlay.Entry());
        int[] count = {0};
        Mesh.Visible v = m.visible(new net.minecraft.client.renderer.culling.Frustum(new org.joml.Matrix4f(), new org.joml.Matrix4f()) {
            @Override
            public boolean isVisible(net.minecraft.world.phys.AABB box) {
                return true;
            }
        }, 2, 2, 500, 500, count);
        check(v != null, "everything visible");
        java.util.List<float[]> verts = new java.util.ArrayList<>();
        int[] minAlpha = {255};
        com.mojang.blaze3d.vertex.VertexConsumer vc = new com.mojang.blaze3d.vertex.VertexConsumer() {
            float[] cur;
            public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) { cur = new float[] {x, y, z, 0, 0, 0}; verts.add(cur); return this; }
            public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) { minAlpha[0] = Math.min(minAlpha[0], a); return this; }
            public com.mojang.blaze3d.vertex.VertexConsumer setColor(int argb) { minAlpha[0] = Math.min(minAlpha[0], argb >>> 24); return this; }
            public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) { return this; }
            public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) { return this; }
            public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) { return this; }
            public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) { cur[3] = x; cur[4] = y; cur[5] = z; return this; }
            public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float w) { return this; }
        };
        m.emitFills(new com.mojang.blaze3d.vertex.PoseStack().last(), vc, false, v, 0.5f, null, 100, 60, 100);
        eq(verts.size(), (4 + 1) * 4, "4 walls + roof, each written once, from the side the camera sees");
        check(minAlpha[0] >= Mesh.MIN_ALPHA, "faint fills are lifted to the minimum alpha shaders still draw");
        boolean facing = true;
        for (float[] p : verts) {
            // vertices are camera-relative: the camera is at 0, so the normal must point towards −position
            if (p[3] * -p[0] + p[4] * -p[1] + p[5] * -p[2] < -1e-4) facing = false;
        }
        check(facing, "every written face looks at the camera (walls from outside, the roof from below)");
        float[] roof = verts.get(16);
        near(roof[4], -1, "the roof seen from below: its normal turned down");
        near(roof[1], 78 - 60, "camera-relative y (roof)");

        // schematic blocks already built are left out
        Mesh g = new Mesh();
        g.group(0);
        g.tile(0, 64, 0, null, 0xFFFFFFFF, 0, false);
        g.group(1);
        g.tile(1, 64, 0, null, 0xFFFFFFFF, 0, false);
        g.group(-1);
        java.util.BitSet built = new java.util.BitSet();
        built.set(0);
        verts.clear();
        Mesh.Visible gv = g.visible(new net.minecraft.client.renderer.culling.Frustum(new org.joml.Matrix4f(), new org.joml.Matrix4f()) {
            @Override
            public boolean isVisible(net.minecraft.world.phys.AABB box) {
                return true;
            }
        }, 0, 0, 500, 500, new int[1]);
        g.emitFills(new com.mojang.blaze3d.vertex.PoseStack().last(), vc, false, gv, 1f, built, 0.5, 80, 0.5);
        eq(verts.size(), 4, "a built block isn't drawn, the other one is");
        near(verts.get(0)[0], 1 - 0.5, "…the second one");
        verts.clear();
        g.emitFills(new com.mojang.blaze3d.vertex.PoseStack().last(), vc, false, gv, 1f, built, 0.5, 10, 0.5);
        eq(verts.size(), 0, "ground tiles seen from below aren't written");
    }

    static void arrows() {
        eq(Texts.arrow(0, 10, 0f), "↑", "facing south, target south → ahead");
        eq(Texts.arrow(-10, 0, 0f), "→", "facing south, target west → right");
        eq(Texts.arrow(10, 0, 0f), "←", "facing south, target east → left");
        eq(Texts.arrow(0, -10, 0f), "↓", "facing south, target north → behind");
        eq(Texts.arrow(0, -10, 180f), "↑", "facing north, target north → ahead");
        eq(Texts.compass(10, 0), "E", "east");
        eq(Texts.compass(0, -10), "N", "north");
        eq(Texts.compass(-7, 7), "SW", "south-west");
    }

    static void colours() {
        ModConfig c = new ModConfig();
        int[] planned = Overlay.colors(obj(Kind.AREA, new int[][] {{0, 0}}, 64, 0, 0, Status.PLANNED), c, false);
        int[] done = Overlay.colors(obj(Kind.AREA, new int[][] {{0, 0}}, 64, 0, 0, Status.DONE), c, false);
        int[] idea = Overlay.colors(obj(Kind.AREA, new int[][] {{0, 0}}, 64, 0, 0, Status.IDEA), c, false);
        check((planned[0] >>> 24) > (done[0] >>> 24), "done is fainter than planned");
        check((planned[0] >>> 24) > (idea[0] >>> 24), "ideas are fainter than planned");
        eq(planned[0] & 0xFFFFFF, 0x4AB0E8, "planned keeps its colour");
        eq(Overlay.colors(obj(Kind.AREA, new int[][] {{0, 0}}, 64, 0, 0, Status.DONE), c, true)[0] >>> 24, 255, "the navigation target is fully opaque");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    static void eq(Object a, Object b, String what) {
        checks++;
        if (!java.util.Objects.equals(a, b)) {
            failed++;
            System.out.println("FAIL " + what + ": got " + a + ", want " + b);
        }
    }

    static void near(double a, double b, String what) {
        checks++;
        if (Math.abs(a - b) > 1e-6) {
            failed++;
            System.out.println("FAIL " + what + ": got " + a + ", want " + b);
        }
    }
}
