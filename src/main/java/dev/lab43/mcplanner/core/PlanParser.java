package dev.lab43.mcplanner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads plans: .mcplan files ({@code mc-planner/plan@2}, older {@code mc-planner/v1}), older JSON exports
 * (with {@code meta}), and the server's response wrapper {@code {room_id, name, role, paused, canEdit, rev,
 * plan}}. Leniency = port of the web's {@code parsePlanFile}: unusable objects are skipped, bad fields fall
 * back to defaults.
 */
public final class PlanParser {
    private PlanParser() {}

    public static final String FORMAT = "mc-planner/plan@2";

    static final Pattern MATERIAL_RE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    static final Pattern COLOR_RE = Pattern.compile("#[0-9a-fA-F]{6}");
    static final Pattern HASH_RE = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern URL_RE = Pattern.compile("(?i)(https?)://([^/?#]*)(.*)", Pattern.DOTALL);
    private static final List<String> FORMATS = List.of("litematic", "sponge", "mcedit", "structure");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Parse JSON text (a UTF-8 BOM is fine). */
    public static Plan parse(String json) {
        if (json == null) throw new PlanFormatException("This is not an MC Planner plan file.");
        String s = json.startsWith("﻿") ? json.substring(1) : json;
        JsonElement root;
        try {
            root = JsonParser.parseString(s);
        } catch (JsonParseException | IllegalStateException e) {
            throw new PlanFormatException("This is not an MC Planner plan file.", e);
        } catch (StackOverflowError e) {
            throw new PlanFormatException("This is not an MC Planner plan file.");
        }
        return parse(root);
    }

    /** Parse an already parsed JSON tree. */
    public static Plan parse(JsonElement root) {
        try {
            return parseTree(root);
        } catch (PlanFormatException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PlanFormatException("This plan file is damaged and can't be read.", e);
        }
    }

    private static Plan parseTree(JsonElement root) {
        JsonObject d = Json.obj(root);
        String rev = null, wrapperName = null;
        // the server's wrapper: {room_id, name, role, paused, canEdit, rev, plan: {…}}
        if (d != null && Json.arr(Json.get(d, "objects")) == null && d.has("plan")) {
            rev = Json.text(d, "rev");
            wrapperName = Json.str(d, "name");
            JsonElement inner = d.get("plan");
            if (inner == null || inner.isJsonNull()) return new Plan(wrapperName, List.of(), Map.of(), Map.of(), rev); // world without a plan yet
            d = Json.obj(inner);
        }
        if (d == null || Json.arr(Json.get(d, "objects")) == null) throw new PlanFormatException("This is not an MC Planner plan file.");
        String format = Json.str(d, "format");
        if (format != null && !format.startsWith("mc-planner/")) throw new PlanFormatException("Unknown file format “" + format + "”.");
        if (rev == null) rev = Json.text(d, "rev");

        long now = System.currentTimeMillis();
        List<PlanObject> objects = new ArrayList<>();
        for (JsonElement raw : d.getAsJsonArray("objects")) {
            PlanObject o = parseObject(raw, now);
            if (o != null) objects.add(o);
        }

        JsonObject meta = Json.obj(Json.get(d, "meta"));
        // world: d.world ?? { name: meta.worldName }
        String worldName;
        JsonElement world = Json.get(d, "world");
        if (world != null) worldName = Json.str(Json.obj(world), "name");
        else worldName = Json.str(meta, "worldName");
        if ((worldName == null || worldName.isEmpty()) && wrapperName != null) worldName = wrapperName;

        // settings: d.settings ?? { home: meta.home, kindColors: meta.kindColors }
        JsonElement homeEl, colorsEl;
        JsonElement settings = Json.get(d, "settings");
        if (settings != null) {
            JsonObject so = Json.obj(settings);
            homeEl = Json.get(so, "home");
            colorsEl = Json.get(so, "kindColors");
        } else {
            homeEl = Json.get(meta, "home");
            colorsEl = Json.get(meta, "kindColors");
        }
        return new Plan(worldName, objects, parseHome(homeEl), parseKindColors(colorsEl), rev);
    }

    /** One object as the web's parsePlanFile reads it; null when the web would skip it. */
    public static PlanObject parseObject(JsonElement raw) {
        return parseObject(raw, System.currentTimeMillis());
    }

