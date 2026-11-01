package dev.lab43.mcplanner.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static dev.lab43.mcplanner.core.TestMain.check;
import static dev.lab43.mcplanner.core.TestMain.eq;

final class ModConfigTest {
    private ModConfigTest() {}

    static void run() throws Exception {
        Path dir = TestMain.tempDir("config");
        try {
            Path file = dir.resolve("config").resolve("mcplanner.json");

            ModConfig d = ModConfig.load(file);
            check(d.overlay && d.labels && !d.labelsThroughWalls && !d.outlinesThroughWalls, "defaults: overlay/labels");
            check(d.showPaths && d.showAreas && d.showBuildings && d.showMarkers, "defaults: kinds shown");
            check(d.showIdea && d.showPlanned && d.showInProgress && !d.showDone, "defaults: done hidden");
            check(d.pathTiles && !d.areaFill && d.hud, "defaults: tiles/fill/hud");
            eq("top_left", d.hudCorner, "default hud corner");
            eq(160, d.renderDistance, "default render distance");
            eq(96, d.labelDistance, "default label distance");
            eq(0.25f, d.fillOpacity, "default fill opacity");
            eq(0.9f, d.lineOpacity, "default line opacity");
            eq("https://auth.lab43.dev", d.hubUrl, "default hub");
            eq("https://app.mc-planner.com", d.apiUrl, "default api");
            check(d.worldByContext.isEmpty(), "default world links");
            check(!Files.exists(file), "load doesn't create the file");

            // round trip
            d.showDone = true;
            d.labels = false;
            d.hudCorner = "bottom_right";
            d.renderDistance = 300;
            d.fillOpacity = 0.5f;
            d.apiUrl = "http://localhost:8787";
            d.setWorldFor(ModConfig.singleplayerContext("My World"), ModConfig.roomRef("abc123"));
            d.setWorldFor(ModConfig.serverContext(" Play.Example.com "), ModConfig.fileRef(dir.resolve("x.mcplan")));
            d.saveOrThrow(file);
            check(Files.exists(file), "saved");
            try (Stream<Path> s = Files.list(file.getParent())) {
                eq(1L, s.count(), "atomic save leaves no temp files");
            }
            ModConfig r = ModConfig.load(file);
            check(r.showDone && !r.labels, "round trip booleans");
            eq("bottom_right", r.hudCorner, "round trip hud corner");
            eq(300, r.renderDistance, "round trip render distance");
            eq(0.5f, r.fillOpacity, "round trip opacity");
            eq("http://localhost:8787", r.apiUrl, "round trip api url");
            eq("room:abc123", r.worldFor("sp:My World"), "round trip world link");
            eq("abc123", ModConfig.refRoomId(r.worldFor("sp:My World")), "refRoomId");
            eq(dir.resolve("x.mcplan").toAbsolutePath().normalize(), ModConfig.refFile(r.worldFor("mp:play.example.com")), "refFile");
            eq(null, ModConfig.refRoomId("file:/x"), "refRoomId of a file ref");
            r.setWorldFor("sp:My World", null);
            eq(null, r.worldFor("sp:My World"), "unlink");

            // visibility filter
            PlanObject path = PlanObject.builder("p", Kind.PATH).points(new int[][] {{0, 0}}).build();
            ModConfig v = new ModConfig();
            check(v.isVisible(path), "planned path visible");
            check(!v.isVisible(path.withStatus(Status.DONE)), "done hidden by default");
            v.showPaths = false;
            check(!v.isVisible(path), "paths off");
            v.showPaths = true;
            v.showPlanned = false;
            check(!v.isVisible(path), "planned off");
            check(v.isVisible(path.withStatus(Status.IDEA)), "idea still on");
            check(v.isVisible(PlanObject.builder("m", Kind.MARKER).status(Status.IN_PROGRESS).build()), "marker in progress");

            // hand-edited values are repaired
            Files.write(file, "{\"hudCorner\":\"middle\",\"renderDistance\":99999,\"fillOpacity\":7,\"worldByContext\":null,\"apiUrl\":\"ftp://x\"}".getBytes(StandardCharsets.UTF_8));
            ModConfig fixed = ModConfig.load(file);
            eq("top_left", fixed.hudCorner, "bad hud corner → default");
            eq(1024, fixed.renderDistance, "render distance clamped");
            eq(1.0f, fixed.fillOpacity, "opacity clamped");
            check(fixed.worldByContext != null, "null map → empty");
            eq("https://app.mc-planner.com", fixed.apiUrl, "bad api url → default");
            check(fixed.overlay, "missing fields keep defaults");

            // corrupt file → defaults + .bak
            String broken = "{ \"overlay\": false, this is not json";
            Files.write(file, broken.getBytes(StandardCharsets.UTF_8));
            ModConfig c = ModConfig.load(file);
            check(c.overlay, "corrupt → defaults");
            Path bak = file.resolveSibling("mcplanner.json.bak");
            check(Files.exists(bak), "corrupt file kept as .bak");
            eq(broken, Files.readString(bak), ".bak has the old content");
            Files.write(file, "{\"overlay\": false, \"renderDistance\": \"far\"}".getBytes(StandardCharsets.UTF_8));
            check(ModConfig.load(file).overlay, "wrong type → defaults");
            Files.write(file, "[1, 2]".getBytes(StandardCharsets.UTF_8));
            check(ModConfig.load(file).overlay, "not an object → defaults");
            Files.write(file, new byte[0]);
            check(ModConfig.load(file).overlay, "empty file → defaults");

            // save() never throws
            Path blocked = dir.resolve("blocked");
            Files.write(blocked, new byte[] {1});
            new ModConfig().save(blocked.resolve("sub").resolve("x.json"));
            check(true, "save() swallows IO errors");
        } finally {
            TestMain.deleteTree(dir);
        }
    }
}
