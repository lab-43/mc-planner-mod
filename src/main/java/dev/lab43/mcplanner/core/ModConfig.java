package dev.lab43.mcplanner.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The mod's settings (Gson-serialised, public fields with defaults). Load with {@link #load(Path)}, change the
 * fields, then {@link #save(Path)}. Never holds the sign-in token (see {@link AuthStore}).
 */
public final class ModConfig {
    private static final Logger LOG = LoggerFactory.getLogger("MC Planner");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final List<String> HUD_CORNERS = List.of("top_left", "top_right", "bottom_left", "bottom_right");
    public static final String DEFAULT_HUB_URL = "https://auth.lab43.dev";
    public static final String DEFAULT_API_URL = "https://app.mc-planner.com";

    public int version = 1;

    public boolean overlay = true;
    public boolean labels = true;
    public boolean labelsThroughWalls = false;
    public boolean outlinesThroughWalls = false;

    public boolean showPaths = true;
    public boolean showAreas = true;
    public boolean showBuildings = true;
    public boolean showMarkers = true;

    public boolean showIdea = true;
    public boolean showPlanned = true;
    public boolean showInProgress = true;
    public boolean showDone = false;

    /** show an object's material as block textures (paths, areas, building walls and roofs) */
    public boolean textures = true;
    /** plain-colour tiles on paths without a material */
    public boolean pathTiles = true;
    /** plain-colour fill inside areas without a material */
    public boolean areaFill = false;
    public boolean hud = true;
    /** "top_left" | "top_right" | "bottom_left" | "bottom_right" */
    public String hudCorner = "top_left";

    /** blocks */
    public int renderDistance = 160;
    public int labelDistance = 96;

    public float fillOpacity = 0.25f;
    public float lineOpacity = 0.9f;

    public String hubUrl = DEFAULT_HUB_URL;
    public String apiUrl = DEFAULT_API_URL;

    /**
     * Which plan belongs to which Minecraft world: context key ({@code sp:<save name>} / {@code mp:<server
     * address>}) → {@code room:<id>} or {@code file:<absolute path>}.
     */
    public Map<String, String> worldByContext = new LinkedHashMap<>();

    /** Loads the config; missing → defaults; unreadable → defaults and the bad file is kept as {@code <file>.bak}. */
    public static ModConfig load(Path file) {
        String text;
        try {
            text = Files2.read(file);
        } catch (NoSuchFileException e) {
            return new ModConfig();
        } catch (IOException e) {
            LOG.warn("Could not read {} ({}); using defaults", file, e.toString());
            return new ModConfig();
        }
        try {
            ModConfig c = GSON.fromJson(text, ModConfig.class);
            if (c == null) throw new IllegalStateException("empty file");
            return c.normalize();
        } catch (RuntimeException e) {
            Path bak = file.resolveSibling(file.getFileName() + ".bak");
            LOG.warn("{} is damaged ({}); using defaults, the old file is kept as {}", file, e.getMessage(), bak.getFileName());
            try {
                Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                LOG.warn("Could not keep {}: {}", bak, ex.toString());
            }
            return new ModConfig();
        }
    }

    /** Saves atomically (temp file + move). Errors are logged, not thrown — see {@link #saveOrThrow}. */
    public void save(Path file) {
        try {
            saveOrThrow(file);
        } catch (IOException e) {
            LOG.warn("Could not save {}: {}", file, e.toString());
        }
    }

    public void saveOrThrow(Path file) throws IOException {
        normalize();
        Files2.writeAtomic(file, GSON.toJson(this), false);
    }

    /** Should this object be drawn with the current kind + status filters? */
    public boolean isVisible(PlanObject o) {
        boolean kindOn;
        switch (o.kind) {
            case PATH: kindOn = showPaths; break;
            case AREA: kindOn = showAreas; break;
            case BUILDING: kindOn = showBuildings; break;
            case MARKER: kindOn = showMarkers; break;
            default: kindOn = true;
        }
        if (!kindOn) return false;
        switch (o.status) {
            case IDEA: return showIdea;
            case PLANNED: return showPlanned;
            case IN_PROGRESS: return showInProgress;
            case DONE: return showDone;
            default: return true;
        }
    }

    // ---------- world ↔ plan links ----------

    /** "sp:<save name>" */
    public static String singleplayerContext(String saveName) {
        return "sp:" + saveName;
    }

    /** "mp:<server address>" (lower-cased, trimmed) */
    public static String serverContext(String address) {
        return "mp:" + address.strip().toLowerCase(Locale.ROOT);
    }

    /** "room:<id>" */
    public static String roomRef(String roomId) {
        return "room:" + roomId;
    }

    /** "file:<absolute path>" */
    public static String fileRef(Path file) {
        return "file:" + file.toAbsolutePath().normalize();
    }

    /** The room id of a "room:…" ref, else null. */
    public static String refRoomId(String ref) {
        return ref != null && ref.startsWith("room:") && ref.length() > 5 ? ref.substring(5) : null;
    }

    /** The path of a "file:…" ref, else null. */
    public static Path refFile(String ref) {
        if (ref == null || !ref.startsWith("file:") || ref.length() <= 5) return null;
        try {
            return Path.of(ref.substring(5));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The plan linked to a context ("room:…" / "file:…") or null. */
    public String worldFor(String contextKey) {
        return worldByContext.get(contextKey);
    }

    /** Link (or with null: unlink) a plan to a context. */
    public void setWorldFor(String contextKey, String ref) {
        if (ref == null) worldByContext.remove(contextKey);
        else worldByContext.put(contextKey, ref);
    }

    /** Repairs values a hand-edited file may have broken. */
    ModConfig normalize() {
        if (worldByContext == null) worldByContext = new LinkedHashMap<>();
        else {
            Map<String, String> clean = new LinkedHashMap<>();
            worldByContext.forEach((k, v) -> {
                if (k != null && v != null) clean.put(k, v);
            });
            worldByContext = clean;
        }
        if (hudCorner == null || !HUD_CORNERS.contains(hudCorner)) hudCorner = "top_left";
        if (hubUrl == null || !(hubUrl.startsWith("https://") || hubUrl.startsWith("http://"))) hubUrl = DEFAULT_HUB_URL;
        if (apiUrl == null || !(apiUrl.startsWith("https://") || apiUrl.startsWith("http://"))) apiUrl = DEFAULT_API_URL;
        renderDistance = clamp(renderDistance, 16, 1024);
        labelDistance = clamp(labelDistance, 8, 1024);
        fillOpacity = clamp(fillOpacity);
        lineOpacity = clamp(lineOpacity);
        return this;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clamp(float v) {
        return Float.isNaN(v) ? 0.5f : Math.max(0f, Math.min(1f, v));
    }
}