    static PlanObject parseObject(JsonElement raw, long now) {
        JsonObject o = Json.obj(raw);
        if (o == null) return null;
        Kind kind = Kind.parse(Json.str(o, "kind"));
        JsonArray pts = Json.arr(Json.get(o, "points"));
        if (kind == null || pts == null) return null;

        String id = Json.str(o, "id");
        PlanObject.Builder b = PlanObject.builder(id != null && !id.isEmpty() ? id : newId(), kind);
        JsonElement name = Json.get(o, "name");
        b.name(name != null ? Json.jsString(name) : kind.label());
        String color = Json.str(o, "color");
        b.color(color != null && COLOR_RE.matcher(color).matches() ? Integer.parseInt(color.substring(1), 16) : kind.defaultColor());
        Status status = Status.parse(Json.str(o, "status"));
        b.status(status != null ? status : Status.PLANNED);
        JsonElement dim = Json.get(o, "dimension");
        b.dimension(dim != null ? Json.jsString(dim) : "minecraft:overworld");

        List<int[]> points = new ArrayList<>(pts.size());
        for (JsonElement pe : pts) {
            JsonArray p = Json.arr(pe);
            if (p == null || p.size() < 2) continue;
            Double x = Json.finite(p.get(0)), z = Json.finite(p.get(1));
            if (x == null || z == null) continue;
            points.add(new int[] {Json.roundInt(x), Json.roundInt(z)});
        }
        b.points(points.toArray(new int[0][]));

        Double y = Json.finite(o, "y"), width = Json.finite(o, "width"), height = Json.finite(o, "height"), storey = Json.finite(o, "storey");
        b.y(y != null ? Json.roundInt(y) : 64);
        b.width(width != null ? Json.roundInt(width) : kind.defaultWidth());
        b.height(height != null ? Json.roundInt(height) : kind.defaultHeight());
        b.storey(storey != null ? Json.roundInt(storey) : Geometry.DEFAULT_STOREY);

        String material = Json.str(o, "material");
        if (material != null && MATERIAL_RE.matcher(material).matches()) b.material(material);
        String group = Json.str(o, "group");
        if (group != null && !group.isEmpty()) {
            JsonElement gn = Json.get(o, "groupName");
            b.group(Json.slice(group, 40), Json.slice(gn != null ? Json.jsString(gn) : "Group", 100));
        }
        String src = normalizeSourceUrl(Json.str(o, "sourceUrl"));
        if (src != null) b.sourceUrl(src);
        if (kind == Kind.BUILDING) {
            SchematicInfo s = parseSchematic(Json.get(o, "schematic"));
            if (s != null) b.schematic(s);
        }
        JsonElement notes = Json.get(o, "notes"), author = Json.get(o, "author");
        b.notes(notes != null ? Json.jsString(notes) : "");
        b.author(author != null ? Json.jsString(author) : "");
        Double created = Json.finite(o, "createdAt"), updated = Json.finite(o, "updatedAt");
        b.createdAt(created != null ? created.longValue() : now);
        b.updatedAt(updated != null ? updated.longValue() : now);
        return b.build();
    }

