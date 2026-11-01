package dev.lab43.mcplanner.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static dev.lab43.mcplanner.core.TestMain.check;
import static dev.lab43.mcplanner.core.TestMain.eq;

final class LocalFilesTest {
    private LocalFilesTest() {}

    static void run() throws Exception {
        planCache();
        localPlans();
        fileWatch();
    }

    static String wrapper(String rev) throws Exception {
        return "{\"room_id\":\"r1\",\"name\":\"Server World\",\"role\":\"viewer\",\"paused\":true,\"canEdit\":false,\"rev\":\"" + rev
            + "\",\"plan\":" + TestMain.fixture("plan-sample.mcplan") + "}";
    }

    static void planCache() throws Exception {
        Path game = TestMain.tempDir("cache");
        try {
            PlanCache cache = new PlanCache(game);
            eq(game.resolve("mcplanner").resolve("cache"), cache.dir(), "cache folder");
            eq(null, cache.load("r1"), "nothing cached");
            check(cache.save("r1", wrapper("5"), "etag-5"), "save");
            check(Files.exists(game.resolve("mcplanner/cache/r1.json")), "cache file");
            PlanCache.Cached c = cache.load("r1");
            check(c != null, "load");
            if (c != null) {
                eq("etag-5", c.etag(), "cached etag");
                eq(9, c.plan().size(), "cached plan");
                eq("5", c.plan().rev, "cached rev");
                check(c.response().fromCache(), "marked fromCache");
                eq("viewer", c.response().role(), "cached role");
                check(c.response().paused() && !c.response().canEdit(), "cached flags");
                check(c.savedAt() > 0, "savedAt");
                check(c.raw().contains("\"room_id\""), "raw JSON kept");
            }
            check(cache.save("weird/../id with spaces", wrapper("1"), null), "odd room id");
            check(PlanCache.safeName("weird/../id with spaces").startsWith("h-"), "odd ids are hashed");
            check(cache.load("weird/../id with spaces") != null, "odd id loads");
            Files.write(cache.fileFor("bad"), "{nope".getBytes(StandardCharsets.UTF_8));
            eq(null, cache.load("bad"), "corrupt cache → null");
            cache.delete("r1");
            eq(null, cache.load("r1"), "deleted");
            check(!cache.save("x", "not json at all {", "e"), "invalid raw JSON isn't cached");
        } finally {
            TestMain.deleteTree(game);
        }
    }

    static void localPlans() throws Exception {
        String oldHome = System.getProperty("user.home");
        Path home = TestMain.tempDir("lphome");
        Path game = TestMain.tempDir("lpgame");
        try {
            System.setProperty("user.home", home.toString());
            check(LocalPlans.defaultFolders(game).isEmpty(), "no folders yet");
            Path gamePlans = LocalPlans.gamePlansFolder(game);
            Files.createDirectories(gamePlans);
            eq(List.of(gamePlans), LocalPlans.defaultFolders(game), "only existing folders");
            Path docs = home.resolve("Documents").resolve("MC Planner");
            Files.createDirectories(docs.resolve("old"));
            eq(List.of(docs, gamePlans), LocalPlans.defaultFolders(game), "documents first");

            String sample = TestMain.fixture("plan-sample.mcplan");
            Path a = write(docs.resolve("Island.mcplan"), sample, 3000);
            Path b = write(gamePlans.resolve("export.json"), TestMain.fixture("plan-legacy.json"), 1000);
            Path c = write(docs.resolve("old").resolve("Older.MCPLAN"), sample, 500);
            write(docs.resolve("notes.txt"), "hello", 4000);
            write(docs.resolve(".hidden.mcplan"), sample, 4000);
            List<LocalPlanFile> found = LocalPlans.scan(LocalPlans.defaultFolders(game));
            eq(3, found.size(), "found plan files (not .txt, not hidden)");
            if (found.size() == 3) {
                eq(a.toAbsolutePath().normalize(), found.get(0).path(), "newest first");
                eq("Island", found.get(0).displayName(), "display name without extension");
                eq(b.toAbsolutePath().normalize(), found.get(1).path(), "second");
                eq(c.toAbsolutePath().normalize(), found.get(2).path(), "subfolder file last");
                eq("Older", found.get(2).displayName(), "upper-case extension");
                eq(Files.size(a), found.get(0).size(), "size");
                eq(3000L * 1000, found.get(0).modified(), "modified");
            }
            check(LocalPlans.scan(List.of(home.resolve("missing"))).isEmpty(), "missing folder is skipped");

            eq(9, LocalPlans.load(a).size(), "load .mcplan");
            eq("Old export world", LocalPlans.load(b).worldName, "load legacy .json");
            Path junk = write(gamePlans.resolve("junk.json"), "{\"some\":\"other json\"}", 10);
            TestMain.expectThrows(PlanFormatException.class, () -> LocalPlans.load(junk), "non-plan json");
            TestMain.expectThrows(java.io.IOException.class, () -> LocalPlans.load(gamePlans.resolve("missing.mcplan")), "missing file");
        } finally {
            System.setProperty("user.home", oldHome);
            TestMain.deleteTree(home);
            TestMain.deleteTree(game);
        }
    }

    static void fileWatch() throws Exception {
        Path dir = TestMain.tempDir("watch");
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watch-test");
            t.setDaemon(true);
            return t;
        });
        try {
            Path f = write(dir.resolve("w.mcplan"), "{\"objects\":[]}", 1000);
            AtomicLong now = new AtomicLong(10_000);
            LocalPlans.FileWatch w = new LocalPlans.FileWatch(f, now::get);
            check(!w.check(), "unchanged");
            write(f, "{\"objects\":[ ]}", 2000);
            check(!w.check(), "change seen, not confirmed yet");
            check(w.hasPending(), "pending");
            now.addAndGet(499);
            check(!w.check(), "still within 500 ms");
            now.addAndGet(1);
            check(w.check(), "stable for 500 ms → changed");
            check(!w.check(), "reported once");
            write(f, "{\"objects\":[  ]}", 3000);
            check(!w.check(), "second change pending");
            now.addAndGet(200);
            write(f, "{\"objects\":[   ]}", 3100); // still being written
            check(!w.check(), "changed again → debounce restarts");
            now.addAndGet(400);
            check(!w.check(), "400 ms after the last change");
            now.addAndGet(100);
            check(w.check(), "500 ms after the last change");
            Files.delete(f);
            now.addAndGet(1000);
            check(!w.check(), "missing file (mid-replace) is not a change");
            write(f, "{\"objects\":[   ]}", 3100);
            check(!w.check(), "same file back = no change");
            w.reset();

            // real scheduler: 2 s polling + 500 ms confirm
            LocalPlans.FileWatch live = new LocalPlans.FileWatch(f);
            AtomicInteger changes = new AtomicInteger();
            live.start(exec, changes::incrementAndGet);
            write(f, "{\"objects\":[],\"x\":1}", 5000);
            check(TestMain.waitFor(() -> changes.get() == 1, 8000), "start(): change reported after polling + debounce");
            Thread.sleep(300);
            eq(1, changes.get(), "reported once");
            live.stop();
        } finally {
            exec.shutdownNow();
            TestMain.deleteTree(dir);
        }
    }

    static Path write(Path p, String text, long mtimeSeconds) throws Exception {
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtimeSeconds * 1000));
        return p;
    }
}
