package dev.lab43.mcplanner.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds a schematic file on this computer by its fingerprint (sha256), like the app does: the shared plan only knows
 * the hash, never the file (builds belong to their creators). Looks where people keep them — the instance's
 * {@code schematics} folder (Litematica's), Downloads, the Desktop and {@code Documents/MC Planner} — and only ever reads
 * them; nothing is uploaded. Hashes are remembered per file (path + size + time), so looking again is cheap.
 */
public final class SchematicFiles {
    /** larger files aren't schematics we'd show (same limit as the app's import) */
    public static final long MAX_FILE_BYTES = 60L * 1024 * 1024;
    private static final int MAX_FILES = 2000;

    /** a folder to look in; {@code depth} 1 = only the files in it, 2 = also in its subfolders, … */
    public record Folder(Path path, int depth) {}

    private record Known(long size, long modified, String hash) {}

    private final List<Folder> folders;
    private final Map<Path, Known> hashes = new HashMap<>();

    public SchematicFiles(List<Folder> folders) {
        this.folders = List.copyOf(folders);
    }

    /** The usual places: the instance's schematics folder, Downloads, Desktop, Documents/MC Planner (and OneDrive's). */
    public static List<Folder> defaultFolders(Path gameDir) {
        List<Folder> out = new ArrayList<>();
        if (gameDir != null) out.add(new Folder(gameDir.resolve("schematics"), 4));
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            Path h = Paths.get(home);
            out.add(new Folder(h.resolve("Downloads"), 2));
            out.add(new Folder(h.resolve("Desktop"), 1));
            out.add(new Folder(h.resolve("Documents").resolve("MC Planner"), 3));
            out.add(new Folder(h.resolve("OneDrive").resolve("Desktop"), 1));
            out.add(new Folder(h.resolve("OneDrive").resolve("Documents").resolve("MC Planner"), 3));
        }
        return out;
    }

    /** where to tell people to put a file: the instance's schematics folder */
    public static Path suggestedFolder(Path gameDir) {
        return gameDir.resolve("schematics");
    }

    public List<Folder> folders() {
        return folders;
    }

    public static boolean isSchematicName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String ext : Schematic.EXTENSIONS) if (n.endsWith(ext)) return true;
        return false;
    }

    /**
     * Looks for files with these hashes (blocking: call it off the main thread). Files named like {@code fileNames}
     * are hashed first. Returns hash → file for the ones found.
     */
    public synchronized Map<String, Path> find(Set<String> wanted, Set<String> fileNames) {
        Map<String, Path> found = new LinkedHashMap<>();
        if (wanted.isEmpty()) return found;
        List<Path> candidates = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Folder f : folders) {
            if (!Files.isDirectory(f.path())) continue;
            try (Stream<Path> s = Files.walk(f.path(), f.depth())) {
                s.filter(p -> isSchematicName(p.getFileName().toString())).forEach(p -> {
                    if (candidates.size() < MAX_FILES && seen.add(p.toAbsolutePath().normalize())) candidates.add(p);
                });
            } catch (IOException | RuntimeException e) {
                // unreadable folder: skip it
            }
        }
        Set<String> names = new HashSet<>();
        for (String n : fileNames) if (n != null) names.add(n.toLowerCase(Locale.ROOT));
        candidates.sort((a, b) -> Boolean.compare(!names.contains(a.getFileName().toString().toLowerCase(Locale.ROOT)),
                !names.contains(b.getFileName().toString().toLowerCase(Locale.ROOT))));
        for (Path p : candidates) {
            String h = hash(p);
            if (h != null && wanted.contains(h) && !found.containsKey(h)) {
                found.put(h, p);
                if (found.size() == wanted.size()) break;
            }
        }
        return found;
    }

    /** sha256 of a file (hex), remembered while its size and time stay the same; null if unreadable or too big */
    String hash(Path p) {
        try {
            long size = Files.size(p), modified = Files.getLastModifiedTime(p).toMillis();
            if (size <= 0 || size > MAX_FILE_BYTES) return null;
            Path key = p.toAbsolutePath().normalize();
            Known k = hashes.get(key);
            if (k != null && k.size() == size && k.modified() == modified) return k.hash();
            String h = sha256(p);
            hashes.put(key, new Known(size, modified, h));
            return h;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static String sha256(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return sb.toString();
    }
}
