package dev.lab43.mcplanner.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

import static dev.lab43.mcplanner.core.TestMain.check;
import static dev.lab43.mcplanner.core.TestMain.eq;

final class PlannerClientTest {
    private PlannerClientTest() {}

    static final String OBJECT = "{\"id\":\"obj 1\",\"kind\":\"building\",\"name\":\"House\",\"color\":\"#e85a4a\",\"status\":\"done\","
        + "\"dimension\":\"minecraft:overworld\",\"points\":[[0,0],[4,0],[4,4],[0,4]],\"y\":70,\"width\":0,\"height\":8,"
        + "\"notes\":\"\",\"author\":\"Joni\",\"createdAt\":1,\"updatedAt\":2}";

    static void run() throws Exception {
        String[] token = {"tok-1"};
        try (FakeServer s = new FakeServer()) {
            PlannerClient c = new PlannerClient(s.url() + "/", HubClientTest.UA, () -> token[0]);
            eq(s.url(), c.apiUrl(), "api url trimmed");

            // me
            s.on("GET", "/api/mod/me", r -> FakeServer.Reply.json(200,
                "{\"user\":{\"id\":\"u-1\",\"name\":\"Joni\",\"color\":\"#ff8800\"},\"scopes\":[\"mcplanner:read\",\"mcplanner:write\"]}"));
            Me me = TestMain.await(c.me());
            eq(new Me("u-1", "Joni", 0xff8800, List.of("mcplanner:read", "mcplanner:write")), me, "me");
            check(me.hasScope(Me.SCOPE_READ) && me.canWrite(), "hasScope / canWrite");
            FakeServer.Req req = s.requests("GET", "/api/mod/me").get(0);
            eq("Bearer tok-1", req.header("Authorization"), "Bearer token sent");
            eq(HubClientTest.UA, req.header("User-Agent"), "User-Agent sent");
            s.on("GET", "/api/mod/me", r -> FakeServer.Reply.json(200, "{\"user_id\":\"u-2\",\"name\":\"Flat\",\"scope\":\"a b\"}"));
            Me flat = TestMain.await(c.me());
            eq(new Me("u-2", "Flat", null, List.of("a", "b")), flat, "flat me shape");
            check(!flat.canWrite(), "no write scope");
            // a server from before the mod stopped asking about plans still sends "access": ignored
            s.on("GET", "/api/mod/me", r -> FakeServer.Reply.json(200, "{\"user\":{\"id\":\"u-3\"},\"scopes\":[\"mcplanner:read\"],"
                + "\"access\":{\"active\":false,\"plan\":\"free\",\"name\":\"Free\",\"source\":\"free\",\"expiresAt\":null,\"trialAvailable\":true,\"files\":false}}"));
            eq(new Me("u-3", null, null, List.of("mcplanner:read")), TestMain.await(c.me()), "an old server's access field is ignored");

            // a paused world: 402 with the server's message and code
            s.on("GET", "/api/mod/worlds/r402/plan", r -> FakeServer.Reply.json(402,
                "{\"error\":\"This world is paused: its owner's MC Planner plan ended.\",\"code\":\"paused\",\"detail\":{\"name\":\"Island\",\"role\":\"editor\"}}"));
            ApiException e402 = ApiException.of(TestMain.awaitError(c.plan("r402", null), "402"));
            eq(402, e402.status(), "402 status");
            eq("paused", e402.code(), "paused code");
            eq("This world is paused: its owner's MC Planner plan ended.", e402.getMessage(), "paused message");
            check(!e402.isTransient() && !e402.isUnauthorized(), "a pause is neither transient nor a sign-out");

            // worlds
            s.on("GET", "/api/mod/worlds", r -> FakeServer.Reply.json(200, "{\"worlds\":["
                + "{\"room_id\":\"r1\",\"name\":\"Island\",\"role\":\"owner\",\"paused\":false,\"canEdit\":true,\"ownerName\":\"Joni\",\"members\":3},"
                + "{\"roomId\":\"r2\",\"name\":\"Server\",\"role\":\"viewer\",\"paused\":true,\"can_edit\":false,\"owner\":{\"name\":\"Robin\"},\"members\":[1,2]},"
                + "{\"id\":\"r3\"},"
                + "{\"name\":\"no id\"}]}"));
            List<WorldInfo> ws = TestMain.await(c.worlds());
            eq(3, ws.size(), "worlds without id are skipped");
            eq(new WorldInfo("r1", "Island", "owner", false, true, "Joni", 3), ws.get(0), "world 1");
            eq(new WorldInfo("r2", "Server", "viewer", true, false, "Robin", 2), ws.get(1), "world 2 (other field names)");
            eq(new WorldInfo("r3", "World", null, false, false, null, 0), ws.get(2), "world 3 (missing fields)");
            s.on("GET", "/api/mod/worlds", r -> FakeServer.Reply.json(200, "[{\"room_id\":\"x\",\"name\":\"Bare\"}]"));
            eq("Bare", TestMain.await(c.worlds()).get(0).name(), "bare array");

            // plan: 200 + ETag, then 304
            String body = LocalFilesTest.wrapper("7");
            s.on("GET", "/api/mod/worlds/r1/plan", r -> "\"e1\"".equals(r.header("If-None-Match"))
                ? FakeServer.Reply.empty(304).header("ETag", "\"e1\"").header("X-Poll-Interval", "15")
                : FakeServer.Reply.json(200, body).header("ETag", "W/\"e1\"").header("X-Poll-Interval", "7"));
            PlanResponse pr = TestMain.await(c.plan("r1", null));
            check(!pr.notModified(), "200 is modified");
            eq(9, pr.plan().size(), "plan objects");
            eq("7", pr.plan().rev, "rev");
            eq("e1", pr.etag(), "ETag without W/ and quotes");
            eq(7, pr.pollSeconds(), "X-Poll-Interval");
            eq("viewer", pr.role(), "role");
            check(pr.paused() && !pr.canEdit(), "paused / canEdit");
            eq("Server World", pr.name(), "name");
            eq(body, pr.raw(), "raw body kept");
            check(!pr.fromCache(), "not from cache");
            eq(null, s.requests("GET", "/api/mod/worlds/r1/plan").get(0).header("If-None-Match"), "no If-None-Match without etag");
            PlanResponse nm = TestMain.await(c.plan("r1", "e1"));
            check(nm.notModified(), "304 → notModified");
            eq(null, nm.plan(), "304 has no plan");
            eq("e1", nm.etag(), "304 etag");
            eq(15, nm.pollSeconds(), "304 poll interval");
            eq("\"e1\"", s.requests("GET", "/api/mod/worlds/r1/plan").get(1).header("If-None-Match"), "If-None-Match is quoted");
            s.on("GET", "/api/mod/worlds/r9/plan", r -> FakeServer.Reply.json(200, body));
            eq(PlannerClient.DEFAULT_POLL_SECONDS, TestMain.await(c.plan("r9", null)).pollSeconds(), "default poll interval 10");

            // errors
            s.on("GET", "/api/mod/worlds/r401/plan", r -> FakeServer.Reply.json(401, "{\"error\":\"invalid_token\"}"));
            ApiException e401 = ApiException.of(TestMain.awaitError(c.plan("r401", null), "401"));
            eq(401, e401.status(), "401 status");
            eq("unauthorized", e401.code(), "401 → unauthorized");
            check(e401.isUnauthorized(), "isUnauthorized");
            s.on("GET", "/api/mod/worlds/r403/plan", r -> FakeServer.Reply.json(403, "{\"error\":\"You were removed from this world.\",\"code\":\"not_member\"}"));
            ApiException e403 = ApiException.of(TestMain.awaitError(c.plan("r403", null), "403"));
            eq("not_member", e403.code(), "code from body");
            eq("You were removed from this world.", e403.getMessage(), "sentence from body");
            s.on("GET", "/api/mod/worlds/r404/plan", r -> FakeServer.Reply.empty(404));
            ApiException e404 = ApiException.of(TestMain.awaitError(c.plan("r404", null), "404"));
            eq("not_found", e404.code(), "404 code");
            eq("This world doesn't exist any more (or you were removed from it).", e404.getMessage(), "404 world message");
            s.on("GET", "/api/mod/worlds/r500/plan", r -> FakeServer.Reply.json(503, "{\"error\":\"db_down\"}"));
            ApiException e500 = ApiException.of(TestMain.awaitError(c.plan("r500", null), "503"));
            eq("db_down", e500.code(), "non-sentence error becomes the code");
            eq("MC Planner has a problem right now. Try again later.", e500.getMessage(), "5xx fallback message");
            check(e500.isTransient(), "5xx transient");
            s.on("GET", "/api/mod/worlds/rbad/plan", r -> FakeServer.Reply.json(200, "{\"hello\":1}"));
            eq("bad_response", ApiException.of(TestMain.awaitError(c.plan("rbad", null), "not a plan")).code(), "not a plan → bad_response");

            // writes
            s.on("PATCH", "/api/mod/worlds/r1/objects/obj%201", r -> FakeServer.Reply.json(200, "{\"object\":" + OBJECT + ",\"rev\":8}"));
            PlanObject changed = TestMain.await(c.setStatus("r1", "obj 1", Status.DONE));
            eq(Status.DONE, changed.status, "setStatus → changed object");
            eq("obj 1", changed.id, "object id");
            FakeServer.Req patch = s.requests("PATCH", "/api/mod/worlds/r1/objects/obj%201").get(0);
            eq("{\"status\":\"done\"}", patch.body(), "PATCH body");
            check(patch.header("Content-Type").startsWith("application/json"), "PATCH JSON");

            s.on("POST", "/api/mod/worlds/r1/markers", r -> {
                JsonObject in = JsonParser.parseString(r.body()).getAsJsonObject();
                return FakeServer.Reply.json(201, "{\"object\":{\"id\":\"mk1\",\"kind\":\"marker\",\"name\":" + in.get("name") + ",\"points\":[["
                    + in.get("x") + "," + in.get("z") + "]],\"y\":" + in.get("y") + ",\"dimension\":" + in.get("dimension") + "},\"rev\":9}");
            });
            PlanObject mk = TestMain.await(c.addMarker("r1", MarkerRequest.of("Portal", 10, 65, -20, "minecraft:the_nether")));
            eq(Kind.MARKER, mk.kind, "marker kind");
            eq("Portal", mk.name, "marker name");
            eq("x 10 · y 65 · z -20", mk.describe(), "marker position");
            eq("minecraft:the_nether", mk.dimension, "marker dimension");
            JsonObject sent = JsonParser.parseString(s.requests("POST", "/api/mod/worlds/r1/markers").get(0).body()).getAsJsonObject();
            check(!sent.has("color") && !sent.has("notes"), "null color / notes are left out");
            TestMain.await(c.addMarker("r1", new MarkerRequest("Base", 1, 2, 3, "minecraft:overworld", "#ff0000", "here")));
            JsonObject sent2 = JsonParser.parseString(s.requests("POST", "/api/mod/worlds/r1/markers").get(1).body()).getAsJsonObject();
            eq("#ff0000", sent2.get("color").getAsString(), "color sent");
            eq("here", sent2.get("notes").getAsString(), "notes sent");
            s.on("POST", "/api/mod/worlds/r2/markers", r -> FakeServer.Reply.json(403, "{\"error\":\"This world is paused.\"}"));
            eq("This world is paused.", ApiException.of(TestMain.awaitError(c.addMarker("r2", MarkerRequest.of("x", 0, 0, 0, "minecraft:overworld")), "paused")).getMessage(), "write refused message");

            // logout
            s.on("POST", "/api/mod/logout", r -> FakeServer.Reply.empty(204));
            TestMain.await(c.logout());
            eq(1, s.count("POST", "/api/mod/logout"), "logout sent");

            // no token → fails at once, nothing sent
            token[0] = null;
            int before = s.requests.size();
            ApiException none = ApiException.of(TestMain.awaitError(c.me(), "no token"));
            eq("unauthorized", none.code(), "no token → unauthorized");
            eq(before, s.requests.size(), "no request without a token");
        }

        // nobody listening
        PlannerClient dead = new PlannerClient("http://127.0.0.1:1", HubClientTest.UA, () -> "t");
        ApiException ne = ApiException.of(TestMain.awaitError(dead.worlds(), "no server"));
        check(ne.isNetwork(), "network error");
        eq("Could not reach MC Planner. Check your internet connection.", ne.getMessage(), "network message");
    }
}
