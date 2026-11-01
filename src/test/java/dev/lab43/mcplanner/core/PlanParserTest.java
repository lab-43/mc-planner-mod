package dev.lab43.mcplanner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;

import static dev.lab43.mcplanner.core.TestMain.check;
import static dev.lab43.mcplanner.core.TestMain.eq;

final class PlanParserTest {
    private PlanParserTest() {}

    static void run() throws Exception {
        sampleFile();
        legacyExport();
        messyFile();
        serverWrapper();
        garbage();
        helpers();
    }

    /** (a) a realistic plan@2 file: every field equals what the web's parsePlanFile makes of it. */
    static void sampleFile() throws Exception {
        Plan p = PlanParser.parse(TestMain.fixture("plan-sample.mcplan"));
        compareWithTs("sample", p, "plan-sample.expected.json");
        eq("Joni & Robin's Island", p.worldName, "sample world name");
        eq(null, p.rev, "files have no rev");
        eq(9, p.objects().size(), "sample objects");
        eq(new int[] {120, 71, -48}, p.home("minecraft:overworld"), "home overworld");
        eq(new int[] {15, 64, -6}, p.home().get("minecraft:the_nether"), "home nether");
        eq(0xd4a03c, p.kindColors().get(Kind.PATH), "kindColors path from file");
        eq(Kind.AREA.defaultColor(), p.kindColors().get(Kind.AREA), "kindColors area falls back to default");
        eq(8, p.inDimension("minecraft:overworld").size(), "overworld objects");
        eq(1, p.inDimension("minecraft:the_nether").size(), "nether objects");
        eq(0, p.inDimension("minecraft:the_end").size(), "end objects");

        PlanObject barn = p.byId("barn0legacy006");
        eq(2, barn.points.length, "2-corner building keeps its 2 points");
        eq(new int[][] {{121, -31}, {130, -31}, {130, -20}, {121, -20}}, barn.footprint(), "2-corner building footprint = 4 corners");
        eq(new Bounds(121, -31, 130, -20), barn.bounds(), "barn bounds");

        PlanObject tower = p.byId("tower000000007");
        check(tower.schematic != null, "schematic building has its schematic");
        eq("wizard_tower.litematic", tower.schematic.file(), "schematic file");
        eq(new int[] {22, 40, 15}, tower.schematic.size(), "schematic size");
        eq(new int[] {15, 22}, tower.schematic.dims(), "schematic dims after a quarter turn");
        eq(3, tower.schematic.materials().size(), "schematic materials");
        eq("https://www.minecraft-schematics.com/schematic/12345/", tower.sourceUrl, "source url");
        eq("15 × 22 · 40 high · 12,873 blocks (schematic)", tower.describe(), "schematic describe");

        PlanObject road = p.byId("road0main00001");
        eq(Status.IN_PROGRESS, road.status, "status");
        eq("minecraft:gravel", road.material, "material");
        eq(0xe8b04a, road.color, "colour");
        eq("#e8b04a", road.colorHex(), "colour hex");
        eq("101 long · 3 wide · 309 blocks of gravel", road.describe(), "path describe");
        eq("1,200 blocks of farmland · 40 × 30", p.byId("farm0000000003").describe(), "area describe");
        eq("Farm district", p.byId("farm0000000003").groupName, "group name");
        eq("12 × 9 · 108 footprint · 8 high · Stone Bricks", p.byId("house000000005").describe(), "building describe");
        eq("x 10 · y 65 · z -20", p.byId("mark0portal008").describe(), "marker describe");

        // a UTF-8 BOM in front is fine
        eq(9, PlanParser.parse("﻿" + TestMain.fixture("plan-sample.mcplan")).size(), "BOM is skipped");
    }

    /** (a') an older JSON export with `meta` instead of world/settings. */
    static void legacyExport() throws Exception {
        Plan p = PlanParser.parse(TestMain.fixture("plan-legacy.json"));
        compareWithTs("legacy", p, "plan-legacy.expected.json");
        eq("Old export world", p.worldName, "legacy world name from meta");
        eq(new int[] {5, 80, 6}, p.home("minecraft:overworld"), "legacy home from meta");
        eq(0x123456, p.kindColors().get(Kind.BUILDING), "legacy kindColors from meta");
        eq(4, p.byId("old1").footprint().length, "legacy 2-corner building");
    }

