package dev.lab43.mcplanner.mc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.lab43.mcplanner.core.Access;
import dev.lab43.mcplanner.core.AccessGate;
import dev.lab43.mcplanner.core.ApiException;
import dev.lab43.mcplanner.core.AuthStore;
import dev.lab43.mcplanner.core.DeviceLogin;
import dev.lab43.mcplanner.core.HubClient;
import dev.lab43.mcplanner.core.Kind;
import dev.lab43.mcplanner.core.LocalPlans;
import dev.lab43.mcplanner.core.MarkerRequest;
import dev.lab43.mcplanner.core.Me;
import dev.lab43.mcplanner.core.ModConfig;
import dev.lab43.mcplanner.core.Plan;
import dev.lab43.mcplanner.core.PlanCache;
import dev.lab43.mcplanner.core.PlanObject;
import dev.lab43.mcplanner.core.PlanResponse;
import dev.lab43.mcplanner.core.PlanSync;
import dev.lab43.mcplanner.core.PlannerClient;
import dev.lab43.mcplanner.core.Status;
import dev.lab43.mcplanner.core.WorldInfo;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Everything the mod knows and does, independent of screens and rendering: the signed-in Lab43 account, which plan is
 * shown (a shared world from app.mc-planner.com or a local .mcplan file), the plan itself, navigation and edits.
 *
 * <p>The mod needs an active MC Planner plan (docs/PLAN-mod-launch.md): a shared world shows while its OWNER's plan
 * is active (the server answers 402 for a paused world), a plan file only with the player's own plan
 * ({@link #filesAccess()}: /api/mod/me, checked at sign-in, every 30 minutes and when a file is opened, 72 hours offline
 * grace). {@link #gate()} says what keeps the chosen plan from showing.
 *
 * <p>All public methods are called on the game's main thread; network work runs on the core library's threads and
 * comes back through {@link #main}.
 */
public final class Planner {
    public static final Logger LOG = LoggerFactory.getLogger("MC Planner");
    private static Planner instance;

    public enum Source { NONE, ROOM, FILE }

    /** What keeps the chosen plan from showing (the mod needs an active MC Planner plan). */
    public enum Gate {
        NONE,
        /** a plan file, not signed in */
        SIGN_IN,
        /** a plan file, the first check is on its way */
        CHECKING,
        /** a plan file, no plan of their own */
        NEEDS_PLAN,
        /** a plan file, the last check is too old (offline > 72 h) or the plan's end has passed */
        CHECK_AGAIN,
        /** a shared world whose owner's plan ended */
        PAUSED
    }

    public final Minecraft mc;
    public final Path gameDir;
    public final Path configFile;
    public final Executor main;
    public final ScheduledExecutorService exec;
    public final AuthStore authStore;
    /** the last check of the player's own plan (plan files), next to the token */
    public final AccessGate.Store accessStore;
    /** the plan's schematic files found on this computer */
    public final Schematics schematics;
    public final PlanCache cache;
    public final String userAgent;

    public ModConfig config;
    private HubClient hub;
    private PlannerClient api;
    private PlanSync sync;

    // account
    private AuthStore.Entry account;
    private Me me;
    private DeviceLogin login;
    private DeviceLogin.State loginState;
    private final List<Consumer<DeviceLogin.State>> loginListeners = new ArrayList<>();

    // the player's own plan (for plan files)
    private AccessGate.Check accessCheck;
    private long accessAttemptAt;
    private boolean accessChecking;
    private String accessError;
    /** until when a waiting plan file is checked every minute (AccessGate.WAITING_WINDOW_MS) */
    private long accessWaitUntil;

    // which plan
    private Source source = Source.NONE;
    private String roomId;
    private Path file;
    private LocalPlans.FileWatch watch;
    private String sourceName;
    /** the chosen plan file is shown (false while it waits for a plan) */
    private boolean fileLoaded;
    /** the chosen shared world is paused (402): the server's message */
    private String pausedMessage;

    // the plan
    private Plan plan = Plan.empty();
    private PlanResponse meta;
    private long planAt;
    private String error;
    /** bumped whenever the plan changes: the renderer and screens rebuild what they cached */
    private int planVersion;

    // shared worlds (for the picker)
    private List<WorldInfo> worlds = List.of();
    private boolean worldsLoading;
    private String worldsError;
    private long worldsAt;

    // the game
    private String context;
    private String navId;
    private boolean busy;

    private Planner(Minecraft mc) {
        this.mc = mc;
        this.gameDir = mc.gameDirectory.toPath();
        this.configFile = gameDir.resolve("config").resolve("mcplanner.json");
        this.main = mc::execute;
        this.exec = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "MC Planner");
            t.setDaemon(true);
            return t;
        });
        this.authStore = new AuthStore(gameDir);
        this.accessStore = AccessGate.Store.besides(authStore);
        this.cache = new PlanCache(gameDir);
        this.schematics = new Schematics(this);
        this.userAgent = PlannerClient.userAgent(McPlannerClient.version(), SharedConstants.getCurrentVersion().name());
        this.config = ModConfig.load(configFile);
        rebuildClients();
        this.account = authStore.load(config.hubUrl);
        this.accessCheck = account == null ? null : accessStore.load(config.hubUrl);
    }

    public static Planner init(Minecraft mc) {
        if (instance == null) instance = new Planner(mc);
        return instance;
    }

    public static Planner get() {
        return instance;
    }

    private void rebuildClients() {
        if (sync != null) sync.clear();
        hub = new HubClient(config.hubUrl, userAgent);
        api = new PlannerClient(config.apiUrl, userAgent, () -> account == null ? null : account.token());
        sync = new PlanSync(api, exec, main, new PlanSync.Listener() {
            @Override
            public void onPlan(Plan p, PlanResponse m) {
                if (source != Source.ROOM) return;
                if (pausedMessage != null && m.fromCache()) return; // paused: not even the saved copy
                boolean first = meta == null || !java.util.Objects.equals(meta.name(), m.name());
                if (pausedMessage != null && !m.fromCache()) {
                    pausedMessage = null;
                    first = true;
                }
                plan = p;
                meta = m;
                planAt = System.currentTimeMillis();
                if (m.name() != null && !m.name().isBlank()) sourceName = m.name();
                if (!m.fromCache()) error = null;
                planVersion++;
                if (first && !m.fromCache()) toast("Plan loaded", sourceName + " · " + countLabel(p));
            }

            @Override
            public void onUnauthorized() {
                signedOutRemotely();
            }

            @Override
            public void onLost(String message) {
                toast("Plan closed", message);
                clearSource(false);
            }

            @Override
            public void onError(String message) {
                error = message;
            }

            @Override
            public void onRecovered() {
                error = null;
            }

            @Override
            public void onPaused(String message) {
                if (source != Source.ROOM) return;
                boolean announce = pausedMessage == null || plan.size() > 0;
                WorldInfo w = world();
                pausedMessage = message == null || message.isBlank() ? pausedText(w) : message;
                plan = Plan.empty();
                meta = null;
                error = null;
                navId = null;
                planVersion++;
                if (announce) toast("MC Planner", w != null && "owner".equals(w.role())
                        ? "This world is paused: it isn't one of your live worlds. See MC Planner."
                        : "This world is paused: its owner's plan doesn't keep it live.");
            }
        });
        sync.setCache(cache);
    }

    public void saveConfig() {
        config.save(configFile);
    }

    // ------------------------------------------------------------------ account

    public boolean isSignedIn() {
        return account != null && account.token() != null;
    }

    public String accountName() {
        if (me != null && me.name() != null && !me.name().isBlank()) return me.name();
        if (account != null && account.userName() != null && !account.userName().isBlank()) return account.userName();
        return "your Lab43 account";
    }

    public boolean canWriteScope() {
        return me == null || me.canWrite();
    }

    public DeviceLogin.State loginState() {
        return loginState;
    }

    public void addLoginListener(Consumer<DeviceLogin.State> l) {
        loginListeners.add(l);
    }

    public void removeLoginListener(Consumer<DeviceLogin.State> l) {
        loginListeners.remove(l);
    }

    /** Starts the browser sign-in (auth.lab43.dev/device) — the screen shows the code from {@link #loginState()}. */
    public void startSignIn() {
        if (login != null) login.cancel();
        String device = "Minecraft " + SharedConstants.getCurrentVersion().name() + " · " + osName();
        DeviceLogin[] self = new DeviceLogin[1];
        // states of a sign-in that was cancelled or replaced in the meantime are dropped
        self[0] = new DeviceLogin(hub, AuthStore.CLIENT_ID, device, exec, s -> {
            if (login == self[0]) onLoginState(s);
        }, main);
        login = self[0];
        loginState = DeviceLogin.State.starting();
        login.start();
    }

    public void cancelSignIn() {
        if (login != null) login.cancel();
        login = null;
        loginState = null;
    }

    private void onLoginState(DeviceLogin.State s) {
        loginState = s;
        if (s.phase() == DeviceLogin.Phase.APPROVED) {
            authStore.save(config.hubUrl, s.token(), s.userId(), s.userName());
            account = authStore.load(config.hubUrl);
            if (account == null) account = new AuthStore.Entry(config.hubUrl, s.token(), s.userId(), s.userName(), System.currentTimeMillis());
            login = null;
            toast("Signed in", "as " + accountName());
            accessCheck = accessStore.load(config.hubUrl); // only counts if it's this person's (AccessGate.files)
            accessError = null;
            accessAttemptAt = 0;
            fetchMe();
            loadWorlds(true);
            if (source == Source.ROOM) sync.refreshNow();
            else if (source == Source.NONE) selectRemembered();
        }
        for (Consumer<DeviceLogin.State> l : List.copyOf(loginListeners)) l.accept(s);
    }

    /** /api/mod/me: the account, and whether plan files may be opened (the player's own plan) */
    private void fetchMe() {
        if (!isSignedIn() || accessChecking) return;
        accessChecking = true;
        accessAttemptAt = System.currentTimeMillis();
        AuthStore.Entry asked = account;
        api.me().whenComplete((m, t) -> main.execute(() -> {
            accessChecking = false;
            if (account != asked) return; // signed out (or in again) meanwhile
            if (m != null) {
                me = m;
                accessError = null;
                String uid = m.userId() != null ? m.userId() : account.userId();
                // a server from before the access rule doesn't say: everything as before
                accessCheck = new AccessGate.Check(uid, System.currentTimeMillis(), m.access() != null ? m.access() : Access.UNRESTRICTED);
                accessStore.save(config.hubUrl, accessCheck);
            } else {
                ApiException e = ApiException.of(t);
                if (e.isUnauthorized()) {
                    signedOutRemotely();
                    return;
                }
                accessError = e.getMessage();
            }
            accessChanged();
        }));
    }

    /** Sign out on this device: the token is revoked on the server too (best effort). */
    public void signOut() {
        if (isSignedIn()) api.logout().exceptionally(t -> null);
        forgetAccount();
        toast("Signed out", "MC Planner is disconnected from your Lab43 account.");
    }

    /** The server no longer accepts the token (disconnected on auth.lab43.dev/account). */
    private void signedOutRemotely() {
        if (!isSignedIn()) return;
        forgetAccount();
        toast("Signed out", "This device was disconnected. Press J to sign in again.");
    }

    private void forgetAccount() {
        authStore.clear(config.hubUrl);
        accessStore.clear(config.hubUrl);
        account = null;
        me = null;
        accessCheck = null;
        accessError = null;
        accessAttemptAt = 0;
        worlds = List.of();
        worldsAt = 0;
        if (source == Source.ROOM) clearSource(false);
        accessChanged();
    }

    // ------------------------------------------------------------------ the player's own plan (plan files)

    /** May plan files be opened now? (Shared worlds go by their owner's plan instead: {@link #isPaused()}.) */
    public AccessGate.State filesAccess() {
        return AccessGate.files(isSignedIn(), account == null ? null : account.userId(), accessCheck, System.currentTimeMillis());
    }

    /** what the last check said about the player's own plan; null before the first check */
    public Access access() {
        return accessCheck == null ? null : accessCheck.access();
    }

    public long accessCheckedAt() {
        return accessCheck == null ? 0 : accessCheck.checkedAt();
    }

    public boolean accessChecking() {
        return accessChecking;
    }

    /** why the last check failed (offline, server trouble); null when it worked */
    public String accessError() {
        return accessError;
    }

    /** "Check again": the player's plan now (plan files), and a paused world's state */
    public void checkAgain() {
        expectPlanSoon();
        fetchMe();
        if (source == Source.ROOM) sync.refreshNow();
    }

    /** the player went to start a plan (or asked to check): look every minute for a while */
    public void expectPlanSoon() {
        accessWaitUntil = System.currentTimeMillis() + AccessGate.WAITING_WINDOW_MS;
    }

    /** the check's answer arrived (or the account changed): show or hide the plan file */
    private void accessChanged() {
        if (source != Source.FILE) return;
        if (filesAccess() == AccessGate.State.ALLOWED) {
            if (!fileLoaded) loadFile(true);
        } else if (fileLoaded) {
            fileLoaded = false;
            plan = Plan.empty();
            navId = null;
            error = null;
            planVersion++;
            toast("Plan files need an MC Planner plan", gate() == Gate.CHECK_AGAIN ? "Connect to the internet so the mod can check yours (J → What now?)." : "J → What now?");
        }
    }

    /** What keeps the chosen plan from showing. */
    public Gate gate() {
        if (source == Source.ROOM && pausedMessage != null) return Gate.PAUSED;
        if (source != Source.FILE) return Gate.NONE;
        return filesGate();
    }

    /** What keeps plan files from showing, whatever is shown now (NONE: they open). */
    public Gate filesGate() {
        return switch (filesAccess()) {
            case ALLOWED -> Gate.NONE;
            case SIGN_IN -> Gate.SIGN_IN;
            case CHECKING -> Gate.CHECKING;
            case NEEDS_PLAN -> Gate.NEEDS_PLAN;
            case CHECK_AGAIN -> Gate.CHECK_AGAIN;
        };
    }

    /** {@link #gate()} for people (one or two sentences); null when nothing blocks */
    public String gateMessage() {
        return gateMessage(gate());
    }

    public String gateMessage(Gate g) {
        return switch (g) {
            case NONE -> null;
            case PAUSED -> pausedMessage;
            case SIGN_IN -> "Plan files need an MC Planner plan. Sign in with your Lab43 account so the mod can check yours.";
            case CHECKING -> accessError != null ? "Couldn't check your MC Planner plan: " + accessError : "Checking your MC Planner plan…";
            case NEEDS_PLAN -> "Plan files need your own MC Planner plan: Cloud, Team, Lifetime or the free 7-day trial.";
            case CHECK_AGAIN -> {
                Access a = access();
                long now = System.currentTimeMillis();
                if (a != null && a.expiresAt() > 0 && now >= a.expiresAt()) yield "Your MC Planner plan has reached its end date. Checking whether it goes on…";
                yield "Your MC Planner plan was last checked " + Texts.ago(accessCheckedAt()) + ". Connect to the internet so the mod can check it again.";
            }
        };
    }

    /** {@link #gate()} in a word or two (HUD) */
    public String gateLabel() {
        return switch (gate()) {
            case NONE -> null;
            case PAUSED -> "paused";
            case SIGN_IN -> "sign in";
            case CHECKING -> "checking…";
            case NEEDS_PLAN -> "needs a plan";
            case CHECK_AGAIN -> "check needed";
        };
    }

    // ------------------------------------------------------------------ shared worlds

    public List<WorldInfo> worlds() {
        return worlds;
    }

    public boolean worldsLoading() {
        return worldsLoading;
    }

    public String worldsError() {
        return worldsError;
    }

    public void loadWorlds(boolean force) {
        if (!isSignedIn() || worldsLoading) return;
        if (!force && worldsAt > 0 && System.currentTimeMillis() - worldsAt < 30_000) return;
        worldsLoading = true;
        api.worlds().whenComplete((list, t) -> main.execute(() -> {
            worldsLoading = false;
            if (list != null) {
                worlds = List.copyOf(list);
                worldsError = null;
                worldsAt = System.currentTimeMillis();
                if (source == Source.NONE) autoSelect();
            } else {
                ApiException e = ApiException.of(t);
                if (e.isUnauthorized()) signedOutRemotely();
                else worldsError = e.getMessage();
            }
        }));
    }

    // ------------------------------------------------------------------ which plan

    public Source source() {
        return source;
    }

    public String sourceName() {
        return sourceName;
    }

    public String roomId() {
        return roomId;
    }

    public Path file() {
        return file;
    }

    public void selectRoom(WorldInfo w) {
        if (source == Source.ROOM && w.roomId().equals(roomId)) {
            sync.select(roomId); // already shown: refresh only (keeps the plan, or the pause)
            return;
        }
        stopWatch();
        source = Source.ROOM;
        roomId = w.roomId();
        file = null;
        fileLoaded = false;
        // the list says it's paused: say so at once and don't draw a saved copy meanwhile; the server has the last word
        pausedMessage = w.paused() ? pausedText(w) : null;
        sourceName = w.name();
        plan = Plan.empty();
        meta = null;
        error = null;
        planVersion++;
        sync.select(roomId);
        sync.setActive(shouldSync());
        remember(ModConfig.roomRef(roomId));
    }

    /**
     * Show a plan file. It's remembered for this world either way; it only loads while the player's own plan allows
     * it ({@link #filesAccess()}) — otherwise {@link #gate()} says why, and it loads by itself once the check says yes.
     */
    public void selectFile(Path path) {
        stopWatch();
        sync.clear();
        source = Source.FILE;
        roomId = null;
        file = path.toAbsolutePath();
        sourceName = fileTitle(file);
        plan = Plan.empty();
        meta = null;
        error = null;
        fileLoaded = false;
        pausedMessage = null;
        planVersion++;
        remember(ModConfig.fileRef(file));
        expectPlanSoon();
        if (filesAccess() == AccessGate.State.ALLOWED) loadFile(true);
        fetchMe(); // opening a file checks the plan again (does nothing when not signed in or already asking)
        watch = new LocalPlans.FileWatch(file);
        Path watched = file;
        watch.start(exec, () -> main.execute(() -> {
            if (source == Source.FILE && watched.equals(file)) loadFile(false);
        }));
    }

    private void loadFile(boolean announce) {
        if (filesAccess() != AccessGate.State.ALLOWED) return;
        Path p = file;
        exec.execute(() -> {
            try {
                Plan loaded = LocalPlans.load(p);
                main.execute(() -> {
                    if (source != Source.FILE || !p.equals(file)) return;
                    if (filesAccess() != AccessGate.State.ALLOWED) return; // the plan ran out meanwhile
                    fileLoaded = true;
                    plan = loaded;
                    planAt = System.currentTimeMillis();
                    error = null;
                    if (loaded.worldName != null && !loaded.worldName.isBlank()) sourceName = loaded.worldName;
                    planVersion++;
                    toast(announce ? "Plan loaded" : "Plan updated", sourceName + " · " + countLabel(loaded));
                });
            } catch (Exception e) {
                main.execute(() -> {
                    if (source != Source.FILE || !p.equals(file)) return;
                    error = e.getMessage() == null ? "Couldn't read " + p.getFileName() : e.getMessage();
                });
            }
        });
    }

    /** Stop showing a plan (and forget the choice for this world unless {@code keepChoice}). */
    public void clearSource(boolean forgetChoice) {
        stopWatch();
        sync.clear();
        source = Source.NONE;
        roomId = null;
        file = null;
        sourceName = null;
        plan = Plan.empty();
        meta = null;
        error = null;
        navId = null;
        fileLoaded = false;
        pausedMessage = null;
        planVersion++;
        if (forgetChoice) remember(null);
    }

    private void stopWatch() {
        if (watch != null) watch.stop();
        watch = null;
    }

    public void refresh() {
        schematics.rescan();
        if (source == Source.ROOM) sync.refreshNow();
        else if (source == Source.FILE) {
            if (filesAccess() == AccessGate.State.ALLOWED) loadFile(false);
            else fetchMe();
        }
    }

    private void remember(String ref) {
        if (context == null) return;
        config.setWorldFor(context, ref);
        saveConfig();
    }

    // ------------------------------------------------------------------ the plan

    public Plan plan() {
        return plan;
    }

    public int planVersion() {
        return planVersion;
    }

    public String error() {
        return error;
    }

    public long planAt() {
        return planAt;
    }

    public boolean isOffline() {
        return meta != null && meta.fromCache();
    }

    public String role() {
        return meta == null ? null : meta.role();
    }

    /** the shown shared world is paused: its owner's plan ended (nothing is shown then) */
    public boolean isPaused() {
        return pausedMessage != null || (meta != null && meta.paused());
    }

    /** why a world is paused, before the server says it in its own words */
    private static String pausedText(WorldInfo w) {
        return w != null && "owner".equals(w.role())
                ? "This world is paused: it isn't one of your live worlds (your plan ended, or it keeps fewer worlds live)."
                : "This world is paused: its owner's MC Planner plan doesn't keep it live right now.";
    }

    /** the shown world's entry in the list of shared worlds, or null */
    public WorldInfo world() {
        if (source != Source.ROOM || roomId == null) return null;
        for (WorldInfo w : worlds) if (roomId.equals(w.roomId())) return w;
        return null;
    }

    /** May this player change status and add markers in the shown plan? */
    public boolean canEdit() {
        return source == Source.ROOM && isSignedIn() && meta != null && meta.canEdit() && canWriteScope();
    }

    public String whyReadOnly() {
        if (source == Source.FILE) return "Plan files are read-only in Minecraft. Change them in MC Planner.";
        if (source != Source.ROOM) return "No plan loaded.";
        if (!isSignedIn()) return "Sign in to change the plan.";
        if (isPaused()) return pausedMessage != null ? pausedMessage : "This world is paused: its owner's MC Planner plan ended.";
        if (meta != null && "viewer".equals(meta.role())) return "You're a viewer in this world. You can only look.";
        if (!canWriteScope()) return "Sign in again to allow changes.";
        return null;
    }

    public String dimension() {
        return mc.level == null ? "minecraft:overworld" : mc.level.dimension().identifier().toString();
    }

    /** Objects of the current dimension (unfiltered). */
    public List<PlanObject> here() {
        return plan.inDimension(dimension());
    }

    public PlanObject object(String id) {
        return id == null ? null : plan.byId(id);
    }

    // ------------------------------------------------------------------ edits

    public void setStatus(PlanObject o, Status status, Consumer<String> done) {
        String why = whyReadOnly();
        if (why != null || busy) {
            if (done != null) done.accept(why != null ? why : "Please wait a moment.");
            return;
        }
        busy = true;
        api.setStatus(roomId, o.id, status).whenComplete((obj, t) -> main.execute(() -> {
            busy = false;
            if (obj != null) {
                sync.applyLocal(obj);
                toast("Status changed", obj.name + " → " + obj.status.label());
                if (done != null) done.accept(null);
            } else {
                ApiException e = ApiException.of(t);
                if (e.isUnauthorized()) signedOutRemotely();
                if (done != null) done.accept(e.getMessage());
            }
        }));
    }

    public void addMarker(String name, Consumer<String> done) {
        String why = whyReadOnly();
        if (why != null || busy || mc.player == null) {
            if (done != null) done.accept(why != null ? why : "Please wait a moment.");
            return;
        }
        busy = true;
        BlockPos p = mc.player.blockPosition();
        String n = name == null || name.isBlank() ? "Marker" : name.strip();
        MarkerRequest req = new MarkerRequest(n, p.getX(), p.getY(), p.getZ(), dimension(), null, "");
        api.addMarker(roomId, req).whenComplete((obj, t) -> main.execute(() -> {
            busy = false;
            if (obj != null) {
                sync.applyLocal(obj);
                toast("Marker added", obj.name + " at " + p.getX() + " " + p.getY() + " " + p.getZ());
                if (done != null) done.accept(null);
            } else {
                ApiException e = ApiException.of(t);
                if (e.isUnauthorized()) signedOutRemotely();
                if (done != null) done.accept(e.getMessage());
            }
        }));
    }

    // ------------------------------------------------------------------ navigation

    public PlanObject navTarget() {
        PlanObject o = object(navId);
        if (o == null && navId != null) navId = null;
        return o;
    }

    public void navigate(PlanObject o) {
        navId = o == null ? null : o.id;
    }

    // ------------------------------------------------------------------ the game

    /** A world or server was joined: pick the plan remembered for it, or one with the same name. */
    public void onJoin() {
        context = contextKey();
        selectRemembered();
        if (isSignedIn()) {
            fetchMe();
            loadWorlds(true);
        }
    }

    /** the plan chosen for this world / server last time, if it can be shown */
    private void selectRemembered() {
        String ref = context == null ? null : config.worldFor(context);
        String room = ModConfig.refRoomId(ref);
        Path f = ModConfig.refFile(ref);
        if (room != null && isSignedIn()) {
            selectRoom(new WorldInfo(room, nameFromCacheOr(room, "Shared world"), null, false, false, null, 0));
        } else if (f != null && Files.isRegularFile(f)) {
            selectFile(f);
        }
    }

    public void onLeave() {
        clearSource(false);
        context = null;
    }

    private String nameFromCacheOr(String room, String fallback) {
        PlanCache.Cached c = cache.load(room);
        if (c != null && c.response() != null && c.response().name() != null) return c.response().name();
        return fallback;
    }

    /** Nothing chosen for this world yet: a shared world with the same name as the save / server is taken. */
    private void autoSelect() {
        if (source != Source.NONE || context == null || config.worldFor(context) != null) return;
        String name = levelName();
        if (name == null) return;
        for (WorldInfo w : worlds) {
            if (w.name() != null && w.name().trim().equalsIgnoreCase(name.trim())) {
                selectRoom(w);
                return;
            }
        }
    }

    private String contextKey() {
        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) {
            // the save's folder: many saves share the display name "New World"
            Path root = sp.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
            Path folder = root.getFileName();
            return ModConfig.singleplayerContext(folder != null ? folder.toString() : sp.getWorldData().getLevelName());
        }
        ServerData sd = mc.getCurrentServer();
        if (sd != null) return ModConfig.serverContext(sd.ip);
        return null;
    }

    private String levelName() {
        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) return sp.getWorldData().getLevelName();
        ServerData sd = mc.getCurrentServer();
        return sd == null ? null : sd.name;
    }

    public boolean inWorld() {
        return mc.level != null && mc.player != null;
    }

    private boolean shouldSync() {
        var screen = mc.gui.screen();
        boolean ours = screen != null && screen.getClass().getName().startsWith("dev.lab43.mcplanner.");
        return ours || (inWorld() && (config.overlay || config.hud || screen != null));
    }

    /** Every client tick. */
    public void tick() {
        if (inWorld()) {
            schematics.tick();
            // the player's own plan: every 30 minutes, every minute while a plan file waits for one
            long now = System.currentTimeMillis();
            boolean waiting = source == Source.FILE && now < accessWaitUntil && filesAccess() != AccessGate.State.ALLOWED;
            if (isSignedIn() && !accessChecking && AccessGate.recheckDue(accessAttemptAt, waiting, now)) fetchMe();
            // offline grace or the plan's end ran out while playing
            if (source == Source.FILE && fileLoaded && filesAccess() != AccessGate.State.ALLOWED) accessChanged();
        }
        if (source == Source.ROOM) {
            boolean want = shouldSync();
            if (sync.isActive() != want) sync.setActive(want);
        }
    }

    public void shutdown() {
        cancelSignIn();
        stopWatch();
        sync.clear();
        exec.shutdownNow();
    }

    // ------------------------------------------------------------------ helpers

    public void toast(String title, String body) {
        try {
            net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.gui.toastManager(),
                    McPlannerClient.TOAST, Component.literal(title), body == null ? null : Component.literal(body));
        } catch (Exception e) {
            LOG.info("{}: {}", title, body);
        }
    }

    public static String countLabel(Plan p) {
        int n = p.size();
        return n == 1 ? "1 object" : n + " objects";
    }

    private static String fileTitle(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "Windows";
        if (os.contains("mac")) return "macOS";
        if (os.contains("linux")) return "Linux";
        return "PC";
    }

    public static String kindLabel(Kind k) {
        return k.label();
    }
}
