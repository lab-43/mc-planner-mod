package dev.lab43.mcplanner.mc.render;

import java.util.ArrayList;
import java.util.List;

import dev.lab43.mcplanner.core.BlockGrid;
import dev.lab43.mcplanner.core.Bounds;
import dev.lab43.mcplanner.core.Geometry;
import dev.lab43.mcplanner.core.Kind;
import dev.lab43.mcplanner.core.PlanObject;

/**
 * The flat (x, z) geometry of one plan object, in world block coordinates — everything that doesn't depend on the
 * terrain. Built off the main thread once per plan change (big areas take a moment to rasterise); {@link Mesh} adds
 * the heights from the loaded world.
 *
 * <p>Conventions (same as the web app): a point (x, z) is a block; polylines and polygons run through block centres
 * (x + 0.5, z + 0.5); a block column (x, z) covers [x, x + 1] × [z, z + 1].
 */
final class Shape {
    /** above this many tiles a path/area is drawn without tiles (only its outline), a building without a roof */
    static final int MAX_TILES = 12_000;
    /** above this many unit edges the outline isn't split per block (drawn at one height) */
    static final int MAX_UNIT_EDGES = 24_000;

    final PlanObject o;
    final Bounds bounds;
    final BlockGrid grid;
    /** block columns to fill (path tiles, area fill, building roof): x, z pairs; empty when too many */
    final int[] tiles;
    /**
     * outline split into unit block edges: per edge x0, z0, x1, z1, insideX, insideZ (the covered block it belongs to,
     * for its height); empty when too long — then {@link #outline} is used
     */
    final int[] unitEdges;
    /** merged outline segments x0, z0, x1, z1 (buildings, and big areas/paths) */
    final int[] outline;
    /** path centre line, sampled about every half block: x, z pairs in world coordinates (block centres + 0.5) */
    final double[] centre;
    /** area corner posts: x, z of the polygon's points (block centres) */
    final double[] posts;
    /** where the label goes (x, z in world coordinates) */
    final double labelX, labelZ;

    private Shape(PlanObject o, Bounds bounds, BlockGrid grid, int[] tiles, int[] unitEdges, int[] outline, double[] centre,
                  double[] posts, double labelX, double labelZ) {
        this.o = o;
        this.bounds = bounds;
        this.grid = grid;
        this.tiles = tiles;
        this.unitEdges = unitEdges;
        this.outline = outline;
        this.centre = centre;
        this.posts = posts;
        this.labelX = labelX;
        this.labelZ = labelZ;
    }

    static Shape of(PlanObject o) {
        int[][] pts = o.kind == Kind.BUILDING ? o.footprint() : o.points;
        Bounds b = o.bounds();
        if (pts == null || pts.length == 0 || b == null) return null;
        BlockGrid g = o.kind == Kind.MARKER ? null : o.grid();

        int[] tiles = new int[0];
        int[] unitEdges = new int[0];
        int[] outline = new int[0];
        double[] centre = new double[0];
        double[] posts = new double[0];

        if (g != null && o.kind != Kind.MARKER && g.count <= MAX_TILES) {
            int[] t = new int[g.count * 2];
            int[] i = {0};
            g.forEach((x, z) -> {
                t[i[0]++] = x;
                t[i[0]++] = z;
            });
            tiles = t;
        }
        if (g != null) {
            List<int[]> segs = Geometry.gridOutline(g);
            outline = flatten(segs);
            if (o.kind != Kind.BUILDING) {
                long total = 0;
                for (int[] s : segs) total += Math.abs(s[2] - s[0]) + Math.abs(s[3] - s[1]);
                if (total <= MAX_UNIT_EDGES) unitEdges = unitEdges(g, segs, (int) total);
            }
        }
        if (o.kind == Kind.PATH) centre = sampleLine(pts);
        if (o.kind == Kind.AREA) {
            posts = new double[pts.length * 2];
            for (int k = 0; k < pts.length; k++) {
                posts[k * 2] = pts[k][0] + 0.5;
                posts[k * 2 + 1] = pts[k][1] + 0.5;
            }
        }

        double lx, lz;
        switch (o.kind) {
            case PATH -> {
                double[] mid = halfway(pts);
                lx = mid[0] + 0.5;
                lz = mid[1] + 0.5;
            }
            case AREA -> {
                if (Geometry.pointInPolygon(b.cx(), b.cz(), pts) || (g != null && g.has((int) Math.floor(b.cx()), (int) Math.floor(b.cz())))) {
                    lx = b.cx() + 0.5;
                    lz = b.cz() + 0.5;
                } else {
                    lx = pts[0][0] + 0.5;
                    lz = pts[0][1] + 0.5;
                }
            }
            case BUILDING -> {
                lx = (b.x0() + b.x1() + 1) / 2.0;
                lz = (b.z0() + b.z1() + 1) / 2.0;
            }
            default -> {
                lx = pts[0][0] + 0.5;
                lz = pts[0][1] + 0.5;
            }
        }
        return new Shape(o, b, g, tiles, unitEdges, outline, centre, posts, lx, lz);
    }

