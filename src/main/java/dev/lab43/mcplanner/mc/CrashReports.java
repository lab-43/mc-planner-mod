package dev.lab43.mcplanner.mc;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.lab43.mcplanner.core.CrashFiles;
import dev.lab43.mcplanner.core.CrashReport;
import dev.lab43.mcplanner.core.CrashSender;
import dev.lab43.mcplanner.core.KnownNames;
import dev.lab43.mcplanner.core.Redactor;
import dev.lab43.mcplanner.core.SessionErrors;

import net.minecraft.SharedConstants;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The menu's "Crash report": a Minecraft crash with the mod in it (looked for when the game starts), or errors the mod
 * caught this session. The report is built and redacted in core ({@link CrashReport}, {@link Redactor}); it's sent
 * only when the person presses Send ({@link CrashSender}), once.
 */
public final class CrashReports {
    /** older crashes aren't offered (a pile of old crash reports isn't news) */
    private static final long MAX_AGE_MS = 14L * 24 * 3600 * 1000;

    private final Planner planner;
    private final long startedAt = System.currentTimeMillis();
    /** the crash to offer (null: none); only touched on the game's thread */
    private CrashFiles.Found crash;

    CrashReports(Planner planner) {
        this.planner = planner;
    }

    /** When the game has started: the player's name and the home folder leave noted errors, and a new crash is looked for. */
    void start() {
        planner.errors.setRedactor(baseNames().build());
        long after = Math.max(planner.config.crashReportSeen, System.currentTimeMillis() - MAX_AGE_MS);
        Path dir = planner.gameDir.resolve("crash-reports");
        planner.exec.execute(() -> {
            CrashFiles.Found f = CrashFiles.newest(dir, after);
            if (f != null) planner.main.execute(() -> crash = f);
        });
    }

    /** There's a report to offer. */
    public boolean available() {
        return crash != null || !planner.errors.isEmpty();
    }

    /** It's a crash of Minecraft (not only errors the mod caught). */
    public boolean isCrash() {
        return crash != null;
    }

    /** The redacted report. Starts on the game's thread (versions, GPU, the world you're in), reads files in the background. */
    public CompletableFuture<CrashReport> prepare() {
        CrashFiles.Found f = crash;
        List<SessionErrors.Entry> errors = planner.errors.entries();
        CrashReport.Facts facts = facts();
        Redactor.Builder names = baseNames();
        planner.addKnownNames(names);
        Path gameDir = planner.gameDir;
        return CompletableFuture.supplyAsync(() -> {
            names.names(KnownNames.saves(gameDir.resolve("saves")), "<world>");
            names.names(KnownNames.servers(gameDir.resolve("servers.dat")), "<server>");
            Redactor r = names.build();
            List<String> log = CrashFiles.logLines(gameDir.resolve("logs"), f == null ? null : f.fileName(), CrashFiles.MAX_LOG_LINES);
            return f != null ? CrashReport.ofCrash(f.fileName(), f.text(), errors, log, facts, r) : CrashReport.ofErrors(errors, log, facts, r);
        }, planner.exec);
    }

    /** "Send to Lab43": one request to the Lab43 account site, nothing else. */
    public CompletableFuture<CrashSender.Result> send(CrashReport report, String note) {
        return new CrashSender(planner.config.hubUrl, planner.userAgent).send(report.body(note));
    }

    /** Sent, or "Don't send": this crash and these errors aren't offered again. */
    public void done() {
        if (crash != null) {
            planner.config.crashReportSeen = Math.max(planner.config.crashReportSeen, crash.modified());
            planner.saveConfig();
        }
        crash = null;
        planner.errors.clear();
    }

    private Redactor.Builder baseNames() {
        Redactor.Builder b = Redactor.builder().home(System.getProperty("user.home"));
        try {
            Object user = planner.mc.getUser();
            b.player(planner.mc.getUser().getName());
            // the UUID by name too (any UUID is taken out by its shape anyway)
            if (user.getClass().getMethod("getProfileId").invoke(user) instanceof UUID id) b.uuid(id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Planner.LOG.debug("MC Planner: no player id for crash reports", e);
        }
        return b;
    }

    private CrashReport.Facts facts() {
        String java = System.getProperty("java.version", "?") + " (" + System.getProperty("java.vendor", "?") + ")";
        String os = (System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "") + " " + System.getProperty("os.arch", "")).strip();
        return new CrashReport.Facts(McPlannerClient.version(), SharedConstants.getCurrentVersion().name(), modVersion("fabricloader", "?"),
                modVersion("fabric-api", null), java, os, gpu(), (System.currentTimeMillis() - startedAt) / 1000, planner.config.reportSettings());
    }

    private static String modVersion(String id, String fallback) {
        return FabricLoader.getInstance().getModContainer(id).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(fallback);
    }

    /**
     * The GPU's renderer string, when this game version tells it. Read by name, because the render API changes between
     * versions (26.x: RenderSystem.getDevice().getRenderer(); older: GlUtil.getRenderer()). Render thread only.
     */
    private static String gpu() {
        try {
            Object device = Class.forName("com.mojang.blaze3d.systems.RenderSystem").getMethod("getDevice").invoke(null);
            if (device != null) {
                Method m;
                try {
                    m = Class.forName("com.mojang.blaze3d.systems.GpuDevice").getMethod("getRenderer");
                } catch (ReflectiveOperationException e) {
                    m = device.getClass().getMethod("getRenderer");
                }
                if (m.invoke(device) instanceof String s && !s.isBlank()) return s;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // not this version's API
        }
        try {
            if (Class.forName("com.mojang.blaze3d.platform.GlUtil").getMethod("getRenderer").invoke(null) instanceof String s && !s.isBlank()) return s;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // not this version's API
        }
        return null;
    }
}
