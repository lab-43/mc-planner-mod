package dev.lab43.mcplanner.core;

/** Bounding box of block coordinates, both corners inclusive. */
public record Bounds(int x0, int z0, int x1, int z1) {
    public double cx() {
        return (x0 + (double) x1) / 2;
    }

    public double cz() {
        return (z0 + (double) z1) / 2;
    }

    /** Size in blocks along x (inclusive). */
    public int width() {
        return x1 - x0 + 1;
    }

    /** Size in blocks along z (inclusive). */
    public int depth() {
        return z1 - z0 + 1;
    }

    public boolean contains(int x, int z) {
        return x >= x0 && x <= x1 && z >= z0 && z <= z1;
    }
}
