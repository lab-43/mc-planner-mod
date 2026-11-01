package dev.lab43.mcplanner.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Block geometry for plan objects — exact ports of {@code packages/plan/src/geometry.ts} (and the geometry
 * helpers of {@code index.ts}). Results match the TypeScript bit-for-bit (checked by the parity test).
 *
 * <p>Conventions: every point is a block coordinate {x, z}. A polygon runs through the centres of its corner
 * blocks, and its footprint = the blocks whose centre is inside the polygon PLUS the blocks its edges run
 * through — so a 2-corner rectangle 0..4 is 5 × 5 blocks.
 */
public final class Geometry {
    private Geometry() {}

    /** Above this many cells in the bounding box we don't rasterise (counts become estimates). */
    public static final int MAX_GRID_CELLS = 16_000_000;

    /** Blocks per storey when a building doesn't say (DEFAULT_STOREY). */
    public static final int DEFAULT_STOREY = 4;

    /** Bounding box of the points; null when there are none. */
    public static Bounds bounds(int[][] pts) {
        if (pts == null || pts.length == 0) return null;
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (int[] p : pts) {
            x0 = Math.min(x0, p[0]);
            x1 = Math.max(x1, p[0]);
            z0 = Math.min(z0, p[1]);
            z1 = Math.max(z1, p[1]);
        }
        return new Bounds(x0, z0, x1, z1);
    }

