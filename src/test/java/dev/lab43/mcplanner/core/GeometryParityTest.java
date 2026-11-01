package dev.lab43.mcplanner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

import static dev.lab43.mcplanner.core.TestMain.check;
import static dev.lab43.mcplanner.core.TestMain.eq;

/** Java geometry vs. the web's TypeScript, on fixtures produced by tools/gen-fixtures.ts. */
final class GeometryParityTest {
    private GeometryParityTest() {}

    static void run() throws Exception {
        JsonObject root = JsonParser.parseString(TestMain.fixture("geometry.json")).getAsJsonObject();
        int shapes = 0;

        for (JsonElement e : root.getAsJsonArray("polygons")) {
            JsonObject c = e.getAsJsonObject();
            String name = "polygon " + c.get("name").getAsString();
            int[][] pts = points(c.getAsJsonArray("points"));
            BlockGrid g = Geometry.polygonGrid(pts);
            compareGrid(name, c, g);
            eq(c.get("blocks7").toString(), toJson(Geometry.gridBlocks(g, 7)), name + " gridBlocks(7)");
            eqBits(c.get("area").getAsDouble(), Geometry.polygonArea(pts), name + " polygonArea");
            eqBits(c.get("length").getAsDouble(), Geometry.polylineLength(pts), name + " polylineLength");
            if (c.get("pip").isJsonPrimitive()) eq(c.get("pip").getAsString(), pip(pts), name + " pointInPolygon samples");
            shapes++;
        }

        for (JsonElement e : root.getAsJsonArray("paths")) {
            JsonObject c = e.getAsJsonObject();
            String name = "path " + c.get("name").getAsString();
            int[][] pts = points(c.getAsJsonArray("points"));
            BlockGrid g = Geometry.pathGrid(pts, c.get("width").getAsDouble());
            compareGrid(name, c, g);
            eqBits(c.get("length").getAsDouble(), Geometry.polylineLength(pts), name + " polylineLength");
            shapes++;
        }
        check(shapes >= 90, "fixture has all shapes (" + shapes + ")");

        JsonObject tooLarge = root.getAsJsonObject("tooLarge");
        check(tooLarge.get("polygon").getAsBoolean() && Geometry.polygonGrid(new int[][] {{0, 0}, {5000, 0}, {5000, 5000}, {0, 5000}}) == null, "too large polygon → null");
        check(tooLarge.get("path").getAsBoolean() && Geometry.pathGrid(new int[][] {{0, 0}, {4100, 4100}}, 3) == null, "too large path → null");
        check(Geometry.polygonGrid(new int[0][]) == null && Geometry.pathGrid(new int[0][], 3) == null, "no points → null");

        for (JsonElement e : root.getAsJsonArray("hypot")) {
            JsonObject h = e.getAsJsonObject();
            eqBits(h.get("h").getAsDouble(), Geometry.jsHypot(h.get("a").getAsDouble(), h.get("b").getAsDouble()), "hypot(" + h.get("a") + ", " + h.get("b") + ")");
        }
        for (JsonElement e : root.getAsJsonArray("stacks")) {
            JsonObject s = e.getAsJsonObject();
            eq(s.get("s").getAsString(), Geometry.formatStacks(s.get("n").getAsLong()), "formatStacks(" + s.get("n") + ")");
        }
        for (JsonElement e : root.getAsJsonArray("materialNames")) {
            JsonObject m = e.getAsJsonObject();
            eq(m.get("name").getAsString(), PlanObject.materialName(m.get("id").getAsString()), "materialName(" + m.get("id") + ")");
        }

        // spot checks of the conventions, independent of the fixtures
        BlockGrid r = Geometry.polygonGrid(Geometry.rectCorners(new int[] {0, 0}, new int[] {4, 4}));
        eq(25, r.count, "2-corner rectangle 0..4 is 5 × 5");
        check(r.has(0, 0) && r.has(4, 4) && !r.has(5, 5) && !r.has(-1, 0), "corner blocks are part of the shape");
        eq(16, Geometry.ringCount(r), "ring of 5 × 5");
        eq(4, Geometry.gridOutline(r).size(), "outline of a rectangle = 4 segments");
        check(Geometry.gridHas(r, 2, 2) && !Geometry.gridHas(null, 0, 0), "gridHas");
    }

