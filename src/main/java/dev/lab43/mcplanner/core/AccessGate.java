package dev.lab43.mcplanner.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * May plan files (.mcplan) be opened? The mod needs an active MC Planner plan (docs/PLAN-mod-launch.md): for plan
 * files the person's own, as {@code /api/mod/me} reports it ({@link Access}). The last answer is kept with the time
 * of the check, so plan files also open offline — for {@link #GRACE_MS 72 hours} after the last successful check, and
 * never past the plan's own end ({@link Access#expiresAt()}).
 *
 * <p>Saved next to the sign-in token, outside the instance ({@code ~/.lab43/mcplanner-access.json}), one entry per
 * hub: {@code {"version":1,"checks":{"<hub>":{userId, checkedAt, access:{…}}}}}. Forgotten on sign-out.
 */
public final class AccessGate {
    private static final Logger LOG = LoggerFactory.getLogger("MC Planner");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** how long the last check counts while the server can't be reached */
    public static final long GRACE_MS = 72L * 3600_000;
    /** how often to check again while playing */
    public static final long RECHECK_MS = 30L * 60_000;
    /**
     * …and every minute while a plan file waits for a plan, for {@link #WAITING_WINDOW_MS} after the file was opened or
     * the player went to start a plan (someone who just started the trial shouldn't wait 30 minutes — but a Free player
     * with a remembered file shouldn't ask every minute all evening either)
     */
    public static final long RECHECK_WAITING_MS = 60_000;
    public static final long WAITING_WINDOW_MS = 15L * 60_000;
    /** a clock that went back more than this makes the saved check useless (else it would last forever) */
    static final long CLOCK_SLACK_MS = 3600_000;

    public enum State {
        /** plan files open */
        ALLOWED,
        /** not signed in: sign in first */
        SIGN_IN,
        /** signed in, the first check is still on its way */
        CHECKING,
        /** checked: no plan of their own */
        NEEDS_PLAN,
        /** the last check is too old (offline for more than 72 h) or the plan's end has passed: check again */
        CHECK_AGAIN
    }

    /** One successful check. */
    public record Check(String userId, long checkedAt, Access access) {
        public Check {
            Objects.requireNonNull(access);
        }
    }

    private AccessGate() {}

    /**
     * The decision for plan files.
     *
     * @param signedIn whether there is a device token
     * @param userId   the signed-in person (a saved check of someone else doesn't count); null = unknown, any check counts
     * @param last     the last successful check, or null
     * @param now      epoch ms
     */
    public static State files(boolean signedIn, String userId, Check last, long now) {
        if (!signedIn) return State.SIGN_IN;
        if (last == null || (userId != null && last.userId() != null && !userId.equals(last.userId()))) return State.CHECKING;
        if (!last.access().files()) return State.NEEDS_PLAN;
        long age = now - last.checkedAt();
        if (age > GRACE_MS || age < -CLOCK_SLACK_MS) return State.CHECK_AGAIN;
        long end = last.access().expiresAt();
        if (end > 0 && now >= end) return State.CHECK_AGAIN;
        return State.ALLOWED;
    }

    /** when to ask /me again, given the last attempt (successful or not) */
    public static boolean recheckDue(long lastAttempt, boolean waitingForPlan, long now) {
        if (lastAttempt <= 0) return true;
        long every = waitingForPlan ? RECHECK_WAITING_MS : RECHECK_MS;
        return now - lastAttempt >= every || now < lastAttempt - CLOCK_SLACK_MS;
    }

    // ------------------------------------------------------------------ the saved check

    /** Where the last check of each hub is kept. */
    public static final class Store {
        private final Path file;

        public Store(Path file) {
            this.file = file;
        }

        /** next to the sign-in token of {@link AuthStore} */
        public static Store besides(AuthStore auth) {
            return new Store(auth.primaryFile().resolveSibling("mcplanner-access.json"));
        }

        public Path file() {
            return file;
        }

        public synchronized Check load(String hub) {
            JsonObject e = Json.obj(Json.get(checks(), AuthStore.hubKey(hub)));
            if (e == null) return null;
            Access a = Access.parse(Json.get(e, "access"));
            Double at = Json.finite(e, "checkedAt");
            if (a == null || at == null) return null;
            return new Check(Json.text(e, "userId"), at.longValue(), a);
        }

        /** false (logged) when it can't be written — the check then only lasts while the game runs */
        public synchronized boolean save(String hub, Check c) {
            JsonObject all = checks();
            JsonObject e = new JsonObject();
            if (c.userId() != null) e.addProperty("userId", c.userId());
            e.addProperty("checkedAt", c.checkedAt());
            e.add("access", c.access().toJson());
            all.add(AuthStore.hubKey(hub), e);
            return write(all);
        }

        public synchronized void clear(String hub) {
            JsonObject all = checks();
            if (all.remove(AuthStore.hubKey(hub)) == null) return;
            if (all.size() == 0) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ex) {
                    LOG.warn("Could not delete {}: {}", file, ex.toString());
                }
            } else {
                write(all);
            }
        }

        private JsonObject checks() {
            try {
                JsonObject root = Json.obj(JsonParser.parseString(Files2.read(file)));
                JsonObject c = Json.obj(Json.get(root, "checks"));
                return c != null ? c.deepCopy() : new JsonObject();
            } catch (NoSuchFileException e) {
                return new JsonObject();
            } catch (IOException | RuntimeException e) {
                LOG.warn("Ignoring the unreadable plan check {}: {}", file, e.toString());
                return new JsonObject();
            }
        }

        private boolean write(JsonObject checks) {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("checks", checks);
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files2.writeAtomic(file, GSON.toJson((JsonElement) root), true);
                return true;
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not save the plan check in {}: {}", file, e.toString());
                return false;
            }
        }
    }
}
