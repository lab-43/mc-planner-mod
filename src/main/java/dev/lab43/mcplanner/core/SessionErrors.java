package dev.lab43.mcplanner.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Errors the mod caught in its own code this session (drawing, textures, looking for schematic files), kept in memory
 * for a crash report and redacted as they come in. The same error again only counts up. Never written anywhere.
 */
public final class SessionErrors {
    public static final int MAX_ENTRIES = 20;
    static final int MAX_FRAMES = 12;
    static final int MAX_TEXT = 3000;

    /** {@code where}: the part of the mod ("overlay", "textures", …); {@code text}: the redacted exception and its frames */
    public record Entry(String where, long firstAt, long lastAt, int count, String text) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private volatile Redactor redactor = Redactor.basic();

    /** Once the player's name and the home folder are known (they're taken out of what's noted from then on). */
    public void setRedactor(Redactor r) {
        if (r != null) redactor = r;
    }

    /** Notes a caught error. Never throws. */
    public void note(String where, Throwable t) {
        if (t == null) return;
        try {
            String text = redactor.apply(describe(t, MAX_FRAMES));
            if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT - 1) + "…";
            String key = where + "\n" + firstLines(text, 2);
            long now = System.currentTimeMillis();
            synchronized (this) {
                Entry old = entries.remove(key);
                if (old != null) entries.put(key, new Entry(old.where(), old.firstAt(), now, old.count() + 1, old.text()));
                else {
                    if (entries.size() >= MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
                    entries.put(key, new Entry(where, now, now, 1, text));
                }
            }
        } catch (RuntimeException ignored) {
            // a crash report helper must never break the game
        }
    }

    /** Oldest first. */
    public synchronized List<Entry> entries() {
        return List.copyOf(new ArrayList<>(entries.values()));
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    /** After a report with them was sent or declined. */
    public synchronized void clear() {
        entries.clear();
    }

    /** "java.lang.IllegalStateException: message" + up to {@code maxFrames} "\tat …" lines, then its causes. */
    static String describe(Throwable t, int maxFrames) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        for (int depth = 0; c != null && depth < 4; depth++, c = c.getCause() == c ? null : c.getCause()) {
            if (depth > 0) sb.append("\nCaused by: ");
            sb.append(c);
            StackTraceElement[] frames = c.getStackTrace();
            int n = Math.min(frames.length, depth == 0 ? maxFrames : Math.max(3, maxFrames / 3));
            for (int i = 0; i < n; i++) sb.append("\n\tat ").append(frames[i]);
            if (frames.length > n) sb.append("\n\t… ").append(frames.length - n).append(" more");
        }
        return sb.toString();
    }

    private static String firstLines(String s, int n) {
        int at = -1;
        for (int i = 0; i < n; i++) {
            int next = s.indexOf('\n', at + 1);
            if (next < 0) return s;
            at = next;
        }
        return s.substring(0, at);
    }
}
