package dev.lab43.mcplanner.mc;

import dev.lab43.mcplanner.core.Status;

/** Small text helpers shared by the HUD, labels and screens. */
public final class Texts {
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private Texts() {}

    public static String ellipsize(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }

    public static String distance(double blocks) {
        if (blocks < 10) return String.format(java.util.Locale.ROOT, "%.1f m", blocks);
        if (blocks < 10_000) return Math.round(blocks) + " m";
        return String.format(java.util.Locale.ROOT, "%.1f km", blocks / 1000);
    }

    /**
     * An arrow pointing from the player's view towards (dx, dz): "↑" = straight ahead. {@code yRot} is the player's
     * yaw (Minecraft: 0 = south, 90 = west).
     */
    public static String arrow(double dx, double dz, float yRot) {
        double target = Math.toDegrees(Math.atan2(-dx, dz)); // same convention as yaw
        double rel = wrap(target - yRot);
        int i = Math.floorMod(Math.round(rel / 45.0), 8);
        return ARROWS[i];
    }

    /** compass direction of (dx, dz) */
    public static String compass(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north (−z), clockwise
        int i = Math.floorMod(Math.round(deg / 45.0), 8);
        return COMPASS[i];
    }

    private static double wrap(double deg) {
        deg %= 360;
        if (deg >= 180) deg -= 360;
        if (deg < -180) deg += 360;
        return deg;
    }

    public static String ago(long millis) {
        if (millis <= 0) return "never";
        long s = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (s < 5) return "just now";
        if (s < 60) return s + " s ago";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " d ago";
    }

    public static int statusColor(Status s) {
        return switch (s) {
            case IDEA -> 0xFFB8B8B8;
            case PLANNED -> 0xFF7FC8FF;
            case IN_PROGRESS -> 0xFFFFC857;
            case DONE -> 0xFF8FD18F;
        };
    }

    public static String roleLabel(String role) {
        if (role == null) return "";
        return switch (role) {
            case "owner" -> "Owner";
            case "admin" -> "Co-owner";
            case "editor" -> "Editor";
            case "viewer" -> "Viewer";
            default -> role;
        };
    }
}
