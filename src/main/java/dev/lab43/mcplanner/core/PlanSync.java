package dev.lab43.mcplanner.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps one shared world's plan up to date in the background.
 *
 * <ul>
 *   <li>Polls only while {@link #setActive active}, every {@code pollSeconds} the server asks for
 *       ({@code X-Poll-Interval}, default 10, at least 5), with {@code If-None-Match} so unchanged plans cost
 *       a 304.</li>
 *   <li>Errors back off exponentially up to 2 minutes; {@link Listener#onError} only fires when the kind of
 *       error changes, {@link Listener#onRecovered} when it works again.</li>
 *   <li>401 → {@link Listener#onUnauthorized} and 403/404 → {@link Listener#onLost}; polling then stops until
 *       {@link #refreshNow()} or {@link #select} is called again.</li>
 *   <li>402 (the world is paused: its owner's plan ended) → {@link Listener#onPaused}: the plan is dropped (also from
 *       the offline cache) and polling goes on slowly ({@code pausedPollMs}, 5 minutes) until the world is live again,
 *       which arrives as a normal {@link Listener#onPlan}.</li>
 *   <li>Every listener call is posted to {@code mainThread}.</li>
 *   <li>With {@link #setCache a cache}, a newly selected world first shows the last saved plan (offline), and
 *       every fresh plan is saved in the background. A saved plan older than {@code maxCacheAgeMs} (72 hours, like
 *       the offline grace of plan files) isn't shown; a 304 refreshes its age at most once an hour.</li>
 * </ul>
 */
public final class PlanSync {
    private static final Logger LOG = LoggerFactory.getLogger("MC Planner");

    public interface Listener {
        /** A new plan (from the server, the offline cache — see {@code meta.fromCache()} — or {@link #applyLocal}). */
        void onPlan(Plan plan, PlanResponse meta);

        /** The device token was rejected: sign in again, then call {@link #refreshNow()}. */
        void onUnauthorized();

        /** No access to this world any more (403) or it's gone (404). */
        void onLost(String message);

        /** Fetching fails (network, server); only called when the kind of error changes. */
        void onError(String message);

        /** Fetching works again after {@link #onError}. */
        default void onRecovered() {}

        /**
         * The world is paused (402: its owner's MC Planner plan ended) — nothing may be shown. Called once per pause;
         * when it's live again the plan comes through {@link #onPlan}.
         */
        default void onPaused(String message) {}
    }

    // test overrides (package-private)
    /** Length of one second of the poll interval, in ms. */
    long unitMs = 1000;
    int minPollSeconds = 5;
    long maxBackoffMs = 120_000;
    /** how often a paused world is asked again */
    long pausedPollMs = 5 * 60_000;
    /** the offline cache shows plans saved at most this long ago */
    long maxCacheAgeMs = AccessGate.GRACE_MS;
    /** a 304 marks the cached plan as still current at most this often */
    long cacheTouchMs = 3600_000;

    private final PlannerClient client;
    private final ScheduledExecutorService exec;
    private final Executor mainThread;
    private final Listener listener;
    private volatile PlanCache cache;

    // all guarded by this
    private String roomId;
    private int gen;
    private boolean active;
    private boolean inFlight;
    private boolean refetch;
    private boolean stopped;
    private ScheduledFuture<?> timer;
    private String etag;
    private Plan plan;
    private PlanResponse meta;
    private int pollSeconds = PlannerClient.DEFAULT_POLL_SECONDS;
    private int errors;
    private String lastErrorKind;
    private long lastFetchAt;
    private int localSeq;
    private int fetches;
    private boolean paused;
    private String pausedMessage;
    private long cacheSavedAt;

    public PlanSync(PlannerClient client, ScheduledExecutorService exec, Executor mainThread, Listener l) {
        this.client = Objects.requireNonNull(client);
        this.exec = Objects.requireNonNull(exec);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.listener = Objects.requireNonNull(l);
    }

    /** Optional offline cache (see class docs). Set it before {@link #select}. */
    public void setCache(PlanCache cache) {
        this.cache = cache;
    }

    /**
     * Follow this world (room id). Selecting the same world again refreshes it and tells the listener the current state
     * again (the plan, or that it's paused), so a caller that dropped its copy gets it back.
     */
    public synchronized void select(String roomId) {
        if (roomId == null) {
            clear();
            return;
        }
        if (roomId.equals(this.roomId)) {
            if (paused) {
                post(() -> listener.onPaused(pausedMessage));
            } else if (plan != null && meta != null) {
                Plan p = plan;
                PlanResponse m = meta;
                post(() -> listener.onPlan(p, m));
            }
            refreshNow();
            return;
        }
        reset();
        this.roomId = roomId;
        PlanCache c = cache;
        if (c != null) {
            int g = gen;
            inFlight = true; // the first fetch waits for the cache (it may give us an etag)
            try {
                exec.execute(() -> loadCache(g, c, roomId));
            } catch (RejectedExecutionException e) {
                inFlight = false;
                if (active) fetch();
            }
        } else if (active) {
            fetch();
        }
    }

    /** Stop following any world. */
    public synchronized void clear() {
        reset();
    }

    /** Poll only while active (e.g. while in a world with the overlay on). Only transitions matter. */
    public synchronized void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        if (!active) {
            cancelTimer();
            return;
        }
        if (roomId == null || stopped || inFlight || timer != null) return;
        long pollMs = paused ? Math.max(pollSeconds * unitMs, pausedPollMs) : pollSeconds * unitMs;
        long since = System.currentTimeMillis() - lastFetchAt;
        if (lastFetchAt == 0 || since >= pollMs) fetch();
        else schedule(pollMs - since);
    }

    /** Fetch now (also after {@link Listener#onUnauthorized} / onLost, e.g. once signed in again). */
    public synchronized void refreshNow() {
        stopped = false;
        if (roomId == null) return;
        if (inFlight) refetch = true;
        else fetch();
    }

    /**
     * Patch the cached plan right after a successful write (the object the server returned), tell the listener,
     * and refresh.
     */
    public synchronized void applyLocal(PlanObject o) {
        if (plan == null || o == null) return;
        localSeq++;
        plan = plan.withObject(o);
        PlanResponse m = meta;
        meta = m != null
            ? new PlanResponse(false, plan, m.etag(), m.role(), m.paused(), m.canEdit(), m.name(), m.pollSeconds(), m.raw(), m.fromCache())
            : new PlanResponse(false, plan, etag, null, false, false, plan.worldName, pollSeconds, null, false);
        Plan p = plan;
        PlanResponse mm = meta;
        post(() -> listener.onPlan(p, mm));
        refreshNow();
    }

    public synchronized Plan plan() {
        return plan;
    }

    public synchronized PlanResponse lastMeta() {
        return meta;
    }

    public synchronized String roomId() {
        return roomId;
    }

    public synchronized String etag() {
        return etag;
    }

    public synchronized boolean isActive() {
        return active;
    }

    /** the last answer was 402: the world is paused */
    public synchronized boolean isPaused() {
        return paused;
    }

    /** Current poll interval in seconds. */
    public synchronized int pollSeconds() {
        return pollSeconds;
    }

    /** Requests sent since the last select (diagnostics / tests). */
    synchronized int fetches() {
        return fetches;
    }

    // ------------------------------------------------------------------------------------------

    private void reset() {
        gen++;
        cancelTimer();
        roomId = null;
        etag = null;
        plan = null;
        meta = null;
        inFlight = false;
        refetch = false;
        stopped = false;
        errors = 0;
        lastErrorKind = null;
        lastFetchAt = 0;
        pollSeconds = PlannerClient.DEFAULT_POLL_SECONDS;
        fetches = 0;
        paused = false;
        pausedMessage = null;
        cacheSavedAt = 0;
    }

    private void loadCache(int g, PlanCache c, String room) {
        PlanCache.Cached cached = null;
        try {
            cached = c.load(room);
        } catch (RuntimeException e) {
            LOG.debug("Could not read the cached plan of {}", room, e);
        }
        synchronized (this) {
            if (g != gen) return;
            inFlight = false;
            long age = cached == null ? 0 : System.currentTimeMillis() - cached.savedAt();
            if (cached != null && (age > maxCacheAgeMs || age < -AccessGate.CLOCK_SLACK_MS)) {
                LOG.debug("Not showing the saved plan of {}: saved {} h ago", room, age / 3600_000);
                cached = null;
            }
            if (cached != null && plan == null) {
                cacheSavedAt = cached.savedAt();
                plan = cached.plan();
                etag = cached.etag();
                meta = cached.response();
                Plan p = plan;
                PlanResponse m = meta;
                post(() -> listener.onPlan(p, m));
            }
            if (active || refetch) {
                refetch = false;
                fetch();
            }
        }
    }

    private void fetch() {
        if (roomId == null) return;
        if (inFlight) {
            refetch = true;
            return;
        }
        cancelTimer();
        inFlight = true;
        fetches++;
        lastFetchAt = System.currentTimeMillis();
        int g = gen, seq = localSeq;
        String room = roomId;
        CompletableFuture<PlanResponse> f;
        try {
            f = client.plan(room, etag);
        } catch (RuntimeException e) {
            f = CompletableFuture.failedFuture(e);
        }
        f.whenComplete((resp, err) -> onResult(g, seq, room, resp, err));
    }

    private synchronized void onResult(int g, int seq, String room, PlanResponse resp, Throwable err) {
        if (g != gen) return;
        inFlight = false;
        if (err == null) {
            boolean recovered = lastErrorKind != null;
            errors = 0;
            lastErrorKind = null;
            paused = false;
            pollSeconds = Math.max(minPollSeconds, resp.pollSeconds());
            if (recovered) post(listener::onRecovered);
            if (resp.notModified()) {
                if (plan == null) { // nothing to keep: get the whole plan
                    etag = null;
                    refetch = true;
                } else {
                    touchCache(room);
                }
            } else if (seq != localSeq) {
                refetch = true; // started before a local change: this answer may not have it yet
            } else {
                etag = resp.etag();
                plan = resp.plan();
                meta = resp;
                Plan p = plan;
                post(() -> listener.onPlan(p, resp));
                saveCache(room, resp);
            }
            next();
            return;
        }
        ApiException e = ApiException.of(err);
        if (e.isUnauthorized()) {
            stopped = true;
            post(listener::onUnauthorized);
            return;
        }
        if (e.status() == 402) {
            boolean first = !paused;
            boolean recovered = lastErrorKind != null;
            paused = true;
            pausedMessage = e.getMessage();
            errors = 0;
            lastErrorKind = null;
            plan = null;
            meta = null;
            etag = null;
            dropCache(room);
            String msg = e.getMessage();
            if (recovered) post(listener::onRecovered);
            if (first) post(() -> listener.onPaused(msg));
            next();
            return;
        }
        if (e.status() == 403 || e.status() == 404) {
            stopped = true;
            String msg = e.getMessage();
            post(() -> listener.onLost(msg));
            return;
        }
        errors++;
        String kind = e.code();
        if (!kind.equals(lastErrorKind)) {
            lastErrorKind = kind;
            String msg = e.getMessage();
            post(() -> listener.onError(msg));
        }
        LOG.debug("Plan sync failed ({}), attempt {}", kind, errors);
        next();
    }

    private void next() {
        if (refetch) {
            refetch = false;
            fetch();
            return;
        }
        if (!active || stopped || roomId == null) return;
        long pollMs = pollSeconds * unitMs;
        long delay = paused ? Math.max(pollMs, pausedPollMs) : pollMs;
        if (errors > 0) delay = Math.min(maxBackoffMs, pollMs * (1L << Math.min(errors, 20)));
        schedule(delay);
    }

    private void schedule(long delayMs) {
        cancelTimer();
        int g = gen;
        try {
            timer = exec.schedule(() -> tick(g), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            timer = null;
        }
    }

    private synchronized void tick(int g) {
        if (g != gen) return;
        timer = null;
        if (!active || stopped) return;
        fetch();
    }

    private void cancelTimer() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }

    private void saveCache(String room, PlanResponse resp) {
        PlanCache c = cache;
        if (c == null || resp.raw() == null) return;
        cacheSavedAt = System.currentTimeMillis();
        try {
            exec.execute(() -> c.save(room, resp.raw(), resp.etag()));
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    /** the plan didn't change: the saved copy is still current (at most once per cacheTouchMs) */
    private void touchCache(String room) {
        PlanCache c = cache;
        long now = System.currentTimeMillis();
        if (c == null || now - cacheSavedAt < cacheTouchMs) return;
        cacheSavedAt = now;
        try {
            exec.execute(() -> c.touch(room));
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    /** a paused world: its plan isn't shown, not even offline */
    private void dropCache(String room) {
        PlanCache c = cache;
        cacheSavedAt = 0;
        if (c == null) return;
        try {
            exec.execute(() -> c.delete(room));
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    /** Runs r on the main thread — unless another world was selected (or the sync cleared) in the meantime. */
    private void post(Runnable r) {
        final int g;
        synchronized (this) {
            g = gen;
        }
        try {
            mainThread.execute(() -> {
                synchronized (this) {
                    if (g != gen) return;
                }
                r.run();
            });
        } catch (RejectedExecutionException e) {
            LOG.debug("Plan sync callback dropped");
        }
    }
}
