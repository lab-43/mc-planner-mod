package dev.lab43.mcplanner.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * World and server names on this computer, so a crash report can leave them out ({@link Redactor}): the folders in
 * {@code saves/} and the names and addresses in {@code servers.dat}. Read only, never sent.
 */
public final class KnownNames {
    private KnownNames() {}

    static final int MAX = 500;

    /** The folder names in {@code saves/} (each one a world). */
    public static List<String> saves(Path savesDir) {
        List<String> out = new ArrayList<>();
        if (savesDir == null || !Files.isDirectory(savesDir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(savesDir)) {
            for (Path p : ds) {
                if (out.size() >= MAX) break;
                if (Files.isDirectory(p)) out.add(p.getFileName().toString());
            }
        } catch (IOException | RuntimeException ignored) {
            // what was found so far
        }
        return out;
    }

    /** The names and addresses of the servers in Minecraft's server list ({@code servers.dat}, NBT). */
    public static List<String> servers(Path serversDat) {
        List<String> out = new ArrayList<>();
        if (serversDat == null || !Files.isRegularFile(serversDat)) return out;
        try {
            if (Files.size(serversDat) > 4L * 1024 * 1024) return out;
            Map<String, Object> root = Nbt.read(Files.readAllBytes(serversDat));
            if (!(root.get("servers") instanceof List<?> list)) return out;
            for (Object o : list) {
                if (out.size() >= MAX) break;
                if (!(o instanceof Map<?, ?> server)) continue;
                for (String key : new String[] {"name", "ip"}) {
                    if (server.get(key) instanceof String s && !s.isBlank()) out.add(s.strip());
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable: what was found so far
        }
        return out;
    }
}
