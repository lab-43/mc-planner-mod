package dev.lab43.mcplanner.core;

/** Progress of a plan object (port of the web's {@code PlanStatus} + {@code STATUS_LABEL}). */
public enum Status {
    IDEA("idea", "Idea"),
    PLANNED("planned", "Planned"),
    IN_PROGRESS("in_progress", "In progress"),
    DONE("done", "Done");

    private final String id;
    private final String label;

    Status(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /** "idea", "planned", "in_progress", "done". */
    public String id() {
        return id;
    }

    /** "Idea", "Planned", "In progress", "Done". */
    public String label() {
        return label;
    }

    /** idea → planned → in_progress → done → idea. */
    public Status next() {
        Status[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** The status for an id like "in_progress"; null when unknown. */
    public static Status parse(String id) {
        if (id == null) return null;
        for (Status s : values()) if (s.id.equals(id)) return s;
        return null;
    }
}
