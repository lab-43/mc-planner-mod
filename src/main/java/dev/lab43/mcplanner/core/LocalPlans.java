package dev.lab43.mcplanner.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/** Plan files on this computer (saved by the web / desktop app): finding, loading and watching them. */
public final class LocalPlans {
    private static final Logger LOG = LoggerFactory.getLogger("MC Planner");

    /** Plan files larger than this are refused. */
    public static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    static final int MAX_FILES = 500;

    private LocalPlans() {}

    /**
     * Where plan files usually are: {@code <user.home>/Documents/MC Planner} and
     * {@code <gameDir>/mcplanner/plans} — only the ones that exist.
     */
    public static List<Path> defaultFolders(Path gameDir) {
        List<Path> out = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            Path docs = Paths.get(home, "Documents", "MC Planner");
            if (Files.isDirectory(docs)) out.add(docs);
        }
        if (gameDir != null) {
            Path plans = gameDir.resolve("mcplanner").resolve("plans");
            if (Files.isDirectory(plans)) out.add(plans);
        }
        return out;
    }

    /** {@code <gameDir>/mcplanner/plans} (may not exist yet). */
    public static Path gamePlansFolder(Path gameDir) {
        return gameDir.resolve("mcplanner").resolve("plans");
    }

    /**
     * All {@code *.mcplan} and {@code *.json} files in the folders (and their direct subfolders), newest first.
     * Missing / unreadable folders are skipped.
     */
    public static List<LocalPlanFile> scan(List<Path> folders) {
        List<LocalPlanFile> out = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Path folder : folders) {
            if (folder == null || !Files.isDirectory(folder)) continue;
            try (Stream<Path> s = Files.walk(folder, 2)) {
                s.filter(LocalPlans::isPlanFileName).forEach(p -> {
                    if (out.size() >= MAX_FILES) return;
                    try {
                        Path real = p.toAbsolutePath().normalize();
                        if (!seen.add(real)) return;
                        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                        if (!a.isRegularFile()) return;
                        out.add(new LocalPlanFile(real, displayName(p), a.lastModifiedTime().toMillis(), a.size()));
                    } catch (IOException e) {
                        LOG.debug("Skipping {}", p, e);
                    }
                });
            } catch (IOException | RuntimeException e) {
                LOG.debug("Could not scan {}", folder, e);
            }
        }
        out.sort(Comparator.comparingLong(LocalPlanFile::modified).reversed().thenComparing(f -> f.path().toString()));
        return out;
    }

    static boolean isPlanFileName(Path p) {
        Path name = p.getFileName();
        if (name == null) return false;
        String n = name.toString().toLowerCase(Locale.ROOT);
        return !n.startsWith(".") && (n.endsWith(".mcplan") || n.endsWith(".json"));
    }

    static String displayName(Path p) {
        String n = p.getFileName().toString();
        String l = n.toLowerCase(Locale.ROOT);
        if (l.endsWith(".mcplan")) return n.substring(0, n.length() - 7);
        if (l.endsWith(".json")) return n.substring(0, n.length() - 5);
        return n;
    }

    /**
     * Reads a plan file. IOException when it can't be read, {@link PlanFormatException} (runtime) when it isn't
     * a usable plan.
     */
    public static Plan load(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) throw new PlanFormatException("This file is too large to be a plan file.");
        return PlanParser.parse(Files2.read(file));
    }

    /**
     * Notices when a loaded plan file was saved again: call {@link #check()} every 2 s (or use {@link #start});
     * a change (mtime or size) is reported once it has been stable for 500 ms, so half-written files aren't
     * read. A missing file (mid-replace) is ignored until it's back.
     */
    public static final class FileWatch {
        public static final long POLL_MS = 2000;
        public static final long DEBOUNCE_MS = 500;

        private final Path path;
        private final LongSupplier clock;
        private Stamp known;
        private Stamp pending;
        private long pendingSince;
        private ScheduledFuture<?> task, confirm;

        public FileWatch(Path path) {
            this(path, System::currentTimeMillis);
        }

        FileWatch(Path path, LongSupplier clock) {
            this.path = Objects.requireNonNull(path);
            this.clock = clock;
            this.known = stamp(path);
        }

        public Path path() {
            return path;
        }

        /** True once when the file changed and has been stable for 500 ms. */
        public synchronized boolean check() {
            Stamp cur = stamp(path);
            long now = clock.getAsLong();
            if (cur == null || cur.equals(known)) {
                if (cur != null) pending = null;
                return false;
            }
            if (!cur.equals(pending)) {
                pending = cur;
                pendingSince = now;
                return false;
            }
            if (now - pendingSince >= DEBOUNCE_MS) {
                known = cur;
                pending = null;
                return true;
            }
            return false;
        }

        /** A change was seen but isn't confirmed yet. */
        public synchronized boolean hasPending() {
            return pending != null;
        }

        /** Treat the file as it is now as seen (e.g. right after loading it). */
        public synchronized void reset() {
            known = stamp(path);
            pending = null;
        }

        /** Polls every 2 s on {@code exec}; {@code onChange} runs on {@code exec} (hop to the main thread yourself). */
        public synchronized void start(ScheduledExecutorService exec, Runnable onChange) {
            stop();
            try {
                task = exec.scheduleWithFixedDelay(() -> {
                    try {
                        if (check()) onChange.run();
                        else if (hasPending()) scheduleConfirm(exec, onChange);
                    } catch (RuntimeException e) {
                        LOG.debug("File watch of {} failed", path, e);
                    }
                }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                task = null;
            }
        }

        private synchronized void scheduleConfirm(ScheduledExecutorService exec, Runnable onChange) {
            if (confirm != null && !confirm.isDone()) return;
            try {
                confirm = exec.schedule(() -> {
                    if (check()) onChange.run();
                }, DEBOUNCE_MS + 20, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // shutting down
            }
        }

        public synchronized void stop() {
            if (task != null) task.cancel(false);
            if (confirm != null) confirm.cancel(false);
            task = confirm = null;
        }

        private static Stamp stamp(Path p) {
            try {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                return new Stamp(a.lastModifiedTime().toMillis(), a.size());
            } catch (IOException e) {
                return null;
            }
        }

        private record Stamp(long modified, long size) {}
    }
}
