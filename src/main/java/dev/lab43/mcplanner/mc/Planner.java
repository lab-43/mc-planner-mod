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
import dev.lab43.mcplanner.core.Redactor;
import dev.lab43.mcplanner.core.SessionErrors;
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
 * <p>The mod never checks anyone's MC Planner plan (Mojang's usage guidelines: a mod can't check whether a player has
 * an out-of-game product or service that changes what it does in the game; docs/PLAN-mod-launch.md). Plan files open
 * for everybody, without an account; a shared world shows for its members. A paused world (its owner's plan doesn't
 * keep it live) shows read-only, as in the app.
 *
 * <p>All public methods are called on the game's main thread; network work runs on the core library's threads and
 * comes back through {@link #main}.
 */
public final class Planner {
    public static final Logger LOG = LoggerFactory.getLogger("MC Planner");
    private static Planner instance;

    public enum Source { NONE, ROOM, FILE }

    public final Minecraft mc;
    public final Path gameDir;
    public final Path configFile;
    public final Executor main;
    public final ScheduledExecutorService exec;
    public final AuthStore authStore;
    /** the plan's schematic files found on this computer */
    public final Schematics schematics;
    public final PlanCache cache;
    public final String userAgent;
    /** errors the mod caught in its own code this session (redacted, memory only) — for a crash report */
    public final SessionErrors errors = new SessionErrors();
    /** the menu's "Crash report" (sent only when the person presses Send) */
    public final CrashReports crashReports;

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
    private boolean meLoading;

    // which plan
    private Source source = Source.NONE;
    private String roomId;
    private Path file;
    private LocalPlans.FileWatch watch;
    private String sourceName;
    /** a server from before paused worlds were shown read-only refused the chosen world (402): its message */
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
        forgetOldPlanCheck(authStore);
        this.cache = new PlanCache(gameDir);
        this.schematics = new Schematics(this);
        this.userAgent = PlannerClient.userAgent(McPlannerClient.version(), SharedConstants.getCurrentVersion().name());
        this.config = ModConfig.load(configFile);
        rebuildClients();
        this.account = authStore.load(config.hubUrl);
        this.crashReports = new CrashReports(this);
        crashReports.start();
    }

    public static Planner init(Minecraft mc) {
        if (instance == null) instance = new Planner(mc);
        return instance;
    }

    public static Planner get() {
        return instance;
    }

    /** mod 0.2 kept the last check of the player's plan next to the token: no longer needed, so it's deleted */
    private static void forgetOldPlanCheck(AuthStore auth) {
        try {
            Files.deleteIfExists(auth.primaryFile().resolveSibling("mcplanner-access.json"));
        } catch (Exception e) {
            LOG.debug("Could not delete the old plan check", e);
        }
    }

    /** An error caught in the mod's own code: kept (redacted) for a crash report. Never throws. */
    public static void noteError(String where, Throwable t) {
        Planner p = instance;
        if (p != null) p.errors.note(where, t);
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
                if (announce) toast("MC Planner", "This world is paused in MC Planner.");
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
            fetchMe();
            loadWorlds(true);
            if (source == Source.ROOM) sync.refreshNow();
            else if (source == Source.NONE) selectRemembered();
        }
        for (Consumer<DeviceLogin.State> l : List.copyOf(loginListeners)) l.accept(s);
    }

    /** /api/mod/me: the account's name and what the device may do */
    private void fetchMe() {
        if (!isSignedIn() || meLoading) return;
        meLoading = true;
        AuthStore.Entry asked = account;
        api.me().whenComplete((m, t) -> main.execute(() -> {
            meLoading = false;
            if (account != asked) return; // signed out (or in again) meanwhile
            if (m != null) me = m;
            else if (ApiException.of(t).isUnauthorized()) signedOutRemotely();
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
        account = null;
        me = null;
        worlds = List.of();
        worldsAt = 0;
        if (source == Source.ROOM) clearSource(false);
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
            sync.select(roomId); // already shown: refresh only
            return;
        }
        stopWatch();
        source = Source.ROOM;
        roomId = w.roomId();
        file = null;
        pausedMessage = null;
        sourceName = w.name();
        plan = Plan.empty();
        meta = null;
        error = null;
        planVersion++;
        sync.select(roomId);
        sync.setActive(shouldSync());
        remember(ModConfig.roomRef(roomId));
    }

    /** Show a plan file (remembered for this world). It is read again whenever it changes on disk. */
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
        pausedMessage = null;
        planVersion++;
        remember(ModConfig.fileRef(file));
        loadFile(true);
        watch = new LocalPlans.FileWatch(file);
        Path watched = file;
        watch.start(exec, () -> main.execute(() -> {
            if (source == Source.FILE && watched.equals(file)) loadFile(false);
        }));
    }

    private void loadFile(boolean announce) {
        Path p = file;
        exec.execute(() -> {
            try {
                Plan loaded = LocalPlans.load(p);
                main.execute(() -> {
                    if (source != Source.FILE || !p.equals(file)) return;
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
        else if (source == Source.FILE) loadFile(false);
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

    /** the shown shared world is paused: its owner's plan doesn't keep it live (read-only, as in the app) */
    public boolean isPaused() {
        return pausedMessage != null || (meta != null && meta.paused());
    }

    /** why a world is paused, in words for the player */
    private static String pausedText(WorldInfo w) {
        return w != null && "owner".equals(w.role())
                ? "This world is paused in MC Planner, so it's read-only. Make it live again in the app."
                : "This world is paused in MC Planner, so it's read-only.";
    }

    /** a paused world's notice (null when the shown world isn't paused) */
    public String pausedNotice() {
        if (!isPaused()) return null;
        return pausedMessage != null ? pausedMessage : pausedText(world());
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
        if (isPaused()) return pausedNotice();
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

    /** Names a crash report leaves out: this world or server, the plan, its objects and file, the Lab43 account. */
    void addKnownNames(Redactor.Builder b) {
        b.world(levelName());
        ServerData sd = mc.getCurrentServer();
        if (sd != null) b.server(sd.name).server(sd.ip);
        if (context != null && context.startsWith("sp:")) b.world(context.substring(3));
        if (context != null && context.startsWith("mp:")) b.server(context.substring(3));
        if (isSignedIn()) b.name(accountName());
        b.name(sourceName);
        if (file != null) b.name(file.getFileName().toString()).name(fileTitle(file));
        b.name(plan.worldName);
        for (PlanObject o : plan.objects()) b.name(o.name).name(o.groupName).name(o.author).name(o.sourceUrl);
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
        if (inWorld()) schematics.tick();
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