    /** Leniency rules on a file full of odd values. */
    static void messyFile() throws Exception {
        Plan p = PlanParser.parse(TestMain.fixture("plan-messy.json"));
        compareWithTs("messy", p, "plan-messy.expected.json");
        eq(null, p.worldName, "messy: no world name");
        check(p.home().isEmpty(), "messy: no home");
        eq(null, p.byId("x1"), "unknown kind skipped");
        eq(null, p.byId("x2"), "missing points skipped");
        eq(null, p.byId("x3"), "non-array points skipped");
        PlanObject noId = p.objects().get(0);
        check(noId.id.matches("[0-9a-z]{14}"), "missing id → new 14-char id (" + noId.id + ")");
        PlanObject m2 = p.byId("m2");
        eq("12345", m2.name, "numeric name → String(name)");
        eq(Kind.MARKER.defaultColor(), m2.color, "bad colour → kind default");
        eq(Status.PLANNED, m2.status, "unknown status → planned");
        eq(64, m2.y, "non-number y → 64");
        eq("7", m2.author, "numeric author → string");
        eq("", m2.notes, "null notes → empty");
        PlanObject p1 = p.byId("p1");
        eq(new int[][] {{0, 1}, {11, 0}, {7, 8}, {-2, 3}}, p1.points, "points rounded like Math.round, bad ones skipped");
        eq(3, p1.width, "path width default 3");
        eq(null, p1.material, "invalid material dropped");
        eq(null, p1.sourceUrl, "javascript: link dropped");
        eq(40, p1.group.length(), "group id cut to 40");
        eq("Group", p1.groupName, "group name default");
        PlanObject a1 = p.byId("a1");
        eq("https://www.planetminecraft.com/project/abc", a1.sourceUrl, "www. link gets https://");
        eq("99", a1.groupName, "numeric group name");
        eq("minecraft:the_end", a1.dimension, "dimension");
        eq("https://example.com/", p.byId("b1").sourceUrl, "link normalised like new URL().href");
        eq(null, p.byId("b1").schematic, "invalid schematic dropped");
        eq(3, p.byId("b1").storey, "storey");
        eq(8, p.byId("b3").height, "building height default 8");
        eq(Geometry.DEFAULT_STOREY, p.byId("b3").storey, "storey default 4");
        SchematicInfo s = p.byId("b2").schematic;
        check(s != null, "valid schematic kept");
        eq(3, s.turns(), "turns -1 → 3");
        check(s.mirror(), "mirror: truthy 1 → true");
        eq(1235, s.blocks(), "blocks rounded");
        eq("sponge", s.format(), "unknown format → sponge");
        eq(List.of(new SchematicInfo.Material("minecraft:stone", 3), new SchematicInfo.Material("minecraft:oak_log", 0)), s.materials(), "materials filtered + rounded");
        eq(null, p.byId("c1").schematic, "schematic only on buildings");
        eq(0, p.byId("e1").points.length, "empty points kept (like the web)");
        eq(null, p.byId("e1").bounds(), "no points → no bounds");
        eq(null, p.byId("e1").grid(), "no points → no grid");
        check(p.byId("huge").counts().approx(), "huge area is approximate");
        eq("≈25,000,000 blocks · 5001 × 5001", p.byId("huge").describe(), "approx area describe");
    }

