package dev.lab43.mcplanner.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The last plan of each shared world, so the overlay works offline:
 * {@code <gameDir>/mcplanner/cache/<roomId>.json} = {@code {roomId, etag, savedAt, body: <server JSON>}}.
 */
public final class PlanCache {
    private static final Logger LOG = LoggerFactory.getLogger("MC Planner");

    /** A cached plan; {@link #response()} is what the server sent (fromCache = true). */
    public record Cached(String roomId, PlanResponse response, long savedAt) {
        public Plan plan() {
            return response.plan();
        }

        public String etag() {
            return response.etag();
        }

        public String raw() {
            return response.raw();
        }
    }

    private final Path dir;

    /** Cache under {@code <gameDir>/mcplanner/cache}. */
    public PlanCache(Path gameDir) {
        this(gameDir, true);
    }

    private PlanCache(Path p, boolean gameDir) {
        this.dir = gameDir ? p.resolve("mcplanner").resolve("cache") : p;
    }

    /** Cache in exactly this folder. */
    public static PlanCache inFolder(Path dir) {
        return new PlanCache(dir, false);
    }

    public Path dir() {
        return dir;
    }

    public Path fileFor(String roomId) {
        return dir.resolve(safeName(roomId) + ".json");
    }

    /** Saves the raw server JSON + etag; false (logged) when it can't. */
    public synchronized boolean save(String roomId, String rawJson, String etag) {
        try {
            JsonElement body = JsonParser.parseString(rawJson);
            JsonObject o = new JsonObject();
            o.addProperty("roomId", roomId);
            if (etag != null) o.addProperty("etag", etag);
            o.addProperty("savedAt", System.currentTimeMillis());
            o.add("body", body);
            Files2.writeAtomic(fileFor(roomId), Http.GSON.toJson(o), false);
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not cache the plan of world {}: {}", roomId, e.toString());
            return false;
        }
    }

    /** The cached plan or null (none, or unreadable). */
    public synchronized Cached load(String roomId) {
        Path f = fileFor(roomId);
        try {
            JsonObject o = Json.obj(JsonParser.parseString(Files2.read(f)));
            if (o == null) return null;
            JsonElement body = Json.get(o, "body");
            if (body == null) return null;
            String etag = Json.str(o, "etag");
            Double savedAt = Json.finite(o, "savedAt");
            PlanResponse r = PlannerClient.planResponse(Http.GSON.toJson(body), etag, PlannerClient.DEFAULT_POLL_SECONDS, true);
            return new Cached(roomId, r, savedAt != null ? savedAt.longValue() : 0);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Ignoring the unreadable plan cache {}: {}", f, e.toString());
            return null;
        }
    }

    /** The saved plan is still current (the server answered 304): its savedAt becomes now. False when there is none. */
    public synchronized boolean touch(String roomId) {
        Path f = fileFor(roomId);
        try {
            JsonObject o = Json.obj(JsonParser.parseString(Files2.read(f)));
            if (o == null || Json.get(o, "body") == null) return false;
            o.addProperty("savedAt", System.currentTimeMillis());
            Files2.writeAtomic(f, Http.GSON.toJson(o), false);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            LOG.debug("Could not update {}", f, e);
            return false;
        }
    }

    public synchronized void delete(String roomId) {
        try {
            Files.deleteIfExists(fileFor(roomId));
        } catch (IOException e) {
            LOG.debug("Could not delete {}", fileFor(roomId), e);
        }
    }

    /** Room ids are used as file names only when they're plain; others are hashed. */
    static String safeName(String roomId) {
        if (roomId.matches("[A-Za-z0-9_-]{1,100}")) return roomId;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(roomId.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("h-");
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
