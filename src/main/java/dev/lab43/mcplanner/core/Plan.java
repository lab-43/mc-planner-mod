package dev.lab43.mcplanner.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** A whole plan: its objects (all dimensions) plus the shared settings the mod needs. Immutable. */
public final class Plan {
    /** World name from the file / server (nullable). */
    public final String worldName;
    /** Server revision of the plan (nullable; local files have none). */
    public final String rev;

    private final List<PlanObject> objects;
    private final Map<String, PlanObject> byId;
    private final Map<String, int[]> home;
    private final Map<Kind, Integer> kindColors;
    private final Map<String, List<PlanObject>> byDimension = new ConcurrentHashMap<>();

    /**
     * @param home       dimension → {x, y, z}
     * @param kindColors per-world default colour for new objects; kinds left out get the built-in default
     */
    public Plan(String worldName, List<PlanObject> objects, Map<String, int[]> home, Map<Kind, Integer> kindColors, String rev) {
        this.worldName = worldName;
        this.rev = rev;
        LinkedHashMap<String, PlanObject> ids = new LinkedHashMap<>();
        for (PlanObject o : objects) ids.put(o.id, o); // same id twice: the later one wins, at the first's place
        this.objects = Collections.unmodifiableList(new ArrayList<>(ids.values()));
        this.byId = Collections.unmodifiableMap(ids);
        this.home = Collections.unmodifiableMap(new LinkedHashMap<>(home != null ? home : Map.of()));
        EnumMap<Kind, Integer> kc = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) kc.put(k, k.defaultColor());
        if (kindColors != null) kc.putAll(kindColors);
        this.kindColors = Collections.unmodifiableMap(kc);
    }

    public static Plan empty() {
        return new Plan(null, List.of(), Map.of(), Map.of(), null);
    }

    public String worldName() {
        return worldName;
    }

    public String rev() {
        return rev;
    }

    /** All objects in file order (unmodifiable). */
    public List<PlanObject> objects() {
        return objects;
    }

    /** dimension → {x, y, z} (unmodifiable; don't change the arrays). */
    public Map<String, int[]> home() {
        return home;
    }

    /** Home of one dimension or null. */
    public int[] home(String dimension) {
        return home.get(dimension);
    }

    /** Default colour per kind (always has all four kinds). */
    public Map<Kind, Integer> kindColors() {
        return kindColors;
    }

    /** Objects of one dimension, e.g. "minecraft:overworld" (cached, unmodifiable). */
    public List<PlanObject> inDimension(String dim) {
        return byDimension.computeIfAbsent(dim, d -> {
            List<PlanObject> out = new ArrayList<>();
            for (PlanObject o : objects) if (o.dimension.equals(d)) out.add(o);
            return Collections.unmodifiableList(out);
        });
    }

    /** Object by id or null. */
    public PlanObject byId(String id) {
        return id == null ? null : byId.get(id);
    }

    /** A copy where the object with the same id is replaced (or added at the end when new). */
    public Plan withObject(PlanObject replaced) {
        Objects.requireNonNull(replaced);
        List<PlanObject> next = new ArrayList<>(objects.size() + 1);
        boolean found = false;
        for (PlanObject o : objects) {
            if (o.id.equals(replaced.id)) {
                next.add(replaced);
                found = true;
            } else next.add(o);
        }
        if (!found) next.add(replaced);
        return new Plan(worldName, next, home, kindColors, rev);
    }

    /** A copy with another server revision. */
    public Plan withRev(String newRev) {
        return new Plan(worldName, objects, home, kindColors, newRev);
    }

    public int size() {
        return objects.size();
    }

    public boolean isEmpty() {
        return objects.isEmpty();
    }

    @Override
    public String toString() {
        return "Plan[" + worldName + ", " + objects.size() + " objects, rev=" + rev + "]";
    }
}