    /** (b) the server's {room_id, name, role, paused, canEdit, rev, plan} wrapper. */
    static void serverWrapper() throws Exception {
        JsonObject plan = JsonParser.parseString(TestMain.fixture("plan-sample.mcplan")).getAsJsonObject();
        JsonObject w = new JsonObject();
        w.addProperty("room_id", "room123");
        w.addProperty("name", "Server name");
        w.addProperty("role", "editor");
        w.addProperty("paused", false);
        w.addProperty("canEdit", true);
        w.addProperty("rev", 42);
        w.add("plan", plan);
        Plan p = PlanParser.parse(w.toString());
        eq("42", p.rev, "rev from the wrapper (number → string)");
        eq("Joni & Robin's Island", p.worldName, "world name from plan.world.name");
        eq(9, p.size(), "objects unwrapped");

        JsonObject plan2 = plan.deepCopy();
        plan2.remove("world");
        w.add("plan", plan2);
        w.addProperty("rev", "r-7");
        Plan p2 = PlanParser.parse(w);
        eq("Server name", p2.worldName, "world name falls back to the wrapper's name");
        eq("r-7", p2.rev, "string rev");

        Plan none = PlanParser.parse("{\"room_id\":\"r\",\"name\":\"New world\",\"rev\":0,\"plan\":null}");
        eq(0, none.size(), "wrapper with plan: null → empty plan");
        eq("New world", none.worldName, "empty wrapper keeps its name");
        eq("0", none.rev, "empty wrapper keeps its rev");

        PlanResponse r = PlannerClient.planResponse(w.toString(), "etag1", 9, false);
        eq("editor", r.role(), "wrapper role");
        check(r.canEdit() && !r.paused(), "wrapper flags");
        eq("Server name", r.name(), "wrapper name");
        eq(9, r.plan().size(), "wrapper plan");
    }

    /** (c) things that aren't plans. */
    static void garbage() {
        String notPlan = "This is not an MC Planner plan file.";
        for (String s : new String[] {"", "hello", "{{{", "[]", "{}", "{\"objects\": 5}", "null", "42", "{\"plan\": 5}", "<html>nope</html>"}) {
            PlanFormatException e = TestMain.expectThrows(PlanFormatException.class, () -> PlanParser.parse(s), "garbage " + s);
            if (e != null) eq(notPlan, e.getMessage(), "message for " + s);
        }
        PlanFormatException e = TestMain.expectThrows(PlanFormatException.class,
            () -> PlanParser.parse("{\"format\":\"other/thing\",\"objects\":[]}"), "foreign format");
        if (e != null) eq("Unknown file format “other/thing”.", e.getMessage(), "foreign format message");
        TestMain.expectThrows(PlanFormatException.class, () -> PlanParser.parse((String) null), "null text");
        eq(0, PlanParser.parse("{\"objects\":[]}").size(), "minimal plan");
    }

    static void helpers() {
        check(PlanParser.newId().matches("[0-9a-z]{14}"), "newId format");
        check(!PlanParser.newId().equals(PlanParser.newId()), "newId random");
        eq(null, PlanParser.normalizeSourceUrl("ftp://x.y/z"), "ftp link refused");
        eq(null, PlanParser.normalizeSourceUrl("https://a b.com/"), "whitespace refused");
        eq(null, PlanParser.normalizeSourceUrl("https://"), "no host refused");
        eq(null, PlanParser.normalizeSourceUrl("   "), "blank refused");
        eq("https://www.example.com/x?y=1", PlanParser.normalizeSourceUrl(" www.example.com/x?y=1 "), "www + trim");
        eq("http://example.com/?q", PlanParser.normalizeSourceUrl("HTTP://EXAMPLE.com?q"), "empty path → /");
        check(PlanParser.isMaterialId("minecraft:stone") && !PlanParser.isMaterialId("Minecraft:Stone") && !PlanParser.isMaterialId("stone"), "isMaterialId");
        eq(0xff00aa, PlanParser.parseColor("#FF00aa"), "parseColor");
        eq(-1, PlanParser.parseColor("#ff00aa\n"), "parseColor needs the whole string");
        check(PlanParser.parseObject(JsonParser.parseString("{\"kind\":\"marker\",\"points\":[[1,2]]}")) != null, "parseObject");
        eq(null, PlanParser.parseObject(JsonParser.parseString("{\"kind\":\"marker\"}")), "parseObject skips like the web");
    }

    // ------------------------------------------------------------------------------------------

