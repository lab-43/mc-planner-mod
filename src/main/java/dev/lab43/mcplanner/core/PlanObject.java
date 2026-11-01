package dev.lab43.mcplanner.core;

import java.util.Locale;
import java.util.Objects;

/**
 * One plan object (port of the web's {@code PlanObject}). Immutable: change it with {@link #withStatus} or
 * {@link #toBuilder()}. Arrays ({@link #points}) are shared — treat them as read-only.
 */
public final class PlanObject {
    public final String id;
    public final Kind kind;
    public final String name;
    /** 0xRRGGBB */
    public final int color;
    public final Status status;
    /** e.g. "minecraft:overworld" */
    public final String dimension;
    /**
     * Block coordinates {x, z}: path = polyline, area = polygon, building = footprint polygon (older buildings:
     * 2 opposite corners — use {@link #footprint()}), marker = 1 point. May be empty.
     */
    public final int[][] points;
    /** base y (buildings / markers); paths and areas follow the terrain */
    public final int y;
    /** path width in blocks */
    public final int width;
    /** building height in blocks */
    public final int height;
    /** building: blocks per storey (4 when the plan doesn't say) */
    public final int storey;
    /** block it's built from, e.g. "minecraft:gravel" (nullable) */
    public final String material;
    /** objects created together share a group id and name (nullable) */
    public final String group;
    public final String groupName;
    /** http(s) link to where it's from (nullable) */
    public final String sourceUrl;
    /** building from an imported schematic (nullable) */
    public final SchematicInfo schematic;
    public final String notes;
    public final String author;
    public final long createdAt;
    public final long updatedAt;

    // lazily computed, immutable results (a race at worst computes them twice)
    private static final Object NO_GRID = new Object();
    private volatile PlanCounts counts;
    private volatile Object grid; // BlockGrid, or NO_GRID once computed as null

    private PlanObject(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.kind = Objects.requireNonNull(b.kind, "kind");
        this.name = b.name != null ? b.name : kind.label();
        this.color = b.color & 0xffffff;
        this.status = b.status != null ? b.status : Status.PLANNED;
        this.dimension = b.dimension != null ? b.dimension : "minecraft:overworld";
        this.points = b.points != null ? b.points : new int[0][];
        this.y = b.y;
        this.width = b.width;
        this.height = b.height;
        this.storey = b.storey;
        this.material = b.material;
        this.group = b.group;
        this.groupName = b.groupName;
        this.sourceUrl = b.sourceUrl;
        this.schematic = b.schematic;
        this.notes = b.notes != null ? b.notes : "";
        this.author = b.author != null ? b.author : "";
        this.createdAt = b.createdAt;
        this.updatedAt = b.updatedAt;
    }

    public static Builder builder(String id, Kind kind) {
        return new Builder(id, kind);
    }

    public Builder toBuilder() {
        Builder b = new Builder(id, kind);
        b.name = name;
        b.color = color;
        b.status = status;
        b.dimension = dimension;
        b.points = points;
        b.y = y;
        b.width = width;
        b.height = height;
        b.storey = storey;
        b.material = material;
        b.group = group;
        b.groupName = groupName;
        b.sourceUrl = sourceUrl;
        b.schematic = schematic;
        b.notes = notes;
        b.author = author;
        b.createdAt = createdAt;
        b.updatedAt = updatedAt;
        return b;
    }

    /** A copy with another status (updatedAt = now), e.g. for an optimistic local change. */
    public PlanObject withStatus(Status s) {
        Builder b = toBuilder();
        b.status = Objects.requireNonNull(s);
        b.updatedAt = System.currentTimeMillis();
        return b.build();
    }

    /** "#rrggbb" */
    public String colorHex() {
        return String.format(Locale.ROOT, "#%06x", color);
    }

    /** Footprint corners (= web's buildingPoints: buildings with exactly 2 points → the 4 rectangle corners). */
    public int[][] footprint() {
        return Geometry.buildingPoints(kind, points);
    }

    /** Bounding box of the footprint; null when the object has no points. */
    public Bounds bounds() {
        return Geometry.bounds(footprint());
    }

    /** Block counts (cached). */
    public PlanCounts counts() {
        PlanCounts c = counts;
        if (c == null) counts = c = Geometry.counts(this);
        return c;
    }