    /** The four corners (clockwise seen from above) of the rectangle spanned by two corner blocks. */
    public static int[][] rectCorners(int[] a, int[] b) {
        int x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]);
        int z0 = Math.min(a[1], b[1]), z1 = Math.max(a[1], b[1]);
        return new int[][] {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
    }

    /** Footprint corners of a building (old 2-corner buildings → their 4 rectangle corners); else the points. */
    public static int[][] buildingPoints(Kind kind, int[][] points) {
        return kind == Kind.BUILDING && points.length == 2 ? rectCorners(points[0], points[1]) : points;
    }

    /**
     * Blocks covered by a closed polygon (see class conventions). Degenerate inputs work too:
     * 1 point = that block, 2 points = the line between them. Null for no points or a too large box.
     */
    public static BlockGrid polygonGrid(int[][] pts) {
        if (pts == null || pts.length == 0) return null;
        Bounds b = bounds(pts);
        int x0 = b.x0(), z0 = b.z0();
        long wl = (long) b.x1() - x0 + 1, dl = (long) b.z1() - z0 + 1;
        if (wl * dl > MAX_GRID_CELLS) return null;
        int w = (int) wl, d = (int) dl;
        long[] cells = BlockGrid.newBits(w, d);
        int n = pts.length;
        // interior: scanline through block centres (even–odd rule)
        if (n >= 3) {
            double[] xs = new double[n];
            for (int z = z0; z < z0 + d; z++) {
                int m = 0;
                for (int i = 0, j = n - 1; i < n; j = i++) {
                    double xi = pts[i][0], zi = pts[i][1], xj = pts[j][0], zj = pts[j][1];
                    if ((zi > z) != (zj > z)) xs[m++] = xi + ((z - zi) * (xj - xi)) / (zj - zi);
                }
                Arrays.sort(xs, 0, m);
                int row = (z - z0) * w;
                for (int k = 0; k + 1 < m; k += 2) {
                    double a = Math.max(x0, Math.ceil(xs[k] - 1e-9));
                    double c = Math.min(x0 + w - 1, Math.floor(xs[k + 1] + 1e-9));
                    for (int x = (int) a; x <= c; x++) BlockGrid.set(cells, row + x - x0);
                }
            }
        }
        // outline: rasterise every edge (1 block per step along the longer axis) so edges are closed lines
        int edges = n >= 3 ? n : n - 1;
        for (int e = 0; e < Math.max(1, edges); e++) {
            int[] a = pts[e], c = pts[(e + 1) % n];
            double ddx = (double) c[0] - a[0], ddz = (double) c[1] - a[1];
            double steps = Math.max(Math.max(Math.abs(ddx), Math.abs(ddz)), 0);
            for (int s = 0; s <= steps; s++) {
                double t = steps != 0 ? s / steps : 0;
                long x = Math.round(a[0] + ddx * t), z = Math.round(a[1] + ddz * t);
                BlockGrid.set(cells, (int) ((z - z0) * w + (x - x0)));
            }
        }
        return new BlockGrid(x0, z0, w, d, cells, BlockGrid.cardinality(cells));
    }

    /** Block columns within width/2 of the polyline through block centres (what you'd pave). */
    public static BlockGrid pathGrid(int[][] pts, double width) {
        if (pts == null || pts.length == 0) return null;
        double r = Math.max(0.5, width / 2);
        Bounds b = bounds(pts);
        int pad = (int) Math.ceil(r) + 1;
        long x0l = (long) b.x0() - pad, z0l = (long) b.z0() - pad;
        long wl = (long) b.x1() + pad - x0l + 1, dl = (long) b.z1() + pad - z0l + 1;
        if (wl * dl > MAX_GRID_CELLS) return null;
        int x0 = (int) x0l, z0 = (int) z0l, w = (int) wl, d = (int) dl;
        long[] cells = BlockGrid.newBits(w, d);
        int[][] c = pts.length == 1 ? new int[][] {pts[0], pts[0]} : pts;
        double rr = r * r + 0.01;
        for (int i = 0; i < c.length - 1; i++) {
            double ax = c[i][0], az = c[i][1], bx = c[i + 1][0], bz = c[i + 1][1];
            int gx0 = (int) Math.floor(Math.min(ax, bx) - r), gx1 = (int) Math.ceil(Math.max(ax, bx) + r);
            int gz0 = (int) Math.floor(Math.min(az, bz) - r), gz1 = (int) Math.ceil(Math.max(az, bz) + r);
            double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
            if (l2 == 0) l2 = 1;
            for (int z = gz0; z <= gz1; z++)
                for (int x = gx0; x <= gx1; x++) {
                    double raw = ((x - ax) * dx + (z - az) * dz) / l2;
                    // flat ends at the first/last clicked block (round joints in between, so bends have no gaps)
                    if (pts.length > 1 && ((i == 0 && raw < -1e-6) || (i == c.length - 2 && raw > 1 + 1e-6))) continue;
                    double t = Math.max(0, Math.min(1, raw));
                    double qx = ax + t * dx - x, qz = az + t * dz - z;
                    if (qx * qx + qz * qz <= rr) BlockGrid.set(cells, (z - z0) * w + (x - x0));
                }
        }
        return new BlockGrid(x0, z0, w, d, cells, BlockGrid.cardinality(cells));
    }

    public static boolean gridHas(BlockGrid g, int x, int z) {
        return g != null && g.has(x, z);
    }

    /** All covered block columns as {x, z} (row order), at most {@code limit}. */
    public static List<int[]> gridBlocks(BlockGrid g, int limit) {
        List<int[]> out = new ArrayList<>();
        if (g == null) return out;
        for (int z = 0; z < g.d; z++)
            for (int x = 0; x < g.w; x++)
                if (g.hasLocal(x, z)) {
                    out.add(new int[] {g.x0 + x, g.z0 + z});
                    if (out.size() >= limit) return out;
                }
        return out;
    }

    public static List<int[]> gridBlocks(BlockGrid g) {
        return gridBlocks(g, Integer.MAX_VALUE);
    }

    /**
     * Wall ring: covered blocks that touch the outside (8-neighbourhood), i.e. the closed one-block-thick
     * wall you'd build around the footprint.
     */
    public static int ringCount(BlockGrid g) {
        int n = 0;
        for (int z = 0; z < g.d; z++)
            for (int x = 0; x < g.w; x++) {
                if (!g.hasLocal(x, z)) continue;
                boolean edge = false;
                for (int dz = -1; dz <= 1 && !edge; dz++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, nz = z + dz;
                        if (nx < 0 || nz < 0 || nx >= g.w || nz >= g.d || !g.hasLocal(nx, nz)) {
                            edge = true;
                            break;
                        }
                    }
                if (edge) n++;
            }
        return n;
    }

    /**
     * Outline of the covered blocks along block edges (for drawing walls), as merged straight segments
     * {x0, z0, x1, z1} in world block-edge coordinates (same order as the TS).
     */
    public static List<int[]> gridOutline(BlockGrid g) {
        List<int[]> out = new ArrayList<>();
        // edges parallel to x: between row z-1 and z
        for (int z = 0; z <= g.d; z++) {
            for (int side = 0; side <= 1; side++) {
                int start = -1;
                for (int x = 0; x <= g.w; x++) {
                    boolean on = x < g.w && (side == 0 ? g.hasLocal(x, z) && !g.hasLocal(x, z - 1) : g.hasLocal(x, z - 1) && !g.hasLocal(x, z));
                    if (on && start < 0) start = x;
                    if (!on && start >= 0) {
                        out.add(new int[] {g.x0 + start, g.z0 + z, g.x0 + x, g.z0 + z});
                        start = -1;
                    }
                }
            }
        }
        // edges parallel to z: between column x-1 and x
        for (int x = 0; x <= g.w; x++) {
            for (int side = 0; side <= 1; side++) {
                int start = -1;
                for (int z = 0; z <= g.d; z++) {
                    boolean on = z < g.d && (side == 0 ? g.hasLocal(x, z) && !g.hasLocal(x - 1, z) : g.hasLocal(x - 1, z) && !g.hasLocal(x, z));
                    if (on && start < 0) start = z;
                    if (!on && start >= 0) {
                        out.add(new int[] {g.x0 + x, g.z0 + start, g.x0 + x, g.z0 + z});
                        start = -1;
                    }
                }
            }
        }
        return out;
    }

    /** Length of the polyline through the points (uses V8's Math.hypot algorithm so it matches the web exactly). */
    public static double polylineLength(int[][] pts) {
        double l = 0;
        for (int i = 1; i < pts.length; i++) l += jsHypot((double) pts[i][0] - pts[i - 1][0], (double) pts[i][1] - pts[i - 1][1]);
        return l;
    }

    /** Shoelace area of the polygon. */
    public static double polygonArea(int[][] pts) {
        double a = 0;
        for (int i = 0, j = pts.length - 1; i < pts.length; j = i++)
            a += ((double) pts[j][0] + pts[i][0]) * ((double) pts[j][1] - pts[i][1]);
        return Math.abs(a / 2);
    }

    /** Even–odd point-in-polygon test (x, z in block units; block centres are integers). */
    public static boolean pointInPolygon(double x, double z, int[][] pts) {
        boolean inside = false;
        for (int i = 0, j = pts.length - 1; i < pts.length; j = i++) {
            double xi = pts[i][0], zi = pts[i][1], xj = pts[j][0], zj = pts[j][1];
            if ((zi > z) != (zj > z) && x < ((xj - xi) * (z - zi)) / (zj - zi) + xi) inside = !inside;
        }
        return inside;
    }

    /** "870 blocks" → "13 stacks + 38" (port of formatStacks). */
    public static String formatStacks(long n, int size) {
        if (n < size) return Long.toString(n);
        long stacks = Math.floorDiv(n, (long) size), rest = n % size;
        return stacks + " stack" + (stacks == 1 ? "" : "s") + (rest != 0 ? " + " + rest : "");
    }

    public static String formatStacks(long n) {
        return formatStacks(n, 64);
    }

    /** Block counts for an object (port of {@code counts()}); prefer {@link PlanObject#counts()}, which caches. */
    public static PlanCounts counts(PlanObject o) {
        return counts(o.kind, o.points, o.width, o.height, o.storey);
    }

    /** Block counts for a shape (port of {@code computeCounts()}). */
    public static PlanCounts counts(Kind kind, int[][] points, int width, int height, int storey) {
        int[][] pts = buildingPoints(kind, points);
        if (pts.length == 0) return PlanCounts.EMPTY;
        Bounds b = bounds(pts);
        int w = b.x1() - b.x0() + 1, d = b.z1() - b.z0() + 1;
        if (kind == Kind.MARKER) return new PlanCounts(1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
        if (kind == Kind.PATH) {
            BlockGrid g = pathGrid(pts, Math.max(1, width));
            double len = polylineLength(pts);
            long blocks = g != null ? g.count : Math.round(len * width);
            return new PlanCounts(blocks, w, d, Math.round(len), 0, 0, 0, 0, 0, 0, 0, 0, g == null);
        }
        BlockGrid g = polygonGrid(pts);
        long blocks = g != null ? g.count : Math.round(polygonArea(pts));
        if (kind == Kind.AREA) return new PlanCounts(blocks, w, d, 0, 0, 0, 0, 0, 0, 0, 0, 0, g == null);
        // building
        int h = Math.max(1, height);
        int storeyHeight = Math.max(1, storey);
        int storeys = Math.max(1, (int) Math.floor((double) h / storeyHeight));
        long ring = g != null ? ringCount(g) : 0;
        long floor = blocks - ring;
        long walls = ring * h;
        return new PlanCounts(blocks, w, d, 0, ring, walls, floor, storeys, storeyHeight, blocks, blocks * h,
            walls + floor * (storeys - 1) + blocks, g == null);
    }

    /** V8's Math.hypot for two arguments (Kahan-summed, normalised to the larger value). */
    static double jsHypot(double a, double b) {
        double x = Math.abs(a), y = Math.abs(b);
        boolean nan = Double.isNaN(x) || Double.isNaN(y);
        double max = 0;
        if (x > max) max = x;
        if (y > max) max = y;
        if (max == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY;
        if (nan) return Double.NaN;
        if (max == 0) return 0;
        double sum = 0, compensation = 0;
        for (int i = 0; i < 2; i++) {
            double n = (i == 0 ? x : y) / max;
            double summand = n * n - compensation;
            double preliminary = sum + summand;
            compensation = (preliminary - sum) - summand;
            sum = preliminary;
        }
        return Math.sqrt(sum) * max;
    }
}
