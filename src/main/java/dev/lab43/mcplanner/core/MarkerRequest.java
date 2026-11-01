package dev.lab43.mcplanner.core;

/**
 * A new marker for {@code POST /api/mod/worlds/{room}/markers}.
 *
 * @param color "#rrggbb" or null for the world's marker colour
 * @param notes nullable
 */
public record MarkerRequest(String name, int x, int y, int z, String dimension, String color, String notes) {
    public static MarkerRequest of(String name, int x, int y, int z, String dimension) {
        return new MarkerRequest(name, x, y, z, dimension, null, null);
    }
}
