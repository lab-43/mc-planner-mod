package dev.lab43.mcplanner.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Minecraft's crash reports and logs, read (never changed) for the mod's "Crash report" screen:
 * <ul>
 *   <li>{@link #newest}: the newest {@code crash-reports/crash-*.txt} written after the last one the mod offered that
 *       mentions the mod (its package or mod id in the crash, not just in the list of installed mods);</li>
 *   <li>{@link #logLines}: the last lines about the mod from the log of that session (Minecraft starts a new
 *       {@code latest.log} each time, so a crash from last time is in the newest {@code logs/*.log.gz}).</li>
 * </ul>
 */
public final class CrashFiles {
    private CrashFiles() {}

    /** the newest crash files looked at */
    static final int MAX_CRASH_FILES = 20;
    static final long MAX_CRASH_BYTES = 4L * 1024 * 1024;
    /** latest.log and the newest rotated logs looked at for a crash's session */
    static final int MAX_OLD_LOGS = 5;
    static final long MAX_LOG_BYTES = 32L * 1024 * 1024;
    public static final int MAX_LOG_LINES = 200;
    static final int MAX_LINE = 300;

    /** what in a crash report says the mod was involved (looked for above "-- System Details --") */
    private static final Pattern MENTIONS = Pattern.compile(
        "\\bdev\\.lab43\\.mcplanner\\b|\\bmcplanner\\.mixins\\.json\\b|provided by '?mcplanner'?|Suspected Mods?:[^\\n]*\\bmcplanner\\b"
            + "|\\[mcplanner-[0-9][^\\s:\\]]*\\.jar");
    /** a log line about the mod */
    private static final Pattern ABOUT_MOD = Pattern.compile("(?i)\\bMC Planner\\b|\\bmcplanner\\b|dev\\.lab43\\.");
    /** a log line that starts a new entry: "[12:34:56] [Render thread/INFO]: …" */
    private static final Pattern LOG_ENTRY = Pattern.compile("^\\[[0-9:.]+\\]|^\\[\\d{2}[A-Za-z]{3}\\d{4}");

    /** A crash report: the file, when it was written (ms), its text. */
    public record Found(Path file, long modified, String text) {
        public String fileName() {
            return file.getFileName().toString();
        }
    }

    /**
     * The newest crash report written after {@code after} (epoch ms) that mentions the mod, or null. A missing folder,
     * unreadable or huge files are skipped.
     */
    public static Found newest(Path crashDir, long after) {
        if (crashDir == null || !Files.isDirectory(crashDir)) return null;
        List<Found> files = new ArrayList<>(); // text filled in below
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(crashDir, "crash-*.txt")) {
            for (Path p : ds) if (Files.isRegularFile(p)) files.add(new Found(p, modified(p), null));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        files.sort(Comparator.comparingLong(Found::modified).thenComparing(Found::fileName).reversed());
        int looked = 0;
        for (Found f : files) {
            Path p = f.file();
            long m = f.modified();
            if (m <= after || looked++ >= MAX_CRASH_FILES) break;
            try {
                if (Files.size(p) > MAX_CRASH_BYTES) continue;
                String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                if (mentionsMod(text)) return new Found(p, m, text);
            } catch (IOException | RuntimeException ignored) {
                // gone or unreadable: the next one
            }
        }
        return null;
    }

    static long modified(Path p) {
        try {
            FileTime t = Files.getLastModifiedTime(p);
            return t.toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** The mod is in the crash itself: a stack frame, its mixins, a loader message, a "suspected mods" line. */
    public static boolean mentionsMod(String crashText) {
        if (crashText == null) return false;
        int details = crashText.indexOf("-- System Details --");
        String head = details >= 0 ? crashText.substring(0, details) : crashText;
        return MENTIONS.matcher(head).find();
    }

    /**
     * The lines that mention the mod, each with the lines that belong to it (the stack trace below a warning), and the
     * mod's frames in other entries' stack traces; never chat. The last {@code max}, each at most 300 characters.
     */
    public static List<String> modLines(List<String> lines, int max) {
        List<String> out = new ArrayList<>();
        boolean inEntry = false;
        for (String raw : lines) {
            boolean starts = LOG_ENTRY.matcher(raw).find();
            boolean about = ABOUT_MOD.matcher(raw).find() && !raw.contains("[CHAT]");
            if (starts) inEntry = about;
            if (inEntry || (about && !starts)) out.add(raw.length() > MAX_LINE ? raw.substring(0, MAX_LINE - 1) + "…" : raw);
        }
        return out.size() <= max ? out : new ArrayList<>(out.subList(out.size() - max, out.size()));
    }

    /**
     * The lines about the mod from the log that belongs to a crash: the log (latest.log or a rotated *.log.gz, newest
     * first) that names the crash file; with {@code crashFileName == null}, this session's latest.log. Empty when none.
     */
    public static List<String> logLines(Path logsDir, String crashFileName, int max) {
        if (logsDir == null || !Files.isDirectory(logsDir)) return List.of();
        Path latest = logsDir.resolve("latest.log");
        if (crashFileName == null) return Files.isRegularFile(latest) ? modLines(readLines(latest), max) : List.of();
        List<Path> candidates = new ArrayList<>();
        if (Files.isRegularFile(latest)) candidates.add(latest);
        List<Path> old = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(logsDir, "*.log.gz")) {
            for (Path p : ds) if (Files.isRegularFile(p)) old.add(p);
        } catch (IOException | RuntimeException ignored) {
            // no old logs
        }
        old.sort(Comparator.comparingLong(CrashFiles::modified).reversed());
        candidates.addAll(old.subList(0, Math.min(MAX_OLD_LOGS, old.size())));
        for (Path p : candidates) {
            List<String> lines = readLines(p);
            for (String l : lines) {
                if (l.contains(crashFileName)) return modLines(lines, max);
            }
        }
        return List.of();
    }

    /** A log's lines (.gz unpacked), at most 32 MB of text; empty when unreadable. */
    static List<String> readLines(Path p) {
        boolean gz = p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz");
        try (InputStream raw = Files.newInputStream(p); InputStream in = gz ? new GZIPInputStream(raw) : raw) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                int keep = (int) Math.min(n, MAX_LOG_BYTES - total);
                out.write(buf, 0, keep);
                total += keep;
                if (total >= MAX_LOG_BYTES) break;
            }
            String text = out.toString(StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            Collections.addAll(lines, text.split("\\r?\\n", -1));
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
            return lines;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** The part of a crash report from its exception to the end of its stack trace (for {@code details.stack}). */
    public static String stackOf(String crashText) {
        if (crashText == null) return "";
        String[] lines = crashText.split("\\r?\\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("Description:")) {
                for (int j = i + 1; j < lines.length; j++) {
                    if (!lines[j].isBlank()) {
                        start = j;
                        break;
                    }
                }
                break;
            }
        }
        if (start < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (i > start && lines[i].isBlank()) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