    /** distance (blocks, 2D) from a point to this object's bounding box */
    double distanceTo(double x, double z) {
        double dx = Math.max(0, Math.max(bounds.x0() - x, x - (bounds.x1() + 1)));
        double dz = Math.max(0, Math.max(bounds.z0() - z, z - (bounds.z1() + 1)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static int[] flatten(List<int[]> segs) {
        int[] out = new int[segs.size() * 4];
        int i = 0;
        for (int[] s : segs) {
            out[i++] = s[0];
            out[i++] = s[1];
            out[i++] = s[2];
            out[i++] = s[3];
        }
        return out;
    }

    /** split merged outline segments into one-block edges, each with the covered block it borders */
    private static int[] unitEdges(BlockGrid g, List<int[]> segs, int total) {
        int[] out = new int[total * 6];
        int i = 0;
        for (int[] s : segs) {
            if (s[1] == s[3]) { // along x, at z = s[1]
                int z = s[1];
                for (int x = Math.min(s[0], s[2]); x < Math.max(s[0], s[2]); x++) {
                    int iz = g.has(x, z) ? z : z - 1;
                    out[i++] = x; out[i++] = z; out[i++] = x + 1; out[i++] = z; out[i++] = x; out[i++] = iz;
                }
            } else { // along z, at x = s[0]
                int x = s[0];
                for (int z = Math.min(s[1], s[3]); z < Math.max(s[1], s[3]); z++) {
                    int ix = g.has(x, z) ? x : x - 1;
                    out[i++] = x; out[i++] = z; out[i++] = x; out[i++] = z + 1; out[i++] = ix; out[i++] = z;
                }
            }
        }
        return i == out.length ? out : java.util.Arrays.copyOf(out, i);
    }

    /** the polyline (block centres) sampled every ~0.5 blocks, in world coordinates */
    private static double[] sampleLine(int[][] pts) {
        List<double[]> out = new ArrayList<>();
        if (pts.length == 1) {
            out.add(new double[] {pts[0][0] + 0.5, pts[0][1] + 0.5});
        }
        for (int k = 0; k + 1 < pts.length && out.size() < 40_000; k++) {
            double ax = pts[k][0] + 0.5, az = pts[k][1] + 0.5, bx = pts[k + 1][0] + 0.5, bz = pts[k + 1][1] + 0.5;
            double len = Math.hypot(bx - ax, bz - az);
            int steps = Math.max(1, (int) Math.ceil(len / 0.5));
            for (int s = (k == 0 ? 0 : 1); s <= steps; s++) {
                double t = (double) s / steps;
                out.add(new double[] {ax + (bx - ax) * t, az + (bz - az) * t});
            }
        }
        double[] flat = new double[out.size() * 2];
        for (int k = 0; k < out.size(); k++) {
            flat[k * 2] = out.get(k)[0];
            flat[k * 2 + 1] = out.get(k)[1];
        }
        return flat;
    }

    /** the point halfway along a polyline (block coordinates) */
    private static double[] halfway(int[][] pts) {
        if (pts.length == 1) return new double[] {pts[0][0], pts[0][1]};
        double total = Geometry.polylineLength(pts), half = total / 2, run = 0;
        for (int k = 0; k + 1 < pts.length; k++) {
            double l = Math.hypot(pts[k + 1][0] - pts[k][0], pts[k + 1][1] - pts[k][1]);
            if (run + l >= half && l > 0) {
                double t = (half - run) / l;
                return new double[] {pts[k][0] + (pts[k + 1][0] - pts[k][0]) * t, pts[k][1] + (pts[k + 1][1] - pts[k][1]) * t};
            }
            run += l;
        }
        return new double[] {pts[pts.length - 1][0], pts[pts.length - 1][1]};
    }
}
