package dev.lab43.mcplanner.core;

import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.lab43.mcplanner.core.AccessGate.State.ALLOWED;
import static dev.lab43.mcplanner.core.AccessGate.State.CHECKING;
import static dev.lab43.mcplanner.core.AccessGate.State.CHECK_AGAIN;
import static dev.lab43.mcplanner.core.AccessGate.State.NEEDS_PLAN;
import static dev.lab43.mcplanner.core.AccessGate.State.SIGN_IN;
import static dev.lab43.mcplanner.core.TestMain.check;
import static dev.lab43.mcplanner.core.TestMain.eq;

/** The access rule for plan files: what /me says (Access), the decision with offline grace (AccessGate), the saved check. */
final class AccessTest {
    private AccessTest() {}

    static final long H = 3600_000L;

    static void run() throws Exception {
        // ---- Access from /me ----
        Access free = Access.parse(JsonParser.parseString(
            "{\"active\":false,\"plan\":\"free\",\"name\":\"Free\",\"source\":\"free\",\"expiresAt\":null,\"trialAvailable\":true,\"files\":false}"));
        eq(new Access(false, "free", "Free", "free", 0, true, false), free, "Free");
        Access trial = Access.parse(JsonParser.parseString(
            "{\"active\":true,\"plan\":\"trial\",\"name\":\"Cloud trial\",\"source\":\"trial\",\"expiresAt\":\"2026-10-09T10:00:00.123456+00:00\",\"trialAvailable\":false,\"files\":true}"));
        eq(java.time.Instant.parse("2026-10-09T10:00:00.123Z").toEpochMilli(), trial.expiresAt(), "ISO end with micros and offset");
        check(trial.active() && trial.files(), "trial: active, files");
        eq(trial, Access.parse(trial.toJson()), "saved form reads back the same");
        eq(free, Access.parse(free.toJson()), "saved form (no end)");
        eq(java.time.OffsetDateTime.parse("2026-10-09T12:00:00+02:00").toInstant().toEpochMilli(),
            Access.parseTime(JsonParser.parseString("\"2026-10-09 12:00:00+02:00\"")), "Postgres text form with a space");
        eq(0L, Access.parseTime(JsonParser.parseString("\"soon\"")), "unreadable end = none");
        eq(1234L, Access.parseTime(JsonParser.parseString("1234")), "epoch ms");
        check(Access.parse(JsonParser.parseString("{\"active\":true}")).files(), "no files field: active decides");
        check(!Access.parse(JsonParser.parseString("{}")).files(), "empty: nothing allowed");
        eq(null, Access.parse(null), "missing");
        eq(null, Access.parse(JsonParser.parseString("\"x\"")), "not an object");
        check(Access.UNRESTRICTED.files() && Access.UNRESTRICTED.expiresAt() == 0, "old servers: unrestricted");

        // ---- the decision ----
        long now = 1_800_000_000_000L;
        AccessGate.Check okCheck = new AccessGate.Check("u1", now - H, new Access(true, "cloud", "Cloud", "subscription", 0, false, true));
        eq(SIGN_IN, AccessGate.files(false, "u1", okCheck, now), "not signed in");
        eq(CHECKING, AccessGate.files(true, "u1", null, now), "never checked");
        eq(CHECKING, AccessGate.files(true, "u2", okCheck, now), "someone else's check doesn't count");
        eq(ALLOWED, AccessGate.files(true, null, okCheck, now), "unknown user id: the check counts");
        eq(ALLOWED, AccessGate.files(true, "u1", okCheck, now), "active plan");
        eq(NEEDS_PLAN, AccessGate.files(true, "u1", new AccessGate.Check("u1", now, free), now), "Free");
        AccessGate.Check old = new AccessGate.Check("u1", now - AccessGate.GRACE_MS - 1, okCheck.access());
        eq(CHECK_AGAIN, AccessGate.files(true, "u1", old, now), "offline for more than 72 h");
        eq(ALLOWED, AccessGate.files(true, "u1", new AccessGate.Check("u1", now - AccessGate.GRACE_MS + 1000, okCheck.access()), now), "71.9 h: still fine");
        AccessGate.Check ending = new AccessGate.Check("u1", now - H, new Access(true, "trial", "Cloud trial", "trial", now + 1000, false, true));
        eq(ALLOWED, AccessGate.files(true, "u1", ending, now), "trial before its end");
        eq(CHECK_AGAIN, AccessGate.files(true, "u1", ending, now + 1000), "trial's end passed: never past expiresAt, even within 72 h");
        eq(CHECK_AGAIN, AccessGate.files(true, "u1", new AccessGate.Check("u1", now + 2 * H, okCheck.access()), now), "a check from the future (clock went back)");
        eq(ALLOWED, AccessGate.files(true, "u1", new AccessGate.Check("u1", now, Access.UNRESTRICTED), now + AccessGate.GRACE_MS - 1), "old server: allowed, with the same grace");

        check(AccessGate.recheckDue(0, false, now), "never asked: due");
        check(!AccessGate.recheckDue(now - 29 * 60_000, false, now), "29 min: not due");
        check(AccessGate.recheckDue(now - 30 * 60_000, false, now), "30 min: due");
        check(AccessGate.recheckDue(now - 60_000, true, now), "waiting for a plan: every minute");
        check(!AccessGate.recheckDue(now - 30_000, true, now), "…not more often");
        check(AccessGate.recheckDue(now + 2 * H, false, now), "clock went back: due");

        // ---- the saved check ----
        Path dir = TestMain.tempDir("access");
        try {
            AuthStore auth = new AuthStore(dir.resolve(".lab43").resolve("mcplanner-minecraft.json"), dir.resolve("cfg.json"));
            AccessGate.Store store = AccessGate.Store.besides(auth);
            eq(dir.resolve(".lab43").resolve("mcplanner-access.json"), store.file(), "next to the token, outside the instance");
            eq(null, store.load("https://auth.lab43.dev"), "nothing saved");
            check(store.save("https://Auth.Lab43.dev/", ending), "saved");
            eq(ending, store.load("https://auth.lab43.dev"), "read back (hub key normalised)");
            check(store.save("http://localhost:3000", okCheck), "second hub");
            eq(okCheck, store.load("http://localhost:3000"), "per hub");
            String text = Files.readString(store.file());
            check(!text.contains("token"), "no token in this file");
            store.clear("https://auth.lab43.dev");
            eq(null, store.load("https://auth.lab43.dev"), "cleared");
            eq(okCheck, store.load("http://localhost:3000"), "the other hub stays");
            store.clear("http://localhost:3000");
            check(!Files.exists(store.file()), "last one cleared: file gone");
            Files.writeString(store.file(), "not json");
            eq(null, store.load("https://auth.lab43.dev"), "unreadable file: nothing");
            check(store.save("https://auth.lab43.dev", okCheck), "…and it's replaced on the next save");
            eq(okCheck, store.load("https://auth.lab43.dev"), "read after replacing");
        } finally {
            TestMain.deleteTree(dir);
        }

        // ---- Me ----
        Me me = new Me("u", "n", null, List.of(Me.SCOPE_READ));
        eq(null, me.access(), "Me without access (old servers)");
    }
}
