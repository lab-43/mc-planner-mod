package dev.lab43.mcplanner.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static dev.lab43.mcplanner.core.TestMain.check;
import static dev.lab43.mcplanner.core.TestMain.eq;

final class HubClientTest {
    private HubClientTest() {}

    static final String UA = PlannerClient.userAgent("1.2.3", "26.2");

    static void run() throws Exception {
        eq("MCPlanner-Mod/1.2.3 (Minecraft 26.2)", UA, "user agent format");
        try (FakeServer s = new FakeServer()) {
            HubClient hub = new HubClient(s.url() + "/", UA);
            eq(s.url(), hub.hubUrl(), "trailing slash trimmed");

            s.on("POST", "/api/v1/device/code", r -> FakeServer.Reply.json(200,
                "{\"device_code\":\"dev-123\",\"user_code\":\"WDJB-MJHT\",\"verification_uri\":\"https://auth.lab43.dev/device\","
                    + "\"verification_uri_complete\":\"https://auth.lab43.dev/device?code=WDJB-MJHT\",\"expires_in\":900,\"interval\":5}"));
            DeviceCode dc = TestMain.await(hub.startDevice(AuthStore.CLIENT_ID, "Minecraft on test-pc"));
            eq(new DeviceCode("dev-123", "WDJB-MJHT", "https://auth.lab43.dev/device", "https://auth.lab43.dev/device?code=WDJB-MJHT", 900, 5), dc, "device code");
            FakeServer.Req req = s.requests("POST", "/api/v1/device/code").get(0);
            JsonObject body = JsonParser.parseString(req.body()).getAsJsonObject();
            eq("mcplanner-minecraft", body.get("client_id").getAsString(), "client_id sent");
            eq("Minecraft on test-pc", body.get("device_name").getAsString(), "device_name sent");
            eq(UA, req.header("User-Agent"), "User-Agent sent");
            check(req.header("Content-Type").startsWith("application/json"), "JSON content type");

            // minimal answer: defaults for interval / complete uri
            s.on("POST", "/api/v1/device/code", r -> FakeServer.Reply.json(200, "{\"device_code\":\"d\",\"user_code\":\"U\",\"verification_uri\":\"https://x/d\",\"expires_in\":600}"));
            DeviceCode min = TestMain.await(hub.startDevice("c", null));
            eq(5, min.interval(), "default interval 5");
            eq("https://x/d", min.verificationUriComplete(), "complete uri falls back");

            s.on("POST", "/api/v1/device/code", r -> FakeServer.Reply.json(400, "{\"error\":\"invalid_client\"}"));
            ApiException e = ApiException.of(TestMain.awaitError(hub.startDevice("bad", "x"), "unknown client fails"));
            eq(400, e.status(), "status 400");
            eq("invalid_client", e.code(), "OAuth error as code");
            eq("The request was not accepted.", e.getMessage(), "fallback message");

            // token polling: each answer kind
            String[] answer = {""};
            int[] status = {400};
            s.on("POST", "/api/v1/device/token", r -> FakeServer.Reply.json(status[0], answer[0]));
            answer[0] = "{\"error\":\"authorization_pending\"}";
            eq(TokenResult.Kind.PENDING, TestMain.await(hub.pollToken("c", "dev-123")).kind(), "pending");
            JsonObject tb = JsonParser.parseString(s.requests("POST", "/api/v1/device/token").get(0).body()).getAsJsonObject();
            eq("urn:ietf:params:oauth:grant-type:device_code", tb.get("grant_type").getAsString(), "grant type");
            eq("dev-123", tb.get("device_code").getAsString(), "device code sent");
            eq("c", tb.get("client_id").getAsString(), "client id sent");
            answer[0] = "{\"error\":\"slow_down\",\"interval\":10}";
            TokenResult sd = TestMain.await(hub.pollToken("c", "d"));
            eq(TokenResult.Kind.SLOW_DOWN, sd.kind(), "slow_down");
            eq(10, sd.interval(), "slow_down interval");
            answer[0] = "{\"error\":\"access_denied\"}";
            eq(TokenResult.Kind.DENIED, TestMain.await(hub.pollToken("c", "d")).kind(), "denied");
            answer[0] = "{\"error\":\"expired_token\"}";
            eq(TokenResult.Kind.EXPIRED, TestMain.await(hub.pollToken("c", "d")).kind(), "expired");
            answer[0] = "{\"error\":\"invalid_grant\"}";
            eq(TokenResult.Kind.INVALID, TestMain.await(hub.pollToken("c", "d")).kind(), "invalid grant");
            status[0] = 401;
            answer[0] = "{\"error\":\"invalid_client\"}";
            eq(TokenResult.Kind.INVALID, TestMain.await(hub.pollToken("c", "d")).kind(), "401 invalid_client → INVALID");
            status[0] = 200;
            answer[0] = "{\"access_token\":\"tok-abc\",\"token_type\":\"Bearer\",\"scope\":\"worlds:read worlds:write\",\"user\":{\"id\":\"u-1\",\"name\":\"Joni\"}}";
            TokenResult ok = TestMain.await(hub.pollToken("c", "d"));
            eq(new TokenResult(TokenResult.Kind.APPROVED, "tok-abc", "u-1", "Joni", 0), ok, "approved");
            status[0] = 500;
            answer[0] = "<html>oops</html>";
            ApiException se = ApiException.of(TestMain.awaitError(hub.pollToken("c", "d"), "5xx fails the future"));
            check(se.isTransient(), "5xx is transient");
            eq("server_error", se.code(), "5xx code");
            eq("The Lab43 sign-in service has a problem right now. Try again later.", se.getMessage(), "5xx message");
            status[0] = 200;
            answer[0] = "{\"token_type\":\"Bearer\"}";
            eq("bad_response", ApiException.of(TestMain.awaitError(hub.pollToken("c", "d"), "200 without token")).code(), "200 without token = bad response");

            // revoke
            s.on("POST", "/api/v1/device/revoke", r -> FakeServer.Reply.empty(204));
            TestMain.await(hub.revoke("tok-abc"));
            eq("Bearer tok-abc", s.requests("POST", "/api/v1/device/revoke").get(0).header("Authorization"), "revoke sends Bearer");
            s.on("POST", "/api/v1/device/revoke", r -> FakeServer.Reply.json(401, "{\"error\":\"invalid_token\"}"));
            TestMain.await(hub.revoke("old"));
            check(true, "revoke of an invalid token counts as done");
        }

        // nobody listening
        HubClient dead = new HubClient("http://127.0.0.1:1", UA);
        ApiException ne = ApiException.of(TestMain.awaitError(dead.pollToken("c", "d"), "no server"));
        eq(0, ne.status(), "network status 0");
        check(ne.isNetwork() && ne.isTransient(), "network error is transient");
        eq("Could not reach the Lab43 sign-in service. Check your internet connection.", ne.getMessage(), "network message");
        ApiException bad = ApiException.of(TestMain.awaitError(new HubClient("not a url", UA).startDevice("c", "d"), "bad url"));
        eq("network", bad.code(), "invalid hub url fails the future (no throw)");
    }
}