    /** Port of parseSchematicRef: null if unusable. */
    static SchematicInfo parseSchematic(JsonElement raw) {
        JsonObject r = Json.obj(raw);
        if (r == null) return null;
        String hash = Json.str(r, "hash");
        if (hash == null || !HASH_RE.matcher(hash).matches()) return null;
        JsonArray sizeArr = Json.arr(Json.get(r, "size"));
        if (sizeArr == null || sizeArr.size() != 3) return null;
        int[] size = new int[3];
        for (int i = 0; i < 3; i++) {
            JsonElement v = sizeArr.get(i);
            if (!Json.isInteger(v)) return null;
            double dv = v.getAsDouble();
            if (!(dv > 0 && dv <= 100000)) return null;
            size[i] = (int) dv;
        }
        List<SchematicInfo.Material> materials = new ArrayList<>();
        JsonArray mats = Json.arr(Json.get(r, "materials"));
        if (mats != null) {
            for (JsonElement me : mats) {
                if (materials.size() >= 500) break;
                JsonArray m = Json.arr(me);
                if (m == null || m.size() < 2) continue;
                JsonElement id = m.get(0);
                if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || id.getAsString().length() >= 200) continue;
                Double count = Json.finite(m.get(1));
                if (count == null) continue;
                materials.add(new SchematicInfo.Material(id.getAsString(), Math.max(0, Json.roundInt(count))));
            }
        }
        JsonElement file = Json.get(r, "file");
        String format = Json.str(r, "format");
        JsonElement turns = Json.get(r, "turns");
        int t = Json.isInteger(turns) ? (int) ((((long) turns.getAsDouble() % 4) + 4) % 4) : 0;
        Double blocks = Json.finite(r, "blocks");
        String author = Json.str(r, "author");
        return new SchematicInfo(
            hash,
            Json.slice(file != null ? Json.jsString(file) : "schematic", 200),
            size,
            t,
            Json.truthy(Json.get(r, "mirror")),
            blocks != null ? Math.max(0, Json.roundInt(blocks)) : 0,
            format != null && FORMATS.contains(format) ? format : "sponge",
            author != null && !author.isEmpty() ? Json.slice(author, 200) : null,
            List.copyOf(materials));
    }

    private static Map<String, int[]> parseHome(JsonElement el) {
        Map<String, int[]> out = new LinkedHashMap<>();
        JsonObject h = Json.obj(el);
        if (h == null) return out;
        for (Map.Entry<String, JsonElement> e : h.entrySet()) {
            JsonObject loc = Json.obj(e.getValue());
            Double x = Json.finite(loc, "x"), z = Json.finite(loc, "z"), y = Json.finite(loc, "y");
            if (x == null || z == null) continue;
            out.put(e.getKey(), new int[] {Json.roundInt(x), y != null ? Json.roundInt(y) : 64, Json.roundInt(z)});
        }
        return out;
    }

    private static Map<Kind, Integer> parseKindColors(JsonElement el) {
        Map<Kind, Integer> out = new EnumMap<>(Kind.class);
        JsonObject c = Json.obj(el);
        if (c == null) return out;
        for (Kind k : Kind.values()) {
            String v = Json.str(c, k.id());
            if (v != null && COLOR_RE.matcher(v).matches()) out.put(k, Integer.parseInt(v.substring(1), 16));
        }
        return out;
    }

    /** Is it a material id like "minecraft:gravel"? */
    public static boolean isMaterialId(String s) {
        return s != null && MATERIAL_RE.matcher(s).matches();
    }

    /** "#rrggbb" → 0xRRGGBB, or -1 when it isn't one. */
    public static int parseColor(String s) {
        return s != null && COLOR_RE.matcher(s).matches() ? Integer.parseInt(s.substring(1), 16) : -1;
    }

    /** A new random object id (14 chars, like the web's newId). */
    public static String newId() {
        byte[] a = new byte[9];
        RANDOM.nextBytes(a);
        StringBuilder sb = new StringBuilder();
        for (byte b : a) {
            String s = Integer.toString(b & 0xff, 36);
            if (s.length() < 2) sb.append('0');
            sb.append(s);
        }
        return sb.substring(0, 14);
    }

    /**
     * A source link as stored by the web: http(s) only, "www.…" gets https://; null for anything else.
     * Close to the web's {@code new URL(s).href} normalisation (scheme + host lower-cased, empty path → "/").
     */
    public static String normalizeSourceUrl(String input) {
        if (input == null) return null;
        String s = input.strip();
        if (s.isEmpty()) return null;
        if (s.regionMatches(true, 0, "www.", 0, 4)) s = "https://" + s;
        if (s.length() > 1000) return null;
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i)) || Character.isSpaceChar(s.charAt(i))) return null;
        Matcher m = URL_RE.matcher(s);
        if (!m.matches()) return null;
        String authority = m.group(2), rest = m.group(3);
        int at = authority.lastIndexOf('@');
        String user = at >= 0 ? authority.substring(0, at + 1) : "";
        String hostPort = at >= 0 ? authority.substring(at + 1) : authority;
        if (hostPort.isEmpty() || hostPort.startsWith(":")) return null;
        if (rest.isEmpty() || rest.startsWith("?") || rest.startsWith("#")) rest = "/" + rest;
        return m.group(1).toLowerCase(Locale.ROOT) + "://" + user + hostPort.toLowerCase(Locale.ROOT) + rest;
    }
}
