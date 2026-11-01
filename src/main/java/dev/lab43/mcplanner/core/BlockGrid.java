package dev.lab43.mcplanner.core;

/**
 * Occupancy grid over a bounding box (port of the web's {@code BlockGrid}): cell (x, z) is covered when
 * {@link #has(int, int)} is true. Immutable once built by {@link Geometry}; stored as a bit set.
 */
public final class BlockGrid {
    /** world block x / z of the grid's first column / row */
    public final int x0, z0;
    /** size in blocks along x / z */
    public final int w, d;
    /** number of covered cells */
    public final int count;

    private final long[] bits;

    BlockGrid(int x0, int z0, int w, int d, long[] bits, int count) {
        this.x0 = x0;
        this.z0 = z0;
        this.w = w;
        this.d = d;
        this.bits = bits;
        this.count = count;
    }

    static long[] newBits(int w, int d) {
        long cells = (long) w * d;
        return new long[(int) ((cells + 63) >>> 6)];
    }

    static void set(long[] bits, int index) {
        bits[index >>> 6] |= 1L << (index & 63);
    }

    static boolean get(long[] bits, int index) {
        return (bits[index >>> 6] & (1L << (index & 63))) != 0;
    }

    static int cardinality(long[] bits) {
        int n = 0;
        for (long b : bits) n += Long.bitCount(b);
        return n;
    }

    /** Is the block column at world (x, z) covered? */
    public boolean has(int x, int z) {
        long lx = (long) x - x0, lz = (long) z - z0;
        return lx >= 0 && lz >= 0 && lx < w && lz < d && get(bits, (int) (lz * w + lx));
    }

    /** Is the cell at grid-local (lx, lz) covered? (false outside the grid) */
    public boolean hasLocal(int lx, int lz) {
        return lx >= 0 && lz >= 0 && lx < w && lz < d && get(bits, lz * w + lx);
    }

    /** Max world x / z covered by the box (inclusive). */
    public int x1() {
        return x0 + w - 1;
    }

    public int z1() {
        return z0 + d - 1;
    }

    /** Receives covered block columns. */
    public interface BlockVisitor {
        void accept(int x, int z);
    }

    /** Visits every covered column in row order (z, then x), like {@link Geometry#gridBlocks}. */
    public void forEach(BlockVisitor v) {
        for (int z = 0; z < d; z++) {
            int row = z * w;
            for (int x = 0; x < w; x++) if (get(bits, row + x)) v.accept(x0 + x, z0 + z);
        }
    }

    /** Rows of '0'/'1' (debugging / tests): cells[(z - z0) * w + (x - x0)]. */
    public String cellString() {
        StringBuilder sb = new StringBuilder(w * d);
        for (int i = 0; i < w * d; i++) sb.append(get(bits, i) ? '1' : '0');
        return sb.toString();
    }

    @Override
    public String toString() {
        return "BlockGrid[x0=" + x0 + ", z0=" + z0 + ", w=" + w + ", d=" + d + ", count=" + count + "]";
    }
}