    /** Compares every field of every object (and world/settings) with the TS output. */
    static void compareWithTs(String label, Plan p, String expectedFixture) throws Exception {
        JsonObject exp = JsonParser.parseString(TestMain.fixture(expectedFixture)).getAsJsonObject();
        JsonArray objs = exp.getAsJsonArray("objects");
        eq(objs.size(), p.objects().size(), label + ": number of objects");
        for (int i = 0; i < Math.min(objs.size(), p.objects().size()); i++) {
            JsonObject t = objs.get(i).getAsJsonObject();
            JsonObject o = t.getAsJsonObject("object");
            PlanObject j = p.objects().get(i);
            String n = label + " object " + i + " (" + o.get("id").getAsString() + ")";
            boolean generatedId = !o.get("id").getAsString().equals(j.id) && j.id.length() == 14;
            if (!generatedId) eq(o.get("id").getAsString(), j.id, n + " id");
            eq(o.get("kind").getAsString(), j.kind.id(), n + " kind");
            eq(o.get("name").getAsString(), j.name, n + " name");
            eq(Integer.parseInt(o.get("color").getAsString().substring(1), 16), j.color, n + " color");
            eq(o.get("status").getAsString(), j.status.id(), n + " status");
            eq(o.get("dimension").getAsString(), j.dimension, n + " dimension");
            eq(o.get("points").toString(), GeometryParityTest.toJson(List.of(j.points)), n + " points");
            eq(Math.round(o.get("y").getAsDouble()), (long) j.y, n + " y");
            eq(Math.round(o.get("width").getAsDouble()), (long) j.width, n + " width");
            eq(Math.round(o.get("height").getAsDouble()), (long) j.height, n + " height");
            eq(o.has("storey") ? o.get("storey").getAsInt() : Geometry.DEFAULT_STOREY, j.storey, n + " storey");
            eq(opt(o, "material"), j.material, n + " material");
            eq(opt(o, "group"), j.group, n + " group");
            eq(opt(o, "groupName"), j.groupName, n + " groupName");
            eq(opt(o, "sourceUrl"), j.sourceUrl, n + " sourceUrl");
            eq(o.get("notes").getAsString(), j.notes, n + " notes");
            eq(o.get("author").getAsString(), j.author, n + " author");
            eq(o.get("createdAt").getAsLong(), j.createdAt, n + " createdAt");
            eq(o.get("updatedAt").getAsLong(), j.updatedAt, n + " updatedAt");
            JsonObject s = o.has("schematic") ? o.getAsJsonObject("schematic") : null;
            eq(s != null, j.schematic != null, n + " has schematic");
            if (s != null && j.schematic != null) {
                SchematicInfo js = j.schematic;
                eq(s.get("hash").getAsString(), js.hash(), n + " schematic hash");
                eq(s.get("file").getAsString(), js.file(), n + " schematic file");
                eq(s.get("format").getAsString(), js.format(), n + " schematic format");
                eq(s.get("size").toString(), "[" + js.size()[0] + "," + js.size()[1] + "," + js.size()[2] + "]", n + " schematic size");
                eq(s.get("turns").getAsInt(), js.turns(), n + " schematic turns");
                eq(s.get("mirror").getAsBoolean(), js.mirror(), n + " schematic mirror");
                eq(s.get("blocks").getAsInt(), js.blocks(), n + " schematic blocks");
                eq(opt(s, "author"), js.author(), n + " schematic author");
                StringBuilder mats = new StringBuilder("[");
                for (int k = 0; k < js.materials().size(); k++) {
                    if (k > 0) mats.append(',');
                    mats.append("[\"").append(js.materials().get(k).id()).append("\",").append(js.materials().get(k).count()).append(']');
                }
                mats.append(']');
                eq(s.has("materials") ? s.get("materials").toString() : "[]", mats.toString(), n + " schematic materials");
            }
            GeometryParityTest.compareCounts(n, t.getAsJsonObject("counts"), j.counts());
            eq(t.get("describe").getAsString(), j.describe(), n + " describe");
        }
        JsonObject world = exp.getAsJsonObject("world");
        eq(opt(world, "name"), p.worldName, label + " world name");
        JsonElement home = exp.getAsJsonObject("settings").get("home");
        if (home != null && home.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : home.getAsJsonObject().entrySet()) {
                JsonObject h = e.getValue().getAsJsonObject();
                eq(new int[] {h.get("x").getAsInt(), h.get("y").getAsInt(), h.get("z").getAsInt()}, p.home(e.getKey()), label + " home " + e.getKey());
            }
        }
        JsonElement colors = exp.getAsJsonObject("settings").get("kindColors");
        if (colors != null && colors.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : colors.getAsJsonObject().entrySet())
                eq(Integer.parseInt(e.getValue().getAsString().substring(1), 16), p.kindColors().get(Kind.parse(e.getKey())), label + " kindColor " + e.getKey());
        }
    }

    private static String opt(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}
