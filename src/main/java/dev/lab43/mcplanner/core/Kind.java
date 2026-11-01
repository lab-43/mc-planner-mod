package dev.lab43.mcplanner.core;

/** What a plan object is (port of the web's {@code PlanKind} + {@code KIND_DEFAULTS}). */
public enum Kind {
    PATH("path", "Path", 0xe8b04a, 3, 0),
    AREA("area", "Area", 0x4ab0e8, 0, 0),
    BUILDING("building", "Building", 0xe85a4a, 0, 8),
    MARKER("marker", "Marker", 0xb04ae8, 0, 0);

    private final String id;
    private final String label;
    private final int defaultColor;
    private final int defaultWidth;
    private final int defaultHeight;

    Kind(String id, String label, int defaultColor, int defaultWidth, int defaultHeight) {
        this.id = id;
        this.label = label;
        this.defaultColor = defaultColor;
        this.defaultWidth = defaultWidth;
        this.defaultHeight = defaultHeight;
    }

    /** "path", "area", "building", "marker" (the id used in plan files and the API). */
    public String id() {
        return id;
    }

    /** "Path", "Area", … (also the default object name). */
    public String label() {
        return label;
    }

    /** Default colour 0xRRGGBB (KIND_DEFAULTS in the web app). */
    public int defaultColor() {
        return defaultColor;
    }

    /** Default path width (3 for paths, 0 otherwise). */
    public int defaultWidth() {
        return defaultWidth;
    }

    /** Default building height (8 for buildings, 0 otherwise). */
    public int defaultHeight() {
        return defaultHeight;
    }

    /** The kind for an id like "path"; null when unknown. */
    public static Kind parse(String id) {
        if (id == null) return null;
        for (Kind k : values()) if (k.id.equals(id)) return k;
        return null;
    }
}
