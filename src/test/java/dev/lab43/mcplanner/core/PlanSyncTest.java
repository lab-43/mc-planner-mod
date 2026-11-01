package dev.lab43.mcplanner.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.lab43.mcplanner.core.TestMain.check;
import static dev.lab43.mcplanner.core.TestMain.eq;

final class PlanSyncTest {
    private PlanSyncTest() {}

    /** Records listener calls and the thread they ran on. */
    static final class Events implements PlanSync.Listener {
        final List<String> names = new ArrayList<>();
        final List<String> threads = new ArrayList<>();
        volatile Plan plan;
        volatile PlanResponse meta;
        volatile String message;

        synchronized void add(String n) {
            names.add(n);
            threads.add(Thread.currentThread().getName());
        }

        synchronized int count(String n) {
            int c = 0;
            for (String x : names) if (x.equals(n)) c++;
            return c;
        }

        synchronized List<String> all() {
            return new ArrayList<>(names);
        }

        @Override
        public void onPlan(Plan p, PlanResponse m) {
            plan = p;
            meta = m;
            add("plan");
        }

        @Override
        public void onUnauthorized() {
            add("unauthorized");
        }

        @Override
        public void onLost(String msg) {
            message = msg;
            add("lost");
        }

        @Override
        public void onError(String msg) {
            message = msg;
            add("error");
        }

        @Override
        public void onRecovered() {
            add("recovered");
        }

        @Override
        public void onPaused(String msg) {
            message = msg;
            add("paused");
        }
    }

