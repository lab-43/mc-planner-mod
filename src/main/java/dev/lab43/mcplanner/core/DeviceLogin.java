package dev.lab43.mcplanner.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Drives the device sign-in: get a code, show it ({@link Phase#WAITING}), poll the hub until the person
 * approves / denies in the browser or the code expires. Honours the hub's interval and {@code slow_down}
 * (+5 s each time), never polls faster than the interval, retries up to 3 consecutive transient errors
 * (network, 5xx) with backoff, and stops at expiry.
 *
 * <p>States go to the listener (on {@code callbacks}, default: the scheduler thread). {@link #cancel()} stops
 * silently. {@link #start()} again restarts with a new code.
 */
public final class DeviceLogin {
    private static final Logger LOG = LoggerFactory.getLogger("MC Planner");
    static final int MAX_TRANSIENT_RETRIES = 3;

    public enum Phase { STARTING, WAITING, APPROVED, DENIED, EXPIRED, FAILED }

    /**
     * Current step. WAITING: userCode, verificationUri, verificationUriComplete, expiresAt (epoch ms) are set.
     * APPROVED: token, userId, userName. FAILED: message (for people). Other fields are null / 0.
     */
    public record State(Phase phase, String userCode, String verificationUri, String verificationUriComplete,
                        long expiresAt, String token, String userId, String userName, String message) {

        public static State starting() {
            return new State(Phase.STARTING, null, null, null, 0, null, null, null, null);
        }

        public static State waiting(String code, String uri, String uriComplete, long expiresAt) {
            return new State(Phase.WAITING, code, uri, uriComplete, expiresAt, null, null, null, null);
        }

        public static State approved(String token, String userId, String userName) {
            return new State(Phase.APPROVED, null, null, null, 0, token, userId, userName, null);
        }

        public static State denied() {
            return new State(Phase.DENIED, null, null, null, 0, null, null, null, "Sign-in was denied in the browser.");
        }

        public static State expired() {
            return new State(Phase.EXPIRED, null, null, null, 0, null, null, null, "The sign-in code expired. Please start again.");
        }

        public static State failed(String message) {
            return new State(Phase.FAILED, null, null, null, 0, null, null, null, message);
        }

        /** APPROVED, DENIED, EXPIRED or FAILED: nothing more will happen. */
        public boolean isFinal() {
            return phase == Phase.APPROVED || phase == Phase.DENIED || phase == Phase.EXPIRED || phase == Phase.FAILED;
        }
    }

    /** Length of one "second" of the hub's interval / expires_in, in ms (tests make it short). */
    long unitMs = 1000;

    private final HubClient hub;
    private final String clientId;
    private final String deviceName;
    private final ScheduledExecutorService exec;
    private final Executor callbacks;
    private final Consumer<State> listener;

    private int generation;
    private boolean running;
    private ScheduledFuture<?> pending;
    private State state;
    private String deviceCode;
    private long intervalSec;
    private long expiresAt;
    private int failures;
    private int polls;

    public DeviceLogin(HubClient hub, String clientId, String deviceName, ScheduledExecutorService exec, Consumer<State> listener) {
        this(hub, clientId, deviceName, exec, listener, Runnable::run);
    }

    /** @param callbacks where the listener runs (e.g. the game's main thread) */
    public DeviceLogin(HubClient hub, String clientId, String deviceName, ScheduledExecutorService exec, Consumer<State> listener,
                       Executor callbacks) {
        this.hub = Objects.requireNonNull(hub);
        this.clientId = Objects.requireNonNull(clientId);
        this.deviceName = deviceName;
        this.exec = Objects.requireNonNull(exec);
        this.listener = Objects.requireNonNull(listener);
        this.callbacks = Objects.requireNonNull(callbacks);
    }

    /** Starts (or restarts) the sign-in. */
    public synchronized void start() {
        stopTimers();
        int gen = ++generation;
        running = true;
        failures = 0;
        polls = 0;
        deviceCode = null;
        emit(State.starting());
        requestCode(gen);
    }

    /** Stops polling; no further states are delivered. */
    public synchronized void cancel() {
        generation++;
        running = false;
        stopTimers();
    }

    /** The last state (null before start). */
    public synchronized State state() {
        return state;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    /** Number of token polls sent since start (diagnostics / tests). */
    synchronized int polls() {
        return polls;
    }

    /** Current poll interval in seconds. */
    public synchronized long intervalSeconds() {
        return intervalSec;
    }

    private void requestCode(int gen) {
        hub.startDevice(clientId, deviceName).whenComplete((dc, err) -> onCode(gen, dc, err));
    }

    private synchronized void onCode(int gen, DeviceCode dc, Throwable err) {
        if (gen != generation) return;
        if (err != null) {
            ApiException e = ApiException.of(err);
            if (e.isTransient() && ++failures <= MAX_TRANSIENT_RETRIES) {
                LOG.debug("Device code request failed ({}), retrying", e.code());
                schedule(gen, unitMs * (1L << failures), () -> requestCode(gen));
                return;
            }
            finish(State.failed(e.getMessage()));
            return;
        }
        failures = 0;
        deviceCode = dc.deviceCode();
        intervalSec = Math.max(1, dc.interval());
        expiresAt = System.currentTimeMillis() + dc.expiresIn() * unitMs;
        emit(State.waiting(dc.userCode(), dc.verificationUri(), dc.verificationUriComplete(), expiresAt));
        schedulePoll(gen, intervalSec * unitMs);
    }

    private void schedulePoll(int gen, long delayMs) {
        if (System.currentTimeMillis() >= expiresAt) {
            finish(State.expired());
            return;
        }
        schedule(gen, delayMs, () -> poll(gen));
    }

    private void poll(int gen) {
        String code;
        synchronized (this) {
            if (gen != generation) return;
            if (System.currentTimeMillis() >= expiresAt) {
                finish(State.expired());
                return;
            }
            code = deviceCode;
            polls++;
        }
        hub.pollToken(clientId, code).whenComplete((tr, err) -> onToken(gen, tr, err));
    }

    private synchronized void onToken(int gen, TokenResult tr, Throwable err) {
        if (gen != generation) return;
        if (err != null) {
            ApiException e = ApiException.of(err);
            if (e.isTransient() && ++failures <= MAX_TRANSIENT_RETRIES) {
                LOG.debug("Device token poll failed ({}), retry {}", e.code(), failures);
                schedulePoll(gen, intervalSec * unitMs * (1L << failures));
                return;
            }
            finish(State.failed(e.getMessage()));
            return;
        }
        failures = 0;
        switch (tr.kind()) {
            case PENDING:
                schedulePoll(gen, intervalSec * unitMs);
                break;
            case SLOW_DOWN:
                intervalSec = Math.max(intervalSec + 5, tr.interval());
                schedulePoll(gen, intervalSec * unitMs);
                break;
            case APPROVED:
                finish(State.approved(tr.accessToken(), tr.userId(), tr.userName()));
                break;
            case DENIED:
                finish(State.denied());
                break;
            case EXPIRED:
                finish(State.expired());
                break;
            case INVALID:
            default:
                finish(State.failed("This sign-in code is no longer valid. Please start again."));
                break;
        }
    }

    private void schedule(int gen, long delayMs, Runnable r) {
        try {
            pending = exec.schedule(() -> {
                synchronized (DeviceLogin.this) {
                    if (gen != generation) return;
                }
                r.run();
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            finish(State.failed("Sign-in stopped."));
        }
    }

    private void finish(State s) {
        running = false;
        generation++;
        stopTimers();
        emit(s);
    }

    private void stopTimers() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    private void emit(State s) {
        state = s;
        try {
            callbacks.execute(() -> listener.accept(s));
        } catch (RejectedExecutionException e) {
            LOG.debug("Sign-in state dropped: {}", s.phase());
        }
    }
}