    /** counts() and describe() of objects of every kind. */
    static void runObjects() throws Exception {
        JsonArray list = JsonParser.parseString(TestMain.fixture("objects.json")).getAsJsonArray();
        check(list.size() > 50, "objects fixture loaded");
        for (JsonElement e : list) {
            JsonObject c = e.getAsJsonObject();
            PlanObject o = PlanParser.parseObject(c.get("object"));
            String name = "object " + c.getAsJsonObject("object").get("id").getAsString() + " (" + o.kind.id() + ")";
            compareCounts(name, c.getAsJsonObject("counts"), o.counts());
            eq(c.get("describe").getAsString(), o.describe(), name + " describe");
        }
    }

    static void compareCounts(String name, JsonObject t, PlanCounts j) {
        String exp = "blocks=" + l(t, "blocks") + " w=" + l(t, "w") + " d=" + l(t, "d") + " length=" + l(t, "length") + " ring=" + l(t, "ring")
            + " walls=" + l(t, "walls") + " floor=" + l(t, "floor") + " storeys=" + l(t, "storeys") + " storeyHeight=" + l(t, "storeyHeight")
            + " roof=" + l(t, "roof") + " volume=" + l(t, "volume") + " shell=" + l(t, "shell")
            + " approx=" + (t.has("approx") && t.get("approx").getAsBoolean());
        String act = "blocks=" + j.blocks() + " w=" + j.w() + " d=" + j.d() + " length=" + j.length() + " ring=" + j.ring()
            + " walls=" + j.walls() + " floor=" + j.floor() + " storeys=" + j.storeys() + " storeyHeight=" + j.storeyHeight()
            + " roof=" + j.roof() + " volume=" + j.volume() + " shell=" + j.shell() + " approx=" + j.approx();
        eq(exp, act, name + " counts");
    }

    private static long l(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsLong() : 0;
    }

    private static void compareGrid(String name, JsonObject c, BlockGrid g) {
        JsonElement eg = c.get("grid");
        if (eg == null || eg.isJsonNull()) {
            check(g == null, name + " grid should be null");
            return;
        }
        if (g == null) {
            TestMain.fail(name + " grid is null");
            return;
        }
        JsonObject t = eg.getAsJsonObject();
        eq(t.get("x0").getAsInt() + "," + t.get("z0").getAsInt() + " " + t.get("w").getAsInt() + "x" + t.get("d").getAsInt() + " count " + t.get("count").getAsInt(),
            g.x0 + "," + g.z0 + " " + g.w + "x" + g.d + " count " + g.count, name + " grid box");
        eq(t.get("cells").getAsString(), g.cellString(), name + " grid cells");
        eq(c.get("ring").getAsInt(), Geometry.ringCount(g), name + " ringCount");
        eq(c.get("outline").toString(), toJson(Geometry.gridOutline(g)), name + " gridOutline");
    }

    static void eqBits(double expected, double actual, String msg) {
        if (Double.doubleToLongBits(expected) == Double.doubleToLongBits(actual)) TestMain.passed++;
        else TestMain.fail(msg + ": expected " + expected + " but was " + actual);
    }

    static int[][] points(JsonArray a) {
        int[][] out = new int[a.size()][];
        for (int i = 0; i < a.size(); i++) {
            JsonArray p = a.get(i).getAsJsonArray();
            out[i] = new int[] {p.get(0).getAsInt(), p.get(1).getAsInt()};
        }
        return out;
    }

    static String toJson(List<int[]> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[');
            int[] a = list.get(i);
            for (int k = 0; k < a.length; k++) {
                if (k > 0) sb.append(',');
                sb.append(a[k]);
            }
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    /** Same sampling as gen-fixtures.ts. */
    static String pip(int[][] pts) {
        Bounds b = Geometry.bounds(pts);
        StringBuilder s = new StringBuilder();
        for (double z = b.z0() - 1.5; z <= b.z1() + 1.5; z += 0.5)
            for (double x = b.x0() - 1.5; x <= b.x1() + 1.5; x += 0.5) s.append(Geometry.pointInPolygon(x, z, pts) ? '1' : '0');
        return s.toString();
    }
}