    static void run() throws Exception {
        ScheduledExecutorService exec = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "sync-bg");
            t.setDaemon(true);
            return t;
        });
        ExecutorService main = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "main");
            t.setDaemon(true);
            return t;
        });
        Path game = TestMain.tempDir("sync");
        try (FakeServer s = new FakeServer()) {
            AtomicReference<String> etag = new AtomicReference<>("e1");
            AtomicReference<String> body = new AtomicReference<>(LocalFilesTest.wrapper("1"));
            AtomicInteger mode = new AtomicInteger(200);
            s.on("GET", "/api/mod/worlds/r1/plan", r -> {
                int m = mode.get();
                if (m == 404) return FakeServer.Reply.json(404, "{\"error\":\"This world was deleted.\"}");
                if (m == 402) return FakeServer.Reply.json(402, "{\"error\":\"This world is paused: its owner's MC Planner plan ended.\",\"code\":\"paused\"}");
                if (m != 200) return FakeServer.Reply.json(m, "{}");
                if (("\"" + etag.get() + "\"").equals(r.header("If-None-Match")))
                    return FakeServer.Reply.empty(304).header("X-Poll-Interval", "5");
                return FakeServer.Reply.json(200, body.get()).header("ETag", "\"" + etag.get() + "\"").header("X-Poll-Interval", "5");
            });
            PlannerClient client = new PlannerClient(s.url(), HubClientTest.UA, () -> "tok");
            Events ev = new Events();
            PlanSync sync = new PlanSync(client, exec, main, ev);
            sync.unitMs = 20; // poll every 5 units = 100 ms
            sync.maxBackoffMs = 300;
            String path = "/api/mod/worlds/r1/plan";

            // first fetch
            sync.select("r1");
            Thread.sleep(80);
            eq(0, s.count("GET", path), "nothing fetched while inactive");
            sync.setActive(true);
            check(TestMain.waitFor(() -> ev.count("plan") == 1, 3000), "first plan delivered");
            eq(9, ev.plan.size(), "plan objects");
            eq("e1", ev.meta.etag(), "etag");
            eq("e1", sync.etag(), "sync etag");
            eq(5, sync.pollSeconds(), "poll interval from the server");
            check(!ev.meta.fromCache(), "from the server");
            eq("main", ev.threads.get(0), "callbacks run on the main thread");
            check(sync.plan() == ev.plan, "plan() is the latest");
            eq("r1", sync.roomId(), "roomId()");

            // 304s keep the plan
            check(TestMain.waitFor(() -> s.count("GET", path) >= 4, 3000), "keeps polling");
            eq(1, ev.count("plan"), "304 → no new plan");
            List<FakeServer.Req> reqs = s.requests("GET", path);
            eq("\"e1\"", reqs.get(reqs.size() - 1).header("If-None-Match"), "polls send If-None-Match");
            long gap = reqs.get(2).at() - reqs.get(1).at();
            check(gap >= 95, "polls are ≥ 5 units apart (" + gap + " ms)");

            // a change arrives
            body.set(LocalFilesTest.wrapper("2"));
            etag.set("e2");
            check(TestMain.waitFor(() -> ev.count("plan") == 2, 3000), "changed plan delivered");
            eq("2", ev.plan.rev, "new rev");

            // errors: reported once, back off, recover
            mode.set(500);
            check(TestMain.waitFor(() -> ev.count("error") == 1, 3000), "error reported");
            int atError = s.count("GET", path);
            check(TestMain.waitFor(() -> s.count("GET", path) >= atError + 2, 3000), "keeps retrying");
            eq(1, ev.count("error"), "same error reported only once");
            check(ev.message != null && ev.message.contains("problem"), "error message for people");
            mode.set(200);
            check(TestMain.waitFor(() -> ev.count("recovered") == 1, 3000), "recovered");
            eq(2, ev.count("plan"), "recovery with an unchanged plan → no new plan");

            // 401 stops polling until refreshNow
            mode.set(401);
            check(TestMain.waitFor(() -> ev.count("unauthorized") == 1, 3000), "onUnauthorized");
            int at401 = s.count("GET", path);
            Thread.sleep(300);
            eq(at401, s.count("GET", path), "no polling after 401");
            mode.set(200);
            sync.refreshNow();
            check(TestMain.waitFor(() -> s.count("GET", path) >= at401 + 2, 3000), "refreshNow resumes polling");
            eq(1, ev.count("unauthorized"), "unauthorized once");

            // optimistic local change
            PlanObject first = ev.plan.objects().get(0);
            Status next = first.status.next();
            int plansBefore = ev.count("plan");
            int reqBefore = s.count("GET", path);
            sync.applyLocal(first.withStatus(next));
            check(TestMain.waitFor(() -> ev.count("plan") == plansBefore + 1, 3000), "applyLocal → onPlan");
            eq(next, ev.plan.byId(first.id).status, "patched object");
            eq(9, ev.plan.size(), "same objects");
            check(TestMain.waitFor(() -> s.count("GET", path) > reqBefore, 3000), "applyLocal refreshes");

            // inactive → no polling
            sync.setActive(false);
            Thread.sleep(60);
            int atInactive = s.count("GET", path);
            Thread.sleep(300);
            eq(atInactive, s.count("GET", path), "no polling while inactive");
            check(!sync.isActive(), "isActive");

            // 404 → lost
            mode.set(404);
            sync.setActive(true);
            check(TestMain.waitFor(() -> ev.count("lost") == 1, 3000), "onLost");
            eq("This world was deleted.", ev.message, "lost message");
            int atLost = s.count("GET", path);
            Thread.sleep(300);
            eq(atLost, s.count("GET", path), "no polling after 404");

            // clear
            mode.set(200);
            sync.clear();
            eq(null, sync.roomId(), "cleared");
            eq(null, sync.plan(), "no plan after clear");
            sync.refreshNow();
            Thread.sleep(200);
            eq(atLost, s.count("GET", path), "nothing fetched after clear");
            sync.setActive(false);

            // offline cache: the cached plan shows first, then 304 with the cached etag
            PlanCache cache = new PlanCache(game);
            cache.save("r1", LocalFilesTest.wrapper("2"), "e2");
            Events ev2 = new Events();
            PlanSync sync2 = new PlanSync(client, exec, main, ev2);
            sync2.unitMs = 20;
            sync2.setCache(cache);
            int beforeCache = s.count("GET", path);
            sync2.setActive(true);
            sync2.select("r1");
            check(TestMain.waitFor(() -> ev2.count("plan") >= 1, 3000), "cached plan delivered");
            check(ev2.meta.fromCache(), "first plan is from the cache");
            eq("2", ev2.plan.rev, "cached rev");
            check(TestMain.waitFor(() -> s.count("GET", path) > beforeCache, 3000), "then fetched");
            eq("\"e2\"", s.requests("GET", path).get(beforeCache).header("If-None-Match"), "cached etag used");
            Thread.sleep(150);
            eq(1, ev2.count("plan"), "304 for the cached etag → no second plan");

            // a fresh plan is written to the cache
            body.set(LocalFilesTest.wrapper("3"));
            etag.set("e3");
            check(TestMain.waitFor(() -> ev2.count("plan") == 2, 3000), "fresh plan after cache");
            check(TestMain.waitFor(() -> {
                PlanCache.Cached c = cache.load("r1");
                return c != null && "e3".equals(c.etag());
            }, 3000), "fresh plan cached");

            // a 304 marks the saved copy as current again (at most once per cacheTouchMs)
            sync2.cacheTouchMs = 0;
            setSavedAt(cache, "r1", System.currentTimeMillis() - 2 * 3600_000L);
            int beforeTouch = s.count("GET", path);
            check(TestMain.waitFor(() -> s.count("GET", path) >= beforeTouch + 2, 3000), "polling on");
            check(TestMain.waitFor(() -> System.currentTimeMillis() - cache.load("r1").savedAt() < 60_000, 3000), "304 → savedAt is now");
            eq(2, ev2.count("plan"), "a touch isn't a new plan");

            // 402: the world is paused (its owner's plan ended) → the plan goes, also from the cache; slow polling
            sync2.pausedPollMs = 400;
            mode.set(402);
            check(TestMain.waitFor(() -> ev2.count("paused") == 1, 3000), "onPaused");
            eq("This world is paused: its owner's MC Planner plan ended.", ev2.message, "the server's message");
            check(sync2.isPaused(), "isPaused");
            eq(null, sync2.plan(), "no plan while paused");
            eq(null, sync2.etag(), "no etag while paused (the next 200 brings the whole plan)");
            check(TestMain.waitFor(() -> cache.load("r1") == null, 3000), "the saved copy is deleted (not shown offline)");
            int atPause = s.count("GET", path);
            Thread.sleep(250);
            eq(atPause, s.count("GET", path), "paused: no poll every 100 ms");
            check(TestMain.waitFor(() -> s.count("GET", path) > atPause, 3000), "…but it's asked again later");
            eq(1, ev2.count("paused"), "onPaused once per pause");
            eq(0, ev2.count("error"), "a pause isn't an error");
            sync2.select("r1"); // the same world again (picked in the list): its state is told again
            check(TestMain.waitFor(() -> ev2.count("paused") == 2, 3000), "re-selecting a paused world tells it again");
            eq("This world is paused: its owner's MC Planner plan ended.", ev2.message, "with the server's message");
            mode.set(200);
            check(TestMain.waitFor(() -> ev2.count("plan") == 3, 3000), "live again: the plan comes back");
            check(!sync2.isPaused(), "not paused any more");
            List<FakeServer.Req> after = s.requests("GET", path);
            eq(null, after.get(after.size() - 1).header("If-None-Match"), "asked without an etag");
            sync2.clear();

            // a saved plan older than the offline grace (72 h) isn't shown
            cache.save("r2", LocalFilesTest.wrapper("5"), "e5");
            setSavedAt(cache, "r2", System.currentTimeMillis() - AccessGate.GRACE_MS - 60_000);
            Events ev3 = new Events();
            PlanSync sync3 = new PlanSync(client, exec, main, ev3);
            sync3.setCache(cache);
            sync3.select("r2");
            Thread.sleep(200);
            eq(0, ev3.count("plan"), "too old: not shown");
            setSavedAt(cache, "r2", System.currentTimeMillis() - AccessGate.GRACE_MS + 3600_000);
            sync3.clear();
            sync3.select("r2");
            check(TestMain.waitFor(() -> ev3.count("plan") == 1, 3000), "71 h old: shown");
            check(ev3.meta.fromCache(), "from the cache");
            sync3.clear();
        } finally {
            exec.shutdownNow();
            main.shutdownNow();
            TestMain.deleteTree(game);
        }
    }

    /** rewrites a cached plan's savedAt */
    static void setSavedAt(PlanCache cache, String room, long at) throws Exception {
        Path f = cache.fileFor(room);
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject();
        o.addProperty("savedAt", at);
        java.nio.file.Files.writeString(f, o.toString());
    }
}