    /**
     * Covered block columns (cached): path → pathGrid(points, max(1, width)), area / building →
     * polygonGrid(footprint). Null for markers, empty objects and shapes too large to rasterise.
     */
    public BlockGrid grid() {
        Object g = grid;
        if (g == null) {
            BlockGrid b = null;
            if (points.length > 0) {
                if (kind == Kind.PATH) b = Geometry.pathGrid(points, Math.max(1, width));
                else if (kind == Kind.AREA || kind == Kind.BUILDING) b = Geometry.polygonGrid(footprint());
            }
            grid = g = b != null ? b : NO_GRID;
        }
        return g instanceof BlockGrid ? (BlockGrid) g : null;
    }

    /** One-line summary for lists (port of the web's {@code describe()}). */
    public String describe() {
        PlanCounts c = counts();
        String mat = material != null ? " of " + materialName(material).toLowerCase(Locale.ROOT) : "";
        switch (kind) {
            case PATH:
                return n(c.length()) + " long · " + width + " wide · " + n(c.blocks()) + " blocks" + mat;
            case AREA:
                return (c.approx() ? "≈" : "") + n(c.blocks()) + " blocks" + mat + " · " + c.w() + " × " + c.d();
            case BUILDING:
                if (schematic != null) {
                    int[] wl = schematic.dims();
                    return wl[0] + " × " + wl[1] + " · " + schematic.size()[1] + " high · " + n(schematic.blocks()) + " blocks (schematic)";
                }
                return c.w() + " × " + c.d() + " · " + n(c.blocks()) + " footprint · " + height + " high"
                    + (material != null ? " · " + materialName(material) : "");
            case MARKER:
            default:
                return points.length > 0 ? "x " + points[0][0] + " · y " + y + " · z " + points[0][1] : "";
        }
    }

    /** en-US grouping like JS {@code toLocaleString("en-US")} for integers. */
    static String n(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    /** "minecraft:dirt_path" → "Dirt Path", "create:andesite_casing" → "Andesite Casing (create)". */
    public static String materialName(String id) {
        String ns, path;
        int colon = id.indexOf(':');
        if (colon >= 0) {
            ns = id.substring(0, colon);
            String rest = id.substring(colon + 1);
            int c2 = rest.indexOf(':'); // JS split(":", 2) drops anything after a second colon
            path = c2 >= 0 ? rest.substring(0, c2) : rest;
        } else {
            ns = "minecraft";
            path = id;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        String[] words = last.split("_", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append(' ');
            String w = words[i];
            if (!w.isEmpty()) sb.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
        }
        return ns.equals("minecraft") ? sb.toString() : sb + " (" + ns + ")";
    }

    @Override
    public String toString() {
        return "PlanObject[" + kind.id() + " " + id + " \"" + name + "\" " + status.id() + "]";
    }

    /** Mutable builder; unset fields get the web's defaults for the kind. */
    public static final class Builder {
        String id;
        Kind kind;
        String name;
        int color;
        Status status = Status.PLANNED;
        String dimension = "minecraft:overworld";
        int[][] points = new int[0][];
        int y = 64;
        int width;
        int height;
        int storey = Geometry.DEFAULT_STOREY;
        String material, group, groupName, sourceUrl;
        SchematicInfo schematic;
        String notes = "", author = "";
        long createdAt, updatedAt;

        Builder(String id, Kind kind) {
            this.id = id;
            this.kind = kind;
            this.name = kind.label();
            this.color = kind.defaultColor();
            this.width = kind.defaultWidth();
            this.height = kind.defaultHeight();
            this.createdAt = this.updatedAt = System.currentTimeMillis();
        }

        public Builder name(String v) { name = v; return this; }
        public Builder color(int v) { color = v; return this; }
        public Builder status(Status v) { status = v; return this; }
        public Builder dimension(String v) { dimension = v; return this; }
        public Builder points(int[][] v) { points = v; return this; }
        public Builder y(int v) { y = v; return this; }
        public Builder width(int v) { width = v; return this; }
        public Builder height(int v) { height = v; return this; }
        public Builder storey(int v) { storey = v; return this; }
        public Builder material(String v) { material = v; return this; }
        public Builder group(String id, String name) { group = id; groupName = name; return this; }
        public Builder sourceUrl(String v) { sourceUrl = v; return this; }
        public Builder schematic(SchematicInfo v) { schematic = v; return this; }
        public Builder notes(String v) { notes = v; return this; }
        public Builder author(String v) { author = v; return this; }
        public Builder createdAt(long v) { createdAt = v; return this; }
        public Builder updatedAt(long v) { updatedAt = v; return this; }

        public PlanObject build() {
            return new PlanObject(this);
        }
    }
}
