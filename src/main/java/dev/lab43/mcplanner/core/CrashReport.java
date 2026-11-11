package dev.lab43.mcplanner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A crash report for Lab43 (auth.lab43.dev's crash-report API): the text the person reads on the mod's "Crash report"
 * screen, all of it redacted, and the request "Send" posts. Two kinds: Minecraft crashed with the mod in the crash
 * ({@link #ofCrash}), or the mod caught errors this session ({@link #ofErrors}).
 */
public final class CrashReport {
    static final int MAX_TITLE = 300;
    static final int MAX_FINGERPRINT = 500;
    static final int MAX_APP_VERSION = 60;
    static final int MAX_PLATFORM = 200;
    static final int MAX_REPORT = 200_000;
    public static final int MAX_NOTE = 2000;
    /** Minecraft's crash text in the report: its start and its end when it's longer */
    static final int CRASH_HEAD = 110_000;
    static final int CRASH_TAIL = 40_000;
    static final int MAX_STACK = 20_000;
    /** details as JSON, below the request's 100 KB */
    static final int MAX_DETAILS_BYTES = 95_000;
    /** the whole request, below the site's 400 KB: room is kept for the note (2,000 characters, up to 4 bytes each) */
    static final int MAX_BODY_BYTES = 400_000;
    static final int NOTE_ROOM_BYTES = 9_000;

    public enum Kind {
        CRASH, ERROR;

        String wire() {
            return this == CRASH ? "crash" : "error";
        }
    }

    /**
     * What the report says about this computer and the mod. {@code settings}: from {@link ModConfig#reportSettings()};
     * {@code gpu} may be null.
     */
    public record Facts(String modVersion, String minecraft, String loader, String fabricApi, String java, String os, String gpu,
                        long uptimeS, Map<String, String> settings) {}

    private final Kind kind;
    private final String title;
    private final String fingerprint;
    private final String appVersion;
    private final String platform;
    private final String text;
    private final JsonObject details;

    private CrashReport(Kind kind, String title, String fingerprint, String appVersion, String platform, String text, JsonObject details) {
        this.kind = kind;
        this.title = title;
        this.fingerprint = fingerprint;
        this.appVersion = appVersion;
        this.platform = platform;
        this.text = text;
        this.details = details;
    }

    public Kind kind() {
        return kind;
    }

    public String title() {
        return title;
    }

    public String fingerprint() {
        return fingerprint;
    }

    /** the whole report as the person sees it, and as it's sent */
    public String text() {
        return text;
    }

    public JsonObject details() {
        return details.deepCopy();
    }

    /** Minecraft crashed: its crash report (with the mod in it), the mod's errors this session, the log lines about the mod. */
    public static CrashReport ofCrash(String crashFileName, String crashText, List<SessionErrors.Entry> errors, List<String> log, Facts f, Redactor r) {
        // only the start and the end of a very long crash report, cut at whole lines before redacting (bounded work)
        String crash = r.apply(window(crashText == null ? "" : crashText, CRASH_HEAD, CRASH_TAIL));
        String stack = cut(CrashFiles.stackOf(crash), MAX_STACK);
        String title = titleOf(crash);
        return build(Kind.CRASH, "Minecraft crashed: " + crashFileName, title, fingerprintOf(stack), crash, stack, crashFileName, errors, log, f, r);
    }

    /** The mod caught errors this session (the game went on). */
    public static CrashReport ofErrors(List<SessionErrors.Entry> errors, List<String> log, Facts f, Redactor r) {
        String first = errors.isEmpty() ? "" : r.apply(errors.get(0).text());
        String firstLine = first.split("\n", 2)[0].strip();
        String title = cut(firstLine.isEmpty() ? "MC Planner mod error" : firstLine, MAX_TITLE);
        String what = errors.size() == 1 ? "the mod caught an error this session" : "the mod caught " + errors.size() + " errors this session";
        return build(Kind.ERROR, what, title, fingerprintOf(first), null, cut(first, MAX_STACK), null, errors, log, f, r);
    }

    private static CrashReport build(Kind kind, String what, String title, String fingerprint, String crash, String stack, String crashFile,
                                     List<SessionErrors.Entry> errors, List<String> log, Facts f, Redactor r) {
        List<String> logLines = r.apply(log);
        String loader = "Fabric Loader " + f.loader() + (f.fabricApi() != null ? " · Fabric API " + f.fabricApi() : "");
        String gpu = f.gpu() == null || f.gpu().isBlank() ? null : r.apply(f.gpu());
        StringBuilder sb = new StringBuilder();
        sb.append("MC Planner mod crash report\n");
        line(sb, "What", what);
        line(sb, "Error", title);
        line(sb, "Fingerprint", fingerprint);
        sb.append('\n');
        line(sb, "Mod", "MC Planner " + f.modVersion());
        line(sb, "Minecraft", f.minecraft() + " · " + loader);
        line(sb, "Java", f.java());
        line(sb, "System", f.os());
        line(sb, "GPU", gpu);
        line(sb, "This session", "running for " + duration(f.uptimeS()));
        if (f.settings() != null && !f.settings().isEmpty()) {
            List<String> parts = new ArrayList<>();
            f.settings().forEach((k, v) -> parts.add(k + " " + v));
            line(sb, "Settings", String.join(" · ", parts));
        }
        sb.append("\nErrors the mod caught this session (").append(errors.size()).append("):\n");
        if (errors.isEmpty()) sb.append("(none)\n");
        DateTimeFormatter hms = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());
        for (SessionErrors.Entry e : errors) {
            sb.append('[').append(hms.format(Instant.ofEpochMilli(e.lastAt()))).append("] ").append(e.where());
            if (e.count() > 1) sb.append(" (").append(e.count()).append(" times)");
            sb.append(": ").append(r.apply(e.text())).append('\n');
        }
        if (crash != null) {
            sb.append("\nMinecraft's crash report:\n");
            sb.append(crash);
            if (!crash.endsWith("\n")) sb.append('\n');
        }
        sb.append("\nThe log's last lines about the mod (").append(logLines.size()).append("):\n");
        if (logLines.isEmpty()) sb.append("(none)\n");
        for (String l : logLines) sb.append(l).append('\n');
        String text = cut(sb.toString(), MAX_REPORT);

        JsonObject d = new JsonObject();
        d.addProperty("what", what);
        if (crashFile != null) d.addProperty("crashFile", crashFile);
        d.addProperty("mod", f.modVersion());
        d.addProperty("minecraft", f.minecraft());
        d.addProperty("loader", f.loader());
        if (f.fabricApi() != null) d.addProperty("fabricApi", f.fabricApi());
        d.addProperty("java", f.java());
        d.addProperty("os", f.os());
        if (gpu != null) d.addProperty("gpu", gpu);
        d.addProperty("uptimeS", f.uptimeS());
        JsonObject settings = new JsonObject();
        if (f.settings() != null) f.settings().forEach(settings::addProperty);
        d.add("settings", settings);
        d.addProperty("stack", stack == null ? "" : stack);
        JsonArray errs = new JsonArray();
        for (SessionErrors.Entry e : errors) {
            JsonObject o = new JsonObject();
            o.addProperty("where", e.where());
            o.addProperty("count", e.count());
            o.addProperty("text", cut(r.apply(e.text()), 1500));
            errs.add(o);
        }
        d.add("errors", errs);
        JsonArray logs = new JsonArray();
        for (String l : logLines) logs.add(l);
        d.add("log", logs);
        while (bytes(d) > MAX_DETAILS_BYTES && logs.size() > 0) logs.remove(0);
        if (bytes(d) > MAX_DETAILS_BYTES) d.addProperty("stack", cut(stack == null ? "" : stack, 4000));
        while (bytes(d) > MAX_DETAILS_BYTES && errs.size() > 0) errs.remove(errs.size() - 1);

        String platform = cut(f.os() + " · Java " + f.java() + " · Minecraft " + f.minecraft() + " · Fabric " + f.loader(), MAX_PLATFORM);
        CrashReport report = new CrashReport(kind, cut(title.isBlank() ? "MC Planner mod crash" : title, MAX_TITLE), fingerprint,
            cut(f.modVersion(), MAX_APP_VERSION), platform, text, d);
        // 200,000 characters of report can be more than 400 KB in UTF-8: the text is shortened here, before anyone reads
        // it, so the screen shows exactly what Send sends
        String fitted = text;
        while (bytes(report.with(fitted).body("")) + NOTE_ROOM_BYTES > MAX_BODY_BYTES && fitted.length() > 1000) {
            fitted = cut(fitted, fitted.length() * 9 / 10);
        }
        return fitted.equals(text) ? report : report.with(fitted);
    }

    private CrashReport with(String newText) {
        return new CrashReport(kind, title, fingerprint, appVersion, platform, newText, details);
    }

    /** The body of {@code POST /api/v1/crash-reports}; the note is what the person typed. */
    public JsonObject body(String note) {
        JsonObject b = new JsonObject();
        b.addProperty("app", "mcplanner");
        b.addProperty("component", "mod");
        b.addProperty("kind", kind.wire());
        b.addProperty("title", title);
        if (fingerprint != null) b.addProperty("fingerprint", fingerprint);
        b.addProperty("appVersion", appVersion);
        b.addProperty("platform", platform);
        b.addProperty("report", text);
        b.add("details", details.deepCopy());
        String n = note == null ? "" : note.strip();
        if (!n.isEmpty()) b.addProperty("note", n.length() > MAX_NOTE ? n.substring(0, MAX_NOTE) : n);
        return b;
    }

    // ------------------------------------------------------------------ helpers

    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+([^\\s(]+)\\(([^)]*)\\)", Pattern.MULTILINE);

    /** The crash's exception line ("java.lang.NullPointerException: …"), or its description. */
    static String titleOf(String crash) {
        String stack = CrashFiles.stackOf(crash);
        String first = stack.isEmpty() ? "" : stack.split("\n", 2)[0].strip();
        if (first.isEmpty()) {
            Matcher m = Pattern.compile("(?m)^Description:\\s*(.+)$").matcher(crash);
            first = m.find() ? "Minecraft crashed: " + m.group(1).strip() : "Minecraft crashed";
        }
        return cut(first, MAX_TITLE);
    }

    /** The top stack frame without line number or jar: "at dev.lab43.mcplanner.mc.render.Overlay.submit(Overlay.java)". */
    static String fingerprintOf(String stack) {
        if (stack == null) return null;
        Matcher m = FRAME.matcher(stack);
        if (!m.find()) return null;
        String where = m.group(2).replaceAll(":\\d+$", "");
        return cut("at " + m.group(1) + "(" + where + ")", MAX_FINGERPRINT);
    }

    /** The text, or its first {@code head} and last {@code tail} characters cut at line ends, with a note in between. */
    static String window(String s, int head, int tail) {
        if (s.length() <= head + tail) return s;
        int h = s.lastIndexOf('\n', head);
        if (h <= 0) h = head;
        int t = s.indexOf('\n', s.length() - tail);
        if (t < 0 || t <= h) t = s.length() - tail;
        return s.substring(0, h) + "\n[… " + (t - h) + " characters left out …]" + s.substring(t);
    }

    static String duration(long seconds) {
        long s = Math.max(0, seconds);
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min " + (s % 60) + " s";
        return (s / 3600) + " h " + ((s % 3600) / 60) + " min";
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) sb.append(label).append(": ").append(value).append('\n');
    }

    static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static int bytes(JsonObject o) {
        return Http.GSON.toJson(o).getBytes(StandardCharsets.UTF_8).length;
    }
}
