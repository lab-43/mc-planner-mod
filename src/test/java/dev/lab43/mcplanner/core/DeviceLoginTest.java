package dev.lab43.mcplanner.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import static dev.lab43.mcplanner.core.TestMain.check;
import static dev.lab43.mcplanner.core.TestMain.eq;

final class DeviceLoginTest {
    private DeviceLoginTest() {}

    /** 1 "second" of interval = 20 ms in these tests. */
    static final long UNIT = 20;

    static void run() throws Exception {
        ScheduledExecutorService exec = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "device-login-test");
            t.setDaemon(true);
            return t;
        });
        try (FakeServer s = new FakeServer()) {
            HubClient hub = new HubClient(s.url(), HubClientTest.UA);
            approvedAfterSlowDown(s, hub, exec);
            endings(s, hub, exec, "{\"error\":\"access_denied\"}", DeviceLogin.Phase.DENIED, "denied");
            endings(s, hub, exec, "{\"error\":\"expired_token\"}", DeviceLogin.Phase.EXPIRED, "expired by the hub");
            endings(s, hub, exec, "{\"error\":\"invalid_grant\"}", DeviceLogin.Phase.FAILED, "invalid grant");
            localExpiry(s, hub, exec);
            transientErrors(s, hub, exec);
            cancel(s, hub, exec);
        } finally {
            exec.shutdownNow();
        }
    }

    static void codeRoute(FakeServer s, int interval, int expiresIn) {
        s.on("POST", "/api/v1/device/code", r -> FakeServer.Reply.json(200,
            "{\"device_code\":\"dev-1\",\"user_code\":\"ABCD-EFGH\",\"verification_uri\":\"https://hub/device\","
                + "\"verification_uri_complete\":\"https://hub/device?c=ABCD-EFGH\",\"expires_in\":" + expiresIn + ",\"interval\":" + interval + "}"));
    }

    /** Token route answering from a script; the last entry repeats. */
    static void tokenScript(FakeServer s, String... answers) {
        ConcurrentLinkedQueue<String> q = new ConcurrentLinkedQueue<>(List.of(answers));
        String last = answers[answers.length - 1];
        s.on("POST", "/api/v1/device/token", r -> {
            String a = q.poll();
            if (a == null) a = last;
            if (a.startsWith("500")) return FakeServer.Reply.json(500, "{}");
            return FakeServer.Reply.json(a.contains("access_token") ? 200 : 400, a);
        });
    }

    static DeviceLogin login(HubClient hub, ScheduledExecutorService exec, List<DeviceLogin.State> states) {
        DeviceLogin l = new DeviceLogin(hub, AuthStore.CLIENT_ID, "Minecraft test", exec, states::add);
        l.unitMs = UNIT;
        return l;
    }

    static List<DeviceLogin.Phase> phases(List<DeviceLogin.State> states) {
        List<DeviceLogin.Phase> out = new ArrayList<>();
        synchronized (states) {
            for (DeviceLogin.State st : states) out.add(st.phase());
        }
        return out;
    }

    static DeviceLogin.State last(List<DeviceLogin.State> states) {
        synchronized (states) {
            return states.isEmpty() ? null : states.get(states.size() - 1);
        }
    }

    static Supplier<Boolean> isFinal(List<DeviceLogin.State> states) {
        return () -> {
            DeviceLogin.State st = last(states);
            return st != null && st.isFinal();
        };
    }

    static void approvedAfterSlowDown(FakeServer s, HubClient hub, ScheduledExecutorService exec) throws Exception {
        s.requests.clear();
        codeRoute(s, 1, 60);
        tokenScript(s, "{\"error\":\"authorization_pending\"}", "{\"error\":\"slow_down\"}", "{\"error\":\"authorization_pending\"}",
            "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"scope\":\"worlds\",\"user\":{\"id\":\"u1\",\"name\":\"Joni\"}}");
        List<DeviceLogin.State> states = Collections.synchronizedList(new ArrayList<>());
        DeviceLogin l = login(hub, exec, states);
        l.start();
        check(TestMain.waitFor(() -> isFinal(states).get(), 5000), "flow finishes");
        eq(List.of(DeviceLogin.Phase.STARTING, DeviceLogin.Phase.WAITING, DeviceLogin.Phase.APPROVED), phases(states), "states");
        DeviceLogin.State waiting = states.get(1);
        eq("ABCD-EFGH", waiting.userCode(), "user code shown");
        eq("https://hub/device", waiting.verificationUri(), "verification uri");
        eq("https://hub/device?c=ABCD-EFGH", waiting.verificationUriComplete(), "complete uri");
        check(waiting.expiresAt() > System.currentTimeMillis(), "expiresAt in the future");
        DeviceLogin.State done = last(states);
        eq("tok-1", done.token(), "token");
        eq("u1", done.userId(), "user id");
        eq("Joni", done.userName(), "user name");
        check(!l.isRunning(), "not running after approval");
        eq(6L, l.intervalSeconds(), "slow_down added 5 s to the interval");

        // timing: never faster than the interval; after slow_down 6 units
        List<FakeServer.Req> polls = s.requests("POST", "/api/v1/device/token");
        long codeAt = s.requests("POST", "/api/v1/device/code").get(0).at();
        eq(4, polls.size(), "4 polls");
        if (polls.size() == 4) {
            check(polls.get(0).at() - codeAt >= UNIT - 2, "first poll waits one interval");
            check(polls.get(1).at() - polls.get(0).at() >= UNIT - 2, "pending → wait one interval");
            check(polls.get(2).at() - polls.get(1).at() >= 6 * UNIT - 2, "slow_down → wait 6 intervals (" + (polls.get(2).at() - polls.get(1).at()) + " ms)");
            check(polls.get(3).at() - polls.get(2).at() >= 6 * UNIT - 2, "slower interval sticks");
        }
        Thread.sleep(150);
        eq(4, s.count("POST", "/api/v1/device/token"), "no polls after approval");
    }

    static void endings(FakeServer s, HubClient hub, ScheduledExecutorService exec, String answer, DeviceLogin.Phase want, String label) throws Exception {
        s.requests.clear();
        codeRoute(s, 1, 60);
        tokenScript(s, "{\"error\":\"authorization_pending\"}", answer);
        List<DeviceLogin.State> states = Collections.synchronizedList(new ArrayList<>());
        DeviceLogin l = login(hub, exec, states);
        l.start();
        check(TestMain.waitFor(() -> isFinal(states).get(), 5000), label + ": finishes");
        eq(want, last(states).phase(), label + ": final state");
        check(last(states).message() != null && !last(states).message().isEmpty(), label + ": has a message");
        int n = s.count("POST", "/api/v1/device/token");
        Thread.sleep(100);
        eq(n, s.count("POST", "/api/v1/device/token"), label + ": polling stopped");
        eq(want, l.state().phase(), label + ": state()");
    }

    static void localExpiry(FakeServer s, HubClient hub, ScheduledExecutorService exec) throws Exception {
        s.requests.clear();
        codeRoute(s, 1, 5); // expires after 5 units = 100 ms
        tokenScript(s, "{\"error\":\"authorization_pending\"}");
        List<DeviceLogin.State> states = Collections.synchronizedList(new ArrayList<>());
        DeviceLogin l = login(hub, exec, states);
        l.start();
        check(TestMain.waitFor(() -> isFinal(states).get(), 5000), "local expiry finishes");
        eq(DeviceLogin.Phase.EXPIRED, last(states).phase(), "stops at expiry without the hub saying so");
        check(s.count("POST", "/api/v1/device/token") <= 6, "polled at most expires_in / interval times");
    }

    static void transientErrors(FakeServer s, HubClient hub, ScheduledExecutorService exec) throws Exception {
        s.requests.clear();
        codeRoute(s, 1, 600);
        tokenScript(s, "500", "500", "500",
            "{\"access_token\":\"tok-2\",\"user\":{\"id\":\"u2\",\"name\":\"Robin\"}}");
        List<DeviceLogin.State> states = Collections.synchronizedList(new ArrayList<>());
        DeviceLogin l = login(hub, exec, states);
        l.start();
        check(TestMain.waitFor(() -> isFinal(states).get(), 5000), "3 transient errors: finishes");
        eq(DeviceLogin.Phase.APPROVED, last(states).phase(), "3 transient errors are retried");
        List<FakeServer.Req> polls = s.requests("POST", "/api/v1/device/token");
        if (polls.size() == 4) {
            check(polls.get(1).at() - polls.get(0).at() >= 2 * UNIT - 2, "backoff ×2");
            check(polls.get(2).at() - polls.get(1).at() >= 4 * UNIT - 2, "backoff ×4");
            check(polls.get(3).at() - polls.get(2).at() >= 8 * UNIT - 2, "backoff ×8");
        } else TestMain.fail("expected 4 polls, got " + polls.size());

        s.requests.clear();
        tokenScript(s, "500");
        List<DeviceLogin.State> states2 = Collections.synchronizedList(new ArrayList<>());
        DeviceLogin l2 = login(hub, exec, states2);
        l2.start();
        check(TestMain.waitFor(() -> isFinal(states2).get(), 5000), "4 transient errors: finishes");
        eq(DeviceLogin.Phase.FAILED, last(states2).phase(), "gives up after 3 retries");
        eq(4, s.count("POST", "/api/v1/device/token"), "1 try + 3 retries");

        // the code request itself is retried too, and a hard error fails
        s.requests.clear();
        s.on("POST", "/api/v1/device/code", r -> FakeServer.Reply.json(403, "{\"error\":\"This app is not allowed to sign in.\"}"));
        List<DeviceLogin.State> states3 = Collections.synchronizedList(new ArrayList<>());
        DeviceLogin l3 = login(hub, exec, states3);
        l3.start();
        check(TestMain.waitFor(() -> isFinal(states3).get(), 5000), "code refused: finishes");
        eq(DeviceLogin.Phase.FAILED, last(states3).phase(), "code refused → FAILED");
        eq("This app is not allowed to sign in.", last(states3).message(), "server sentence is the message");
        eq(1, s.count("POST", "/api/v1/device/code"), "4xx is not retried");
    }

    static void cancel(FakeServer s, HubClient hub, ScheduledExecutorService exec) throws Exception {
        s.requests.clear();
        codeRoute(s, 1, 600);
        tokenScript(s, "{\"error\":\"authorization_pending\"}");
        List<DeviceLogin.State> states = Collections.synchronizedList(new ArrayList<>());
        DeviceLogin l = login(hub, exec, states);
        l.start();
        check(TestMain.waitFor(() -> s.count("POST", "/api/v1/device/token") >= 2, 3000), "polling");
        l.cancel();
        Thread.sleep(60);
        int n = s.count("POST", "/api/v1/device/token");
        int st = states.size();
        Thread.sleep(150);
        eq(n, s.count("POST", "/api/v1/device/token"), "no polls after cancel");
        eq(st, states.size(), "no states after cancel");
        check(!l.isRunning(), "not running");

        // restart works
        tokenScript(s, "{\"access_token\":\"tok-3\",\"user\":{\"id\":\"u3\",\"name\":\"X\"}}");
        l.start();
        check(TestMain.waitFor(() -> isFinal(states).get(), 3000), "restart finishes");
        eq("tok-3", last(states).token(), "restart approved");
    }
}
