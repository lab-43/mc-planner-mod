package dev.lab43.mcplanner.mc.render;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.gizmos.Gizmo;
import net.minecraft.gizmos.GizmoPrimitives;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Ready-to-draw primitives of one plan object (lines and translucent quads in world coordinates), built on the main
 * thread from a {@link Shape} plus terrain heights, never changed afterwards.
 *
 * <p>Lines go through Minecraft's gizmo system (vanilla's debug drawing). The quads ("fills": path tiles, area fill,
 * walls, roofs) are drawn with the banner-pattern render type — translucent, entity vertex layout, depth test without
 * depth write — textured from the block atlas when the object has a material, else with a plain white texture in the
 * object's colour, because shader packs (Iris) can't draw vanilla's debug quads. Both work the same on the OpenGL and
 * the Vulkan backend. That render type culls back faces, so walls and roofs are written from both sides.
 *
 * <p>Primitives are grouped in 16 × 16 block cells, so a frame only draws the cells in view and in range — a big area
 * costs nothing when you only see a corner of it. Long lines and big quads are split at the cell borders, so a part in
 * view never disappears with a cell out of view.
 */
final class Mesh {
    static final int CELL = 16;

    /**
     * Shader packs (Iris) drop fragments whose final alpha isn't above 0.1, vanilla doesn't: fills are never fainter
     * than this (of 255), so faint walls and "idea"/"done" fills stay visible with shaders too.
     */
    static final int MIN_ALPHA = 31;

    // fills: 4 corners (x, y, z), 4 texture coordinates (u, v) and the normal (front side) per quad
    private double[] fillPos = new double[12 * 16];
    private float[] fillUv = new float[8 * 16];
    private float[] fillNormals = new float[3 * 16];
    private int[] fillColors = new int[16];
    private int[] fillLights = new int[16];
    private boolean[] fillTwoSided = new boolean[16];
    private boolean[] fillAtlas = new boolean[16];
    private int[] fillCells = new int[16];
    /** which schematic block a fill belongs to (-1: none), so blocks already built can be left out */
    private int[] fillGroups = new int[16];
    private int group = -1;
    /** number of fills */
    private int quads;
    /** of which textured from the block atlas */
    private int atlasQuads;
    private Vec3[] linePts = new Vec3[64];
    private int[] lineColors = new int[32];
    private float[] lineWidths = new float[32];
    private int[] lineCells = new int[32];
    private int lines;

    // cells: index → bounds (built while adding)
    private final Map<Long, Integer> cellIndex = new HashMap<>();
    private int[] cellX = new int[8], cellZ = new int[8];
    private double[] cellMinY = new double[8], cellMaxY = new double[8];
    private int[] cellPrims = new int[8];
    private int cells;

    /** world-space bounds of everything added */
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

    boolean isEmpty() {
        return quads == 0 && lines == 0;
    }

    int size() {
        return quads + lines;
    }

    boolean hasFills(boolean atlas) {
        return atlas ? atlasQuads > 0 : quads > atlasQuads;
    }

