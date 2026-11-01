package dev.lab43.mcplanner.mc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.lab43.mcplanner.core.PlanObject;
import dev.lab43.mcplanner.core.Schematic;
import dev.lab43.mcplanner.core.SchematicFiles;
import dev.lab43.mcplanner.core.SchematicInfo;

import net.minecraft.util.Util;

/**
 * The schematic files of the plan's buildings, found on this computer by their fingerprint and read in the
 * background. A shared plan never carries the file (builds belong to their creators), so like in the app, everyone
 * shows the blocks of the files they have themselves; the others see the box.
 *
 * <p>Searched again every 20 s while a file is missing (cheap: known files aren't hashed twice), and at once with
 * {@link #rescan()} (menu, {@code /mcplanner reload}).
 */
public final class Schematics {
    private static final long RETRY_MS = 20_000;

    public enum State { SEARCHING, MISSING, READY, FAILED }

    /** What's known about one file. Changed on the main thread only. */
    public static final class Entry {
        public final String hash;
        public volatile State state = State.SEARCHING;
        public volatile Path file;
        public volatile Schematic data;
        public volatile String error;
        long checkedAt;

        Entry(String hash) {
            this.hash = hash;
        }
    }

    private final Planner planner;
    private final SchematicFiles files;
    private final Map<String, Entry> byHash = new ConcurrentHashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private boolean searching, again;
    private int version;
    private long lastWanted;

    Schematics(Planner planner) {
        this.planner = planner;
        this.files = new SchematicFiles(SchematicFiles.defaultFolders(planner.gameDir));
    }

    /** changes whenever a file was found, lost or read — the overlay rebuilds its buildings then */
    public int version() {
        return version;
    }

    /** null for objects without a schematic */
    public Entry get(SchematicInfo ref) {
        return ref == null ? null : byHash.get(ref.hash());
    }

    /** where people should put a missing file */
    public Path folder() {
        return SchematicFiles.suggestedFolder(planner.gameDir);
    }

    /** the folders that are looked in, for the help text */
    public String foldersLabel() {
        StringBuilder sb = new StringBuilder();
        for (SchematicFiles.Folder f : files.folders()) {
            if (!Files.isDirectory(f.path()) && !f.path().equals(folder())) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(f.path().getFileName());
        }
        return sb.toString();
    }

    /** Look for every missing file now (also files that couldn't be read: maybe they were replaced). */
    public void rescan() {
        if (searching) {
            again = true;
            return;
        }
        for (Entry e : byHash.values()) e.checkedAt = 0;
        lastWanted = 0;
        tick();
    }

    /** Every client tick (cheap): starts a search for the plan's schematics that aren't here yet. */
    void tick() {
        long now = Util.getMillis();
        if (searching || now - lastWanted < 1_000) return;
        lastWanted = now;
        Map<String, String> wanted = new HashMap<>();
        for (PlanObject o : planner.plan().objects()) {
            if (o.schematic == null) continue;
            wanted.put(o.schematic.hash(), o.schematic.file());
        }
        byHash.keySet().retainAll(wanted.keySet());
        Set<String> look = new HashSet<>();
        for (Map.Entry<String, String> w : wanted.entrySet()) {
            Entry e = byHash.computeIfAbsent(w.getKey(), Entry::new);
            names.put(w.getKey(), w.getValue());
            boolean due = e.state == State.SEARCHING || (e.state == State.MISSING && now - e.checkedAt > RETRY_MS)
                    || (e.state == State.FAILED && e.checkedAt == 0)
                    || (e.state == State.READY && e.file != null && !Files.isRegularFile(e.file) && now - e.checkedAt > RETRY_MS);
            if (due) look.add(w.getKey());
        }
        if (look.isEmpty()) return;
        searching = true;
        Set<String> fileNames = new HashSet<>();
        for (String h : look) fileNames.add(names.get(h));
        planner.exec.execute(() -> {
            Map<String, Path> found = Map.of();
            Map<String, Object> read = new HashMap<>();
            try {
                found = files.find(look, fileNames);
                for (Map.Entry<String, Path> f : found.entrySet()) {
                    try {
                        byte[] bytes = Files.readAllBytes(f.getValue());
                        read.put(f.getKey(), Schematic.read(bytes, f.getValue().getFileName().toString()));
                    } catch (Schematic.SchematicException ex) {
                        read.put(f.getKey(), ex.getMessage());
                    } catch (Exception ex) {
                        read.put(f.getKey(), "Couldn't read " + f.getValue().getFileName() + ": " + ex.getMessage());
                    }
                }
            } catch (RuntimeException ex) {
                Planner.LOG.warn("MC Planner: looking for schematic files failed", ex);
            }
            Map<String, Path> where = found;
            planner.main.execute(() -> {
                searching = false;
                long t = Util.getMillis();
                for (String h : look) {
                    Entry e = byHash.get(h);
                    if (e == null) continue;
                    e.checkedAt = t;
                    Object r = read.get(h);
                    if (r instanceof Schematic s) {
                        boolean changed = e.state != State.READY || !where.get(h).equals(e.file);
                        e.file = where.get(h);
                        e.data = s;
                        e.error = null;
                        e.state = State.READY;
                        if (changed) {
                            version++;
                            Planner.LOG.info("MC Planner: showing schematic {} from {}", names.get(h), e.file);
                        }
                    } else if (r instanceof String msg) {
                        e.file = where.get(h);
                        e.data = null;
                        e.error = msg;
                        if (e.state != State.FAILED) version++;
                        e.state = State.FAILED;
                    } else if (e.state != State.MISSING) {
                        if (e.state == State.READY) version++;
                        e.data = null;
                        e.file = null;
                        e.state = State.MISSING;
                    }
                }
                if (again) { // "Look again" was pressed while this search ran: once more
                    again = false;
                    for (Entry e : byHash.values()) e.checkedAt = 0;
                    lastWanted = 0;
                }
            });
        });
    }
}
