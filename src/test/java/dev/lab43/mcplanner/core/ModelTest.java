package dev.lab43.mcplanner.core;

import java.util.List;
import java.util.Map;

import static dev.lab43.mcplanner.core.TestMain.check;
import static dev.lab43.mcplanner.core.TestMain.eq;

final class ModelTest {
    private ModelTest() {}

    static void run() {
        // Status
        eq(Status.PLANNED, Status.IDEA.next(), "idea → planned");
        eq(Status.IN_PROGRESS, Status.PLANNED.next(), "planned → in_progress");
        eq(Status.DONE, Status.IN_PROGRESS.next(), "in_progress → done");
        eq(Status.IDEA, Status.DONE.next(), "done → idea");
        eq(Status.IN_PROGRESS, Status.parse("in_progress"), "Status.parse");
        eq(null, Status.parse("In progress"), "Status.parse unknown");
        eq(null, Status.parse(null), "Status.parse null");
        eq("In progress", Status.IN_PROGRESS.label(), "Status label");
        eq("in_progress", Status.IN_PROGRESS.id(), "Status id");
        eq("Idea", Status.IDEA.label(), "Idea label");

        // Kind
        eq(Kind.BUILDING, Kind.parse("building"), "Kind.parse");
        eq(null, Kind.parse("house"), "Kind.parse unknown");
        eq("Path", Kind.PATH.label(), "Kind label");
        eq("marker", Kind.MARKER.id(), "Kind id");
        eq(0xe8b04a, Kind.PATH.defaultColor(), "Kind default colour");
        eq(3, Kind.PATH.defaultWidth(), "path default width");
        eq(8, Kind.BUILDING.defaultHeight(), "building default height");

        // PlanObject
        PlanObject m = PlanObject.builder("m1", Kind.MARKER).points(new int[][] {{10, -20}}).y(64).build();
        eq("Marker", m.name, "builder default name");
        eq(Status.PLANNED, m.status, "builder default status");
        eq("x 10 · y 64 · z -20", m.describe(), "marker describe");
        PlanObject done = m.withStatus(Status.DONE);
        eq(Status.DONE, done.status, "withStatus");
        eq(Status.PLANNED, m.status, "withStatus leaves the original alone");
        check(done.updatedAt >= m.updatedAt, "withStatus bumps updatedAt");
        eq(m.id, done.id, "withStatus keeps id");
        eq(null, m.grid(), "markers have no grid");

        PlanObject path = PlanObject.builder("p", Kind.PATH).points(new int[][] {{0, 0}, {120, 0}}).material("minecraft:gravel").build();
        BlockGrid g = path.grid();
        check(g != null && g.count == path.counts().blocks(), "path grid = counted blocks");
        check(path.grid() == g, "grid cached");
        eq("120 long · 3 wide · 363 blocks of gravel", path.describe(), "path describe");

        PlanObject area = PlanObject.builder("a", Kind.AREA).points(new int[][] {{0, 0}, {39, 0}, {39, 29}, {0, 29}}).build();
        eq("1,200 blocks · 40 × 30", area.describe(), "area describe");
        Bounds b = area.bounds();
        eq(19.5, b.cx(), "bounds cx");
        eq(14.5, b.cz(), "bounds cz");
        eq(40, b.width(), "bounds width");

        PlanObject house = PlanObject.builder("h", Kind.BUILDING).points(new int[][] {{0, 0}, {11, 8}}).material("minecraft:stone_bricks").build();
        eq(4, house.footprint().length, "2-corner building footprint");
        eq("12 × 9 · 108 footprint · 8 high · Stone Bricks", house.describe(), "building describe");
        PlanCounts c = house.counts();
        eq(38L, c.ring(), "ring");
        eq(2, c.storeys(), "storeys");
        eq(482L, c.shell(), "shell");

        SchematicInfo s = new SchematicInfo("a".repeat(64), "x.litematic", new int[] {12, 9, 30}, 1, false, 1234567, "litematic", null, List.of());
        PlanObject sb = house.toBuilder().schematic(s).build();
        eq("30 × 12 · 9 high · 1,234,567 blocks (schematic)", sb.describe(), "schematic building describe");

        // material names
        eq("Dirt Path", PlanObject.materialName("minecraft:dirt_path"), "materialName");
        eq("Andesite Casing (create)", PlanObject.materialName("create:andesite_casing"), "materialName modded");
        eq("Stone Bricks", PlanObject.materialName("stone_bricks"), "materialName without namespace");

        // Plan
        Plan p = new Plan("W", List.of(m, path), Map.of("minecraft:overworld", new int[] {1, 2, 3}), Map.of(Kind.PATH, 0x123456), "r1");
        eq(2, p.size(), "plan size");
        eq(path, p.byId("p"), "byId");
        eq(null, p.byId("nope"), "byId missing");
        Plan p2 = p.withObject(done);
        eq(Status.DONE, p2.byId("m1").status, "withObject replaces");
        eq(0, p2.objects().indexOf(p2.byId("m1")), "withObject keeps the position");
        eq(Status.PLANNED, p.byId("m1").status, "withObject leaves the original alone");
        Plan p3 = p.withObject(area);
        eq(3, p3.size(), "withObject adds new ids");
        eq("r1", p3.rev, "withObject keeps rev");
        eq("W", p3.worldName, "withObject keeps world name");
        eq(0x123456, p3.kindColors().get(Kind.PATH), "kind colour from the plan");
        eq(Kind.MARKER.defaultColor(), p3.kindColors().get(Kind.MARKER), "kind colour default");
        eq(4, p3.kindColors().size(), "all kinds have a colour");
        eq(3, p3.inDimension("minecraft:overworld").size(), "inDimension");
        eq("r9", p.withRev("r9").rev, "withRev");
        check(Plan.empty().isEmpty() && Plan.empty().worldName == null && Plan.empty().rev == null, "Plan.empty");
        boolean immutable;
        try {
            p.objects().add(area);
            immutable = false;
        } catch (UnsupportedOperationException e) {
            immutable = true;
        }
        check(immutable, "objects() is unmodifiable");
        eq("13 stacks + 38", Geometry.formatStacks(870), "formatStacks");
    }
}