    /**
     * A translucent quad: corners in order (counter-clockwise seen from its front), each with its texture coordinate
     * ({@code uv}: u0, v0 … u3, v3) — on the block atlas when {@code atlas}, else on a white texture (0…1);
     * {@code argb} multiplies the texture. {@code twoSided}: also seen from behind (walls, roofs).
     */
    void quad(double x0, double y0, double z0, double x1, double y1, double z1, double x2, double y2, double z2,
              double x3, double y3, double z3, float[] uv, boolean atlas, int argb, int light, boolean twoSided) {
        if (quads == fillColors.length) {
            int n = quads * 2;
            fillPos = Arrays.copyOf(fillPos, n * 12);
            fillUv = Arrays.copyOf(fillUv, n * 8);
            fillNormals = Arrays.copyOf(fillNormals, n * 3);
            fillColors = Arrays.copyOf(fillColors, n);
            fillLights = Arrays.copyOf(fillLights, n);
            fillTwoSided = Arrays.copyOf(fillTwoSided, n);
            fillAtlas = Arrays.copyOf(fillAtlas, n);
            fillCells = Arrays.copyOf(fillCells, n);
            fillGroups = Arrays.copyOf(fillGroups, n);
        }
        int p = quads * 12;
        fillPos[p] = x0; fillPos[p + 1] = y0; fillPos[p + 2] = z0;
        fillPos[p + 3] = x1; fillPos[p + 4] = y1; fillPos[p + 5] = z1;
        fillPos[p + 6] = x2; fillPos[p + 7] = y2; fillPos[p + 8] = z2;
        fillPos[p + 9] = x3; fillPos[p + 10] = y3; fillPos[p + 11] = z3;
        System.arraycopy(uv, 0, fillUv, quads * 8, 8);
        // front normal: (corner1 − corner0) × (corner3 − corner0)
        double ax = x1 - x0, ay = y1 - y0, az = z1 - z0, bx = x3 - x0, by = y3 - y0, bz = z3 - z0;
        double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0) len = 1;
        fillNormals[quads * 3] = (float) (nx / len);
        fillNormals[quads * 3 + 1] = (float) (ny / len);
        fillNormals[quads * 3 + 2] = (float) (nz / len);
        fillColors[quads] = argb;
        fillLights[quads] = light;
        fillTwoSided[quads] = twoSided;
        fillAtlas[quads] = atlas;
        fillGroups[quads] = group;
        if (atlas) atlasQuads++;
        fillCells[quads++] = cell((x0 + x2) / 2, (z0 + z2) / 2, Math.min(Math.min(y0, y1), Math.min(y2, y3)), Math.max(Math.max(y0, y1), Math.max(y2, y3)));
        grow(x0, y0, z0);
        grow(x1, y1, z1);
        grow(x2, y2, z2);
        grow(x3, y3, z3);
    }

    /** the schematic block (index) the next fills belong to; -1 = none */
    void group(int g) {
        group = g;
    }

    private static final float[] WHITE_UV = {0, 0, 0, 1, 1, 1, 1, 0};

    private static float[] uv(Paint.Skin s) {
        return s == null ? WHITE_UV : uv(s.u0(), s.v0(), s.u1(), s.v1());
    }

    private static float[] uv(float u0, float v0, float u1, float v1) {
        return new float[] {u0, v0, u0, v1, u1, v1, u1, v0};
    }

    /** a flat tile on top of block column (x, z) at height y, facing up: the texture once per block, north up */
    void tile(int x, double y, int z, Paint.Skin skin, int argb, int light, boolean twoSided) {
        quad(x, y, z, x, y, z + 1, x + 1, y, z + 1, x + 1, y, z, uv(skin), skin != null, argb, light, twoSided);
    }

    /** a flat rectangle [x0, x1] × [z0, z1] at height y in a plain colour, seen from both sides (merged roof rows) */
    void rect(int x0, int z0, int x1, int z1, double y, int argb, int light) {
        for (int xa = x0; xa < x1; ) {
            int xb = Math.min(x1, nextBorder(xa));
            for (int za = z0; za < z1; ) {
                int zb = Math.min(z1, nextBorder(za));
                quad(xa, y, za, xa, y, zb, xb, y, zb, xb, y, za, WHITE_UV, false, argb, light, true);
                za = zb;
            }
            xa = xb;
        }
    }

    /**
     * An upright quad from (x0, z0) to (x1, z1) between heights yb and yt (a wall, or one block of it), seen from both
     * sides: the texture's top at yt. Walls along x or z are split at the cell borders.
     */
    void wall(double x0, double z0, double x1, double z1, double yb, double yt, Paint.Skin skin, int argb, int light) {
        float u0 = skin == null ? 0 : skin.u0(), u1 = skin == null ? 1 : skin.u1();
        float v0 = skin == null ? 0 : skin.v0(), v1 = skin == null ? 1 : skin.v1();
        boolean alongX = z0 == z1 && x0 != x1, alongZ = x0 == x1 && z0 != z1;
        double from = alongX ? x0 : z0, to = alongX ? x1 : z1;
        if (!(alongX || alongZ) || Math.floorDiv((int) Math.floor(Math.min(from, to)), CELL) == Math.floorDiv((int) Math.ceil(Math.max(from, to)) - 1, CELL)) {
            quad(x0, yt, z0, x0, yb, z0, x1, yb, z1, x1, yt, z1, uv(u0, v0, u1, v1), skin != null, argb, light, true);
            return;
        }
        double dir = Math.signum(to - from), a = from;
        while ((to - a) * dir > 1e-9) {
            double b = dir > 0 ? Math.min(to, nextBorder((int) Math.floor(a))) : Math.max(to, prevBorder((int) Math.ceil(a)));
            float ua = (float) (u0 + (u1 - u0) * (a - from) / (to - from)), ub = (float) (u0 + (u1 - u0) * (b - from) / (to - from));
            if (alongX) quad(a, yt, z0, a, yb, z0, b, yb, z0, b, yt, z0, uv(ua, v0, ub, v1), skin != null, argb, light, true);
            else quad(x0, yt, a, x0, yb, a, x0, yb, b, x0, yt, b, uv(ua, v0, ub, v1), skin != null, argb, light, true);
            a = b;
        }
    }

    /** the first cell border after block coordinate v */
    private static int nextBorder(int v) {
        return (Math.floorDiv(v, CELL) + 1) * CELL;
    }

    /** the last cell border before coordinate v (exclusive) */
    private static int prevBorder(int v) {
        return (Math.floorDiv(v - 1, CELL)) * CELL;
    }

    /** a line; long ones are split where they cross a cell border */
    void line(double x0, double y0, double z0, double x1, double y1, double z1, int argb, float width) {
        int c0x = Math.floorDiv((int) Math.floor(x0), CELL), c1x = Math.floorDiv((int) Math.floor(x1), CELL);
        int c0z = Math.floorDiv((int) Math.floor(z0), CELL), c1z = Math.floorDiv((int) Math.floor(z1), CELL);
        if (c0x == c1x && c0z == c1z) {
            segment(x0, y0, z0, x1, y1, z1, argb, width);
            return;
        }
        // where the line crosses x = k·CELL and z = k·CELL
        java.util.TreeSet<Double> ts = new java.util.TreeSet<>();
        for (int k = Math.min(c0x, c1x) + 1; k <= Math.max(c0x, c1x); k++) ts.add((k * (double) CELL - x0) / (x1 - x0));
        for (int k = Math.min(c0z, c1z) + 1; k <= Math.max(c0z, c1z); k++) ts.add((k * (double) CELL - z0) / (z1 - z0));
        ts.add(1.0);
        double t0 = 0;
        for (double t : ts) {
            if (t <= t0 || t > 1) continue;
            segment(x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0, z0 + (z1 - z0) * t0, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t, argb, width);
            t0 = t;
        }
    }

    private void segment(double x0, double y0, double z0, double x1, double y1, double z1, int argb, float width) {
        if (lines * 2 + 2 > linePts.length) {
            linePts = Arrays.copyOf(linePts, linePts.length * 2);
            lineColors = Arrays.copyOf(lineColors, lineColors.length * 2);
            lineWidths = Arrays.copyOf(lineWidths, lineWidths.length * 2);
            lineCells = Arrays.copyOf(lineCells, lineCells.length * 2);
        }
        int i = lines * 2;
        linePts[i] = new Vec3(x0, y0, z0);
        linePts[i + 1] = new Vec3(x1, y1, z1);
        lineColors[lines] = argb;
        lineWidths[lines] = width;
        lineCells[lines++] = cell((x0 + x1) / 2, (z0 + z1) / 2, Math.min(y0, y1), Math.max(y0, y1));
        grow(x0, y0, z0);
        grow(x1, y1, z1);
    }

    /** the 12 edges of a box */
    void box(double x0, double y0, double z0, double x1, double y1, double z1, int argb, float width) {
        line(x0, y0, z0, x1, y0, z0, argb, width);
        line(x1, y0, z0, x1, y0, z1, argb, width);
        line(x1, y0, z1, x0, y0, z1, argb, width);
        line(x0, y0, z1, x0, y0, z0, argb, width);
        line(x0, y1, z0, x1, y1, z0, argb, width);
        line(x1, y1, z0, x1, y1, z1, argb, width);
        line(x1, y1, z1, x0, y1, z1, argb, width);
        line(x0, y1, z1, x0, y1, z0, argb, width);
        line(x0, y0, z0, x0, y1, z0, argb, width);
        line(x1, y0, z0, x1, y1, z0, argb, width);
        line(x1, y0, z1, x1, y1, z1, argb, width);
        line(x0, y0, z1, x0, y1, z1, argb, width);
    }

    private int cell(double x, double z, double y0, double y1) {
        int cx = Math.floorDiv((int) Math.floor(x), CELL), cz = Math.floorDiv((int) Math.floor(z), CELL);
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        Integer idx = cellIndex.get(key);
        int i;
        if (idx == null) {
            if (cells == cellX.length) {
                int n = cells * 2;
                cellX = Arrays.copyOf(cellX, n);
                cellZ = Arrays.copyOf(cellZ, n);
                cellMinY = Arrays.copyOf(cellMinY, n);
                cellMaxY = Arrays.copyOf(cellMaxY, n);
                cellPrims = Arrays.copyOf(cellPrims, n);
            }
            i = cells++;
            cellIndex.put(key, i);
            cellX[i] = cx;
            cellZ[i] = cz;
            cellMinY[i] = y0;
            cellMaxY[i] = y1;
        } else {
            i = idx;
            if (y0 < cellMinY[i]) cellMinY[i] = y0;
            if (y1 > cellMaxY[i]) cellMaxY[i] = y1;
        }
        cellPrims[i]++;
        return i;
    }

    private void grow(double x, double y, double z) {
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (z < minZ) minZ = z;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
        if (z > maxZ) maxZ = z;
    }

    AABB bounds() {
        return new AABB(minX - 0.5, minY - 0.5, minZ - 0.5, maxX + 0.5, maxY + 0.5, maxZ + 0.5);
    }

    /**
     * Which cells to draw this frame: in the frustum and within {@code range} blocks (2D) of the camera; fills (quads)
     * only within {@code fillRange}. Returns null when nothing is visible; counts the primitives into {@code count[0]}.
     */
    Visible visible(Frustum frustum, double camX, double camZ, double range, double fillRange, int[] count) {
        BitSet lines = new BitSet(cells), fills = new BitSet(cells);
        boolean any = false;
        for (int i = 0; i < cells; i++) {
            double x0 = cellX[i] * (double) CELL, z0 = cellZ[i] * (double) CELL;
            double dx = Math.max(0, Math.max(x0 - camX, camX - (x0 + CELL)));
            double dz = Math.max(0, Math.max(z0 - camZ, camZ - (z0 + CELL)));
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > range) continue;
            if (!frustum.isVisible(new AABB(x0 - 1, cellMinY[i] - 1, z0 - 1, x0 + CELL + 1, cellMaxY[i] + 1, z0 + CELL + 1))) continue;
            lines.set(i);
            if (d <= fillRange) fills.set(i);
            count[0] += cellPrims[i];
            any = true;
        }
        return any ? new Visible(lines, fills) : null;
    }

    record Visible(BitSet lines, BitSet fills) {}

    boolean hasLines() {
        return lines > 0;
    }

    /** The lines as one gizmo per object and frame; {@code alpha} fades it (pulsing "in progress"). */
    Gizmo gizmo(float alpha, Visible v) {
        return new Draw(this, alpha, v);
    }

    /**
     * Writes the visible fills of one texture ({@code atlas}: block atlas, else white) as quads with the entity vertex
     * layout, relative to the camera at (cx, cy, cz) — the pose is the level's (identity, camera-relative). Only the
     * side facing the camera is written (the render type culls the other anyway): two-sided fills seen from behind
     * get their corners reversed and the normal turned round. Fills of schematic blocks in {@code hidden} (already
     * built) are left out.
     */
    void emitFills(PoseStack.Pose pose, VertexConsumer vc, boolean atlas, Visible v, float alpha, java.util.BitSet hidden,
                   double cx, double cy, double cz) {
        for (int q = 0; q < quads; q++) {
            if (fillAtlas[q] != atlas || !v.fills().get(fillCells[q])) continue;
            if (hidden != null && fillGroups[q] >= 0 && hidden.get(fillGroups[q])) continue;
            int p = q * 12, t = q * 8, n = q * 3;
            float nx = fillNormals[n], ny = fillNormals[n + 1], nz = fillNormals[n + 2];
            boolean front = nx * (cx - fillPos[p]) + ny * (cy - fillPos[p + 1]) + nz * (cz - fillPos[p + 2]) >= 0;
            if (!front && !fillTwoSided[q]) continue;
            int color = Draw.fade(fillColors[q], alpha);
            if ((color >>> 24) < MIN_ALPHA) color = (color & 0xFFFFFF) | (MIN_ALPHA << 24);
            int light = fillLights[q];
            if (front) {
                for (int k = 0; k < 4; k++) vertex(pose, vc, p, t, k, color, light, nx, ny, nz, cx, cy, cz);
            } else {
                for (int k : BACK) vertex(pose, vc, p, t, k, color, light, -nx, -ny, -nz, cx, cy, cz);
            }
        }
    }

    /** corner order of the back side */
    private static final int[] BACK = {0, 3, 2, 1};

    private void vertex(PoseStack.Pose pose, VertexConsumer vc, int p, int t, int k, int color, int light, float nx, float ny, float nz,
                        double cx, double cy, double cz) {
        vc.addVertex(pose, (float) (fillPos[p + k * 3] - cx), (float) (fillPos[p + k * 3 + 1] - cy), (float) (fillPos[p + k * 3 + 2] - cz))
                .setColor(color)
                .setUv(fillUv[t + k * 2], fillUv[t + k * 2 + 1])
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }

    private record Draw(Mesh m, float alpha, Visible v) implements Gizmo {
        @Override
        public void emit(GizmoPrimitives p, float alphaMultiplier) {
            float a = alpha * alphaMultiplier;
            for (int l = 0; l < m.lines; l++) {
                if (!v.lines().get(m.lineCells[l])) continue;
                int i = l * 2;
                p.addLine(m.linePts[i], m.linePts[i + 1], fade(m.lineColors[l], a), m.lineWidths[l]);
            }
        }

        static int fade(int argb, float a) {
            return a >= 0.999f ? argb : ARGB.multiplyAlpha(argb, a);
        }
    }
}
