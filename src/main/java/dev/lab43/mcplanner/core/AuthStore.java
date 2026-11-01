package dev.lab43.mcplanner.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps the device token OUTSIDE the Minecraft instance, so modpack exports never contain it:
 * {@code <user.home>/.lab43/mcplanner-minecraft.json} (owner-only on POSIX), falling back to
 * {@code <gameDir>/config/mcplanner-auth.json} when the home folder isn't writable. One entry per hub URL,
 * so a test hub doesn't replace the real sign-in.
 *
 * <p>File: {@code {"version":1,"accounts":{"<hub>":{hub, token, userId, userName, savedAt}}}}.
 */
public final class AuthStore {
    private static final Logger LOG = LoggerFactory.getLogger("MC Planner");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** OAuth client id of the Minecraft mod at the Lab43 hub. */
    public static final String CLIENT_ID = "mcplanner-minecraft";

    /** One saved sign-in. */
    public record Entry(String hub, String token, String userId, String userName, long savedAt) {}

    private final Path primary;
    private final Path fallback;

    /** Primary file in {@code user.home} (read when constructed), fallback in {@code <gameDir>/config}. */
    public AuthStore(Path gameDir) {
        this(Paths.get(System.getProperty("user.home", "."), ".lab43", "mcplanner-minecraft.json"),
            gameDir.resolve("config").resolve("mcplanner-auth.json"));
    }

    public AuthStore(Path primaryFile, Path fallbackFile) {
        this.primary = primaryFile;
        this.fallback = fallbackFile;
    }

    public Path primaryFile() {
        return primary;
    }

    public Path fallbackFile() {
        return fallback;
    }

    /** "https://Auth.Lab43.dev/" → "https://auth.lab43.dev" */
    public static String hubKey(String hubUrl) {
        return Http.trimSlash(hubUrl).toLowerCase(Locale.ROOT);
    }

    /** All saved sign-ins (hub → entry); empty when none. Entries in the home file win over the fallback. */
    public synchronized Map<String, Entry> load() {
        Map<String, Entry> out = new LinkedHashMap<>(read(fallback));
        out.putAll(read(primary));
        return Collections.unmodifiableMap(out);
    }

    /** The sign-in for one hub or null. */
    public synchronized Entry load(String hub) {
        return load().get(hubKey(hub));
    }

    /** Saves (replaces) the sign-in for a hub. False when neither location could be written. */
    public synchronized boolean save(String hub, String token, String userId, String userName) {
        return save(new Entry(hubKey(hub), token, userId, userName, System.currentTimeMillis()));
    }

    public synchronized boolean save(Entry e) {
        String key = hubKey(e.hub());
        Entry entry = new Entry(key, e.token(), e.userId(), e.userName(), e.savedAt());
        try {
            Map<String, Entry> all = new LinkedHashMap<>(read(primary));
            all.put(key, entry);
            write(primary, all);
            removeFrom(fallback, key); // don't leave an older copy in the instance
            return true;
        } catch (IOException | RuntimeException ex) {
            LOG.warn("Could not save the sign-in in {} ({}); using {}", primary, ex.toString(), fallback);
        }
        try {
            Map<String, Entry> all = new LinkedHashMap<>(read(fallback));
            all.put(key, entry);
            write(fallback, all);
            return true;
        } catch (IOException | RuntimeException ex) {
            LOG.error("Could not save the sign-in: {}", ex.toString());
            return false;
        }
    }

    /** Forgets the sign-in for a hub (in both locations). */
    public synchronized void clear(String hub) {
        String key = hubKey(hub);
        removeFrom(primary, key);
        removeFrom(fallback, key);
    }

    private void removeFrom(Path file, String key) {
        Map<String, Entry> all = new LinkedHashMap<>(read(file));
        if (all.remove(key) == null) return;
        try {
            if (all.isEmpty()) Files.deleteIfExists(file);
            else write(file, all);
        } catch (IOException e) {
            LOG.warn("Could not remove the sign-in from {}: {}", file, e.toString());
        }
    }

    private static Map<String, Entry> read(Path file) {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (file == null) return out;
        try {
            JsonObject root = Json.obj(JsonParser.parseString(Files2.read(file)));
            JsonObject accounts = Json.obj(Json.get(root, "accounts"));
            if (accounts == null) return out;
            for (Map.Entry<String, JsonElement> e : accounts.entrySet()) {
                JsonObject o = Json.obj(e.getValue());
                String token = Json.str(o, "token");
                if (token == null || token.isEmpty()) continue;
                String hub = Json.str(o, "hub");
                String key = hubKey(hub != null ? hub : e.getKey());
                Double savedAt = Json.finite(o, "savedAt");
                out.put(key, new Entry(key, token, Json.text(o, "userId"), Json.str(o, "userName"), savedAt != null ? savedAt.longValue() : 0));
            }
        } catch (NoSuchFileException e) {
            // nothing saved
        } catch (IOException | RuntimeException e) {
            LOG.warn("Ignoring the unreadable sign-in file {}: {}", file, e.toString());
        }
        return out;
    }

    private static void write(Path file, Map<String, Entry> all) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        boolean created = !Files.isDirectory(dir);
        Files.createDirectories(dir);
        if (created && dir.getFileName() != null && dir.getFileName().toString().startsWith(".")) Files2.restrict(dir);
        JsonObject accounts = new JsonObject();
        for (Entry e : all.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("hub", e.hub());
            o.addProperty("token", e.token());
            if (e.userId() != null) o.addProperty("userId", e.userId());
            if (e.userName() != null) o.addProperty("userName", e.userName());
            o.addProperty("savedAt", e.savedAt());
            accounts.add(e.hub(), o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("accounts", accounts);
        Files2.writeAtomic(file, GSON.toJson(root), true);
    }
}
