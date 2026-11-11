package dev.lab43.mcplanner.mc.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dev.lab43.mcplanner.core.Kind;
import dev.lab43.mcplanner.core.ModConfig;
import dev.lab43.mcplanner.core.PlanObject;
import dev.lab43.mcplanner.core.Status;
import dev.lab43.mcplanner.mc.Planner;
import dev.lab43.mcplanner.mc.Schematics;
import dev.lab43.mcplanner.mc.Texts;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3fc;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/**
 * Draws the plan into the world: paths as tiles on the ground, areas as an outline that follows the terrain (and a
 * fill), buildings as see-through boxes with storey lines and a roof, markers as a block outline with a beam, and a
 * label per object. Objects with a material show its block texture (gravel path, wheat field, stone brick walls).
 *
 * <p>Prepared once per frame at the end of level extraction ({@code LevelExtractionEvents.END_EXTRACTION}) and handed
 * to Minecraft right before it collects the frame's gizmos ({@code LevelRenderEvents.BEFORE_GIZMOS}): lines and label
 * text as gizmos, the translucent quads as custom geometry with the banner-pattern render type (which shader packs
 * support, unlike vanilla's debug quads).
 * Everything is culled by distance and frustum; terrain heights are read from the loaded chunks with a per-frame budget
 * and refreshed every few seconds, so building on the spot shows up.
 */
public final class Overlay {
    private static final int HEIGHT_BUDGET = 12_000;
    private static final long HEIGHT_REFRESH_MS = 3_000;
    private static final long HEIGHT_RETRY_MS = 600;
    private static final int MAX_LABELS = 40;
    private static final int MARKER_BEAM = 12;
    static final int NONE = Integer.MIN_VALUE;

    private static final long PRUNE_MS = 30_000;
    /** fills (path tiles, area fill) are drawn within this many blocks; lines up to the draw distance */
    private static final double FILL_RANGE = 96;
    /** at most this many primitives per frame (nearest objects first) */
    private static final int MAX_PRIMITIVES = 150_000;
    /** schematic blocks compared with the world per frame (to hide what's built) */
    private static final int GHOST_CHECKS = 6_000;

    /** a plain white texture for fills without a material (tinted by the vertex colour) */
    static final Identifier WHITE = Identifier.fromNamespaceAndPath("mcplanner", "textures/misc/white.png");

    private final Planner planner;
    private final Materials materials;

    // shapes of the current dimension (built off-thread per plan version)
    private List<Shape> shapes = List.of();
    private int shapesVersion = -1;
    private String shapesDimension;
    private int buildingVersion = -1;
    private String buildingDimension;

    private final Map<String, Entry> entries = new HashMap<>();
    private long prunedAt;
    private boolean warned;
    /** what to draw, prepared at the end of extraction and handed to Minecraft right before it collects it (same frame) */
    private Frame pending;
    private int ghostsInView;

    private static final class Frame {
        final List<Runnable> gizmos = new ArrayList<>();
        final List<FillDraw> fills = new ArrayList<>();
        final List<Backdrop> backdrops = new ArrayList<>();

        boolean isEmpty() {
            return gizmos.isEmpty() && fills.isEmpty() && backdrops.isEmpty();
        }
    }

    /** {@code hidden}: schematic blocks already built (left out), or null */
    private record FillDraw(Mesh mesh, Mesh.Visible visible, float alpha, java.util.BitSet hidden) {}

    /** the dark rectangle behind a label: corners in order, colour, and whether it shows through walls */
    private record Backdrop(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int argb, boolean onTop) {}

    static final class Entry {
        Shape shape;
        Mesh mesh;
        int style;
        long builtAt;
        long seenAt;
        boolean complete;
        double labelY;
        /** a schematic building drawn with its blocks (no walls / roof / storeys then) */
        boolean showsBlocks;
        /** its blocks, to hide the ones already built */
        Ghost ghost;
    }

    public Overlay(Planner planner) {
        this.planner = planner;
        this.materials = new Materials(planner.mc);
    }

    // ------------------------------------------------------------------ shapes

    /** Every client tick (also with the overlay off: the HUD and /mcplanner status use the shapes). */
    public void tick() {
        try {
            ensureShapes();
        } catch (RuntimeException e) {
            warnOnce(e);
        }
    }

    private void ensureShapes() {
        int version = planner.planVersion();
        String dim = planner.dimension();
        if ((version == shapesVersion && dim.equals(shapesDimension)) || (version == buildingVersion && dim.equals(buildingDimension))) return;
        List<PlanObject> objects = planner.here();
        if (objects.isEmpty()) {
            shapes = List.of();
            shapesVersion = version;
            shapesDimension = dim;
            buildingVersion = -1;
            entries.clear();
            return;
        }
        buildingVersion = version;
        buildingDimension = dim;
        CompletableFuture.supplyAsync(() -> {
            List<Shape> out = new ArrayList<>(objects.size());
            for (PlanObject o : objects) {
                try {
                    Shape s = Shape.of(o);
                    if (s != null) out.add(s);
                } catch (RuntimeException e) {
                    Planner.LOG.warn("MC Planner: couldn't prepare an object ({}) for drawing", o.kind.label(), e);
                    Planner.noteError("overlay: preparing an object", e);
                }
            }
            return out;
        }, planner.exec).whenComplete((list, t) -> planner.main.execute(() -> {
            if (buildingVersion != version || !dim.equals(buildingDimension)) return; // a newer build is on its way
            adopt(list == null ? List.of() : list);
            shapesVersion = version;
            shapesDimension = dim;
            buildingVersion = -1;
        }));
    }

    /** new shapes: objects whose geometry didn't change keep their mesh (a status change only recolours it) */
    private void adopt(List<Shape> next) {
        Map<String, Entry> keep = new HashMap<>();
        for (Shape s : next) {
            Entry e = entries.get(s.o.id);
            if (e != null && e.shape != null && sameGeometry(e.shape.o, s.o)) {
                e.shape = s;
                keep.put(s.o.id, e);
            }
        }
        entries.clear();
        entries.putAll(keep);
        shapes = next;
    }

    static boolean sameGeometry(PlanObject a, PlanObject b) {
        return a.kind == b.kind && a.y == b.y && a.width == b.width && a.height == b.height && a.storey == b.storey
                && java.util.Arrays.deepEquals(a.points, b.points);
    }

    /** The object whose footprint contains block column (x, z): buildings first, then the smallest area / path. */
    public PlanObject objectAt(int x, int z) {
        PlanObject best = null;
        long bestSize = Long.MAX_VALUE;
        int bestRank = 9;
        for (Shape s : shapes) {
            if (s.grid == null || !s.grid.has(x, z)) continue;
            if (!planner.config.isVisible(s.o)) continue;
            int rank = s.o.kind == Kind.BUILDING ? 0 : s.o.kind == Kind.AREA ? 1 : 2;
            long size = s.grid.count;
            if (rank < bestRank || (rank == bestRank && size < bestSize)) {
                best = s.o;
                bestSize = size;
                bestRank = rank;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ per frame

    /** End of level extraction: read the world (heights), cull, and prepare this frame's drawing. */
    public void extract(LevelExtractionContext ctx) {
        pending = null;
        try {
            Frame out = new Frame();
            prepare(ctx, out);
            pending = out.isEmpty() ? null : out;
        } catch (RuntimeException e) {
            warnOnce(e);
        }
    }

    /**
     * Right before Minecraft collects this frame's gizmos (LevelRenderEvents.BEFORE_GIZMOS): adds the lines and label
     * texts as gizmos and submits the fills and label backdrops as custom geometry.
     */
    public void submit(LevelRenderContext ctx) {
        Frame f = pending;
        pending = null;
        if (f == null) return;
        try {
            for (Runnable r : f.gizmos) r.run();
        } catch (RuntimeException e) {
            warnOnce(e);
        }
        try {
            submitGeometry(ctx, f);
        } catch (RuntimeException e) {
            warnOnce(e);
        }
    }

    private void submitGeometry(LevelRenderContext ctx, Frame f) {
        if (f.fills.isEmpty() && f.backdrops.isEmpty()) return;
        SubmitNodeCollector out = ctx.submitNodeCollector();
        PoseStack poses = ctx.poseStack();
        Vec3 cam = ctx.levelState().cameraRenderState.pos;
        double cx = cam.x, cy = cam.y, cz = cam.z;
        // banner patterns: translucent, entity vertex layout, no depth write (so water, glass and our own lines behind a
        // fill still show) — and shader packs know how to draw them
        RenderType atlasType = RenderTypes.bannerPattern(materials.atlas()), whiteType = RenderTypes.bannerPattern(WHITE);
        List<FillDraw> fills = f.fills;
        for (boolean atlas : new boolean[] {true, false}) {
            boolean any = false;
            for (FillDraw d : fills) any |= d.mesh().hasFills(atlas);
            if (!any) continue;
            // runs later in the frame (when Minecraft builds its buffers), so it guards itself
            out.submitCustomGeometry(poses, atlas ? atlasType : whiteType, (pose, vc) -> {
                try {
                    for (FillDraw d : fills) {
                        if (d.mesh().hasFills(atlas)) d.mesh().emitFills(pose, vc, atlas, d.visible(), d.alpha(), d.hidden(), cx, cy, cz);
                    }
                } catch (RuntimeException e) {
                    warnOnce(e);
                }
            });
        }
        for (boolean onTop : new boolean[] {false, true}) {
            List<Backdrop> list = new ArrayList<>();
            for (Backdrop b : f.backdrops) if (b.onTop() == onTop) list.add(b);
            if (list.isEmpty()) continue;
            out.submitCustomGeometry(poses, onTop ? RenderTypes.textBackgroundSeeThrough() : RenderTypes.textBackground(), (pose, vc) -> {
                try {
                    for (Backdrop b : list) backdrop(pose, vc, b, cx, cy, cz);
                } catch (RuntimeException e) {
                    warnOnce(e);
                }
            });
        }
    }

    /** a label backdrop, wound to face the camera (the text background pipeline culls back faces) */
    private static void backdrop(PoseStack.Pose pose, VertexConsumer vc, Backdrop b, double cx, double cy, double cz) {
        Vec3 n = b.b().subtract(b.a()).cross(b.d().subtract(b.a()));
        boolean facing = n.dot(new Vec3(cx, cy, cz).subtract(b.a())) >= 0;
        Vec3[] corners = facing ? new Vec3[] {b.a(), b.b(), b.c(), b.d()} : new Vec3[] {b.a(), b.d(), b.c(), b.b()};
        for (Vec3 v : corners) {
            vc.addVertex(pose, (float) (v.x - cx), (float) (v.y - cy), (float) (v.z - cz)).setColor(b.argb()).setLight(Paint.FULL_BRIGHT);
        }
    }

    private void warnOnce(RuntimeException e) {
        if (!warned) {
            Planner.LOG.warn("MC Planner overlay couldn't draw", e);
            Planner.noteError("overlay: drawing", e);
        }
        warned = true;
    }

    private void prepare(LevelExtractionContext ctx, Frame out) {
        ModConfig c = planner.config;
        PlanObject target = planner.navTarget();
        if (!c.overlay && target == null) return;
        if (planner.plan().isEmpty() || shapes.isEmpty()) return;

        ClientLevel level = ctx.level();
        Camera cam = ctx.camera();
        if (level == null || cam == null) return;
        Vec3 eye = cam.position();
        Frustum frustum = cam.getCullFrustum();
        long now = Util.getMillis();
        materials.check();
        Paint paint = materials.paint(level, c.textures);

        // what's in range, nearest first
        List<Shape> near = new ArrayList<>();
        Map<Shape, Double> dist = new HashMap<>();
        for (Shape s : shapes) {
            boolean isTarget = target != null && target.id.equals(s.o.id);
            if (!isTarget && (!c.overlay || !c.isVisible(s.o))) continue;
            double d = s.distanceTo(eye.x, eye.z);
            if (d > c.renderDistance && !isTarget) continue;
            near.add(s);
            dist.put(s, d);
        }
        near.sort(Comparator.comparingDouble(dist::get));

        Heights terrain = terrainHeights(level, eye);
        int budget = HEIGHT_BUDGET, ghostsSeen = 0;
        // every schematic in view gets its share of the checks (as many as were in view last frame)
        int ghostShare = Math.max(1_000, GHOST_CHECKS / Math.max(1, ghostsInView));
        int schematicsVersion = planner.schematics.version();
        int primitives = 0;
        float pulse = 0.7f + 0.3f * (float) Math.sin(now / 1000.0 * Math.PI * 1.3);
        List<Entry> labelled = new ArrayList<>();
        for (Shape s : near) {
            boolean isTarget = target != null && target.id.equals(s.o.id);
            Entry e = entries.computeIfAbsent(s.o.id, k -> new Entry());
            if (e.shape != s) {
                e.shape = s;
                e.mesh = null;
            }
            e.seenAt = now;
            Schematics.Entry se = s.o.kind == Kind.BUILDING ? planner.schematics.get(s.o.schematic) : null;
            boolean blocks = c.textures && se != null && se.state == Schematics.State.READY && se.data != null && se.data.count <= Ghost.MAX_BLOCKS;
            int style = (styleKey(s.o, c, isTarget) * 31 + materials.generation()) * 31 + (blocks ? 1 + 2 * schematicsVersion : 0);
            boolean onGround = s.o.kind == Kind.PATH || s.o.kind == Kind.AREA;
            boolean stale = e.mesh == null || e.style != style
                    || (onGround && now - e.builtAt > (e.complete ? HEIGHT_REFRESH_MS : HEIGHT_RETRY_MS));
            // heights are read with a budget per frame (nearest first); the rest follow in the next frames
            if (stale && (budget > 0 || !onGround)) {
                int[] used = {0};
                boolean[] complete = {true};
                Heights h = (x, z) -> {
                    used[0]++;
                    int y = terrain.at(x, z);
                    if (y == NONE) complete[0] = false;
                    return y;
                };
                Paint counted = new Paint() {
                    @Override
                    public Look look(String material, int x, int y, int z) {
                        return paint.look(material, x, y, z);
                    }

                    @Override
                    public int light(int x, int y, int z) {
                        used[0]++;
                        return paint.light(x, y, z);
                    }
                };
                e.showsBlocks = blocks;
                Ghost old = e.ghost;
                e.ghost = null;
                e.mesh = build(s, h, counted, c, isTarget, e);
                if (blocks) {
                    try {
                        long t0 = System.nanoTime();
                        e.ghost = Ghost.build(e.mesh, se.data, s.o.schematic, s.bounds.x0(), s.o.y, s.bounds.z0(), mix(0xFFFFFF, s.o.color & 0xFFFFFF, 0.15f),
                                Math.min(0.85f, textureAlpha(s.o, c)), materials.atlas(), level, planner.mc);
                        e.ghost.takeBuilt(old);
                        Planner.LOG.info("MC Planner: schematic building — {} blocks with faces, {} quads ({} ms)", e.ghost.size(), e.mesh.size(), (System.nanoTime() - t0) / 1_000_000);
                    } catch (RuntimeException ex) {
                        warnOnce(ex);
                    }
                }
                e.complete = complete[0];
                e.style = style;
                e.builtAt = now;
                budget -= used[0];
            }
            if (e.mesh == null || e.mesh.isEmpty()) continue;
            if (primitives > MAX_PRIMITIVES && !isTarget) continue;
            int[] count = {0};
            Mesh.Visible v = e.mesh.visible(frustum, eye.x, eye.z, isTarget ? Double.MAX_VALUE : c.renderDistance, FILL_RANGE, count);
            if (v == null) {
                if (isTarget) labelled.add(e);
                continue;
            }
            primitives += count[0];
            float alpha = s.o.status == Status.IN_PROGRESS ? pulse : 1f;
            Mesh m = e.mesh;
            boolean onTop = c.outlinesThroughWalls;
            if (m.hasLines()) {
                out.gizmos.add(() -> {
                    GizmoProperties props = Gizmos.addGizmo(m.gizmo(alpha, v));
                    if (onTop) props.setAlwaysOnTop();
                });
            }
            java.util.BitSet hidden = null;
            if (e.ghost != null) {
                e.ghost.check(level, ghostShare);
                ghostsSeen++;
                hidden = e.ghost.built;
            }
            if (m.hasFills(true) || m.hasFills(false)) out.fills.add(new FillDraw(m, v, alpha, hidden));
            labelled.add(e);
        }

        ghostsInView = ghostsSeen;
        if (c.labels || target != null) prepareLabels(labelled, eye, cam, c, target, out);
        if (target != null) prepareGuide(target, out);

        if (now - prunedAt > 5_000) { // forget meshes of objects we haven't been near for a while
            prunedAt = now;
            entries.values().removeIf(e -> now - e.seenAt > PRUNE_MS);
        }
    }

    /**
     * The ground height of a column: the first free block above the highest block that blocks movement or holds a fluid
     * (leaves don't count), or NONE when its chunk isn't loaded. Under a ceiling (the Nether) the heightmap would give
     * the roof, so there we look down from around the player's head instead.
     */
    @SuppressWarnings("deprecation") // blocksMotion(): the same test vanilla's heightmaps use
    private static Heights terrainHeights(ClientLevel level, Vec3 eye) {
        var chunks = level.getChunkSource();
        int minY = level.getMinY();
        if (!level.dimensionType().hasCeiling()) {
            return (x, z) -> {
                LevelChunk ch = chunks.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
                if (ch == null) return NONE;
                int y = ch.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
                return y <= minY ? NONE : y; // an empty column (the End's void)
            };
        }
        int top = Math.min(minY + level.dimensionType().logicalHeight() - 1, (int) Math.floor(eye.y) + 6);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return (x, z) -> {
            LevelChunk ch = chunks.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
            if (ch == null) return NONE;
            boolean openAbove = false;
            for (int y = top, stop = Math.max(minY, top - 48); y >= stop; y--) {
                BlockState st = ch.getBlockState(pos.set(x, y, z));
                boolean solid = st.blocksMotion() || !st.getFluidState().isEmpty();
                if (solid && openAbove) return y + 1;
                openAbove = !solid;
            }
            return NONE;
        };
    }

    // ------------------------------------------------------------------ meshes

    private static int styleKey(PlanObject o, ModConfig c, boolean target) {
        int h = o.color;
        h = h * 31 + o.status.ordinal();
        h = h * 31 + (target ? 1 : 0);
        h = h * 31 + Float.floatToIntBits(c.fillOpacity);
        h = h * 31 + Float.floatToIntBits(c.lineOpacity);
        h = h * 31 + (c.pathTiles ? 1 : 0) + (c.areaFill ? 2 : 0) + (c.textures ? 4 : 0);
        h = h * 31 + (o.material == null ? 0 : o.material.hashCode());
        if (o.schematic != null) h = h * 31 + java.util.Objects.hash(o.schematic.hash(), o.schematic.turns(), o.schematic.mirror());
        return h;
    }

    /** the object's colour as line / fill colours for its status */
    static int[] colors(PlanObject o, ModConfig c, boolean target) {
        int rgb = o.color & 0xFFFFFF;
        float line = c.lineOpacity, fill = c.fillOpacity;
        switch (o.status) {
            case IDEA -> {
                rgb = mix(rgb, 0xFFFFFF, 0.35f);
                line *= 0.6f;
                fill *= 0.6f;
            }
            case DONE -> {
                rgb = mix(rgb, 0x8FD18F, 0.55f);
                line *= 0.55f;
                fill *= 0.4f;
            }
            default -> { }
        }
        if (target) line = 1f;
        return new int[] {argb(line, rgb), argb(fill, rgb), argb(Math.min(1f, line * 0.75f), mix(rgb, 0xFFFFFF, 0.3f))};
    }

    /**
     * How solid textured fills are: more than plain colour so the block is recognisable (like the web app), still
     * faded for ideas and for what's already built. The "Fill" setting moves it up or down.
     */
    static float textureAlpha(PlanObject o, ModConfig c) {
        float a = Math.min(0.95f, 0.45f + c.fillOpacity);
        switch (o.status) {
            case IDEA -> a *= 0.65f;
            case DONE -> a *= 0.45f;
            default -> { }
        }
        return a;
    }

    /** a texture's colour: its biome tint with a touch of the object's colour, at {@code alpha} */
    static int textureColor(Paint.Skin skin, PlanObject o, float alpha) {
        int cast = mix(0xFFFFFF, o.color & 0xFFFFFF, 0.15f), t = skin.rgb();
        int r = ((t >> 16) & 0xFF) * ((cast >> 16) & 0xFF) / 255;
        int g = ((t >> 8) & 0xFF) * ((cast >> 8) & 0xFF) / 255;
        int b = (t & 0xFF) * (cast & 0xFF) / 255;
        return argb(alpha, (r << 16) | (g << 8) | b);
    }

    /**
     * The object's primitives; {@code h} gives the terrain height (first free block) of a column, or NONE;
     * {@code paint} the block textures of its material and the light.
     */
    static Mesh build(Shape s, Heights h, Paint paint, ModConfig c, boolean target, Entry e) {
        PlanObject o = s.o;
        int[] col = colors(o, c, target);
        int line = col[0], fill = col[1], soft = col[2];
        float w = target ? 4f : 2.5f;
        Mesh m = new Mesh();
        int lx = (int) Math.floor(s.labelX), lz = (int) Math.floor(s.labelZ);

        switch (o.kind) {
            case PATH -> {
                Paint.Look look = paint.look(o.material, lx, o.y, lz);
                if (look != null || c.pathTiles) ground(m, s, h, paint, look, o, c, fill);
                terrainOutline(m, s, h, o.y, line, w * 0.8f);
                double px = 0, py = NONE, pz = 0;
                for (int i = 0; i + 1 < s.centre.length; i += 2) {
                    double x = s.centre[i], z = s.centre[i + 1];
                    int y = h.at((int) Math.floor(x), (int) Math.floor(z));
                    if (y != NONE && py != NONE) m.line(px, py, pz, x, y + 0.06, z, soft, 1.5f);
                    px = x;
                    pz = z;
                    py = y == NONE ? NONE : y + 0.06;
                }
                int y = h.at(lx, lz);
                e.labelY = (y == NONE ? o.y : y) + 1.6;
            }
            case AREA -> {
                Paint.Look look = paint.look(o.material, lx, o.y, lz);
                if (look != null || c.areaFill) ground(m, s, h, paint, look, o, c, fill);
                terrainOutline(m, s, h, o.y, line, w);
                for (int i = 0; i + 1 < s.posts.length; i += 2) {
                    double x = s.posts[i], z = s.posts[i + 1];
                    int y = h.at((int) Math.floor(x), (int) Math.floor(z));
                    if (y == NONE) continue;
                    m.line(x, y, z, x, y + 3, z, line, w + 1);
                }
                int y = h.at(lx, lz);
                e.labelY = (y == NONE ? o.y : y) + 2.2;
            }
            case BUILDING -> {
                int base = o.y, top = o.y + Math.max(1, o.height);
                int[] seg = s.outline;
                if (seg.length == 0) { // too big to rasterise: its bounding box
                    m.box(s.bounds.x0(), base, s.bounds.z0(), s.bounds.x1() + 1, top, s.bounds.z1() + 1, line, w);
                } else {
                    Paint.Look look = paint.look(o.material, lx, top, lz);
                    int light = paint.light(lx, top, lz);
                    float texAlpha = textureAlpha(o, c) * 0.7f;
                    int wallFill = ARGB.multiplyAlpha(fill, 0.45f);
                    java.util.Set<Long> corners = new java.util.HashSet<>();
                    int storey = Math.max(1, o.storey);
                    boolean box = !e.showsBlocks; // a schematic's own blocks replace walls, roof and storey lines
                    for (int i = 0; i + 3 < seg.length; i += 4) {
                        int x0 = seg[i], z0 = seg[i + 1], x1 = seg[i + 2], z1 = seg[i + 3];
                        m.line(x0, base + 0.02, z0, x1, base + 0.02, z1, line, w);
                        m.line(x0, top, z0, x1, top, z1, line, w);
                        if (!box) {
                            corners.add(key(x0, z0));
                            corners.add(key(x1, z1));
                            continue;
                        }
                        for (double yy = base + storey; yy < top - 0.01; yy += storey) m.line(x0, yy, z0, x1, yy, z1, soft, 1.5f);
                        if (look == null) m.wall(x0, z0, x1, z1, base, top, null, wallFill, light);
                        else texturedWall(m, x0, z0, x1, z1, base, top, look.side(), textureColor(look.side(), o, texAlpha), light);
                        corners.add(key(x0, z0));
                        corners.add(key(x1, z1));
                    }
                    for (long k : corners) {
                        int x = (int) (k >> 32), z = (int) k;
                        m.line(x, base, z, x, top, z, line, w);
                    }
                    if (!box) {
                        // nothing: the blocks are the roof
                    } else if (look == null) roof(m, s, top, wallFill, light);
                    else {
                        int roofColor = textureColor(look.top(), o, texAlpha);
                        for (int i = 0; i + 1 < s.tiles.length; i += 2) m.tile(s.tiles[i], top, s.tiles[i + 1], look.top(), roofColor, light, true);
                    }
                }
                e.labelY = top + 0.8;
            }
            case MARKER -> {
                int x = o.points[0][0], z = o.points[0][1], y = o.y;
                m.box(x, y, z, x + 1, y + 1, z + 1, line, w);
                m.line(x + 0.5, y + 1, z + 0.5, x + 0.5, y + 1 + MARKER_BEAM, z + 0.5, line, w + 1.5f);
                double t = y + 1 + MARKER_BEAM;
                m.line(x + 0.5, t + 0.6, z + 0.5, x + 0.9, t, z + 0.5, line, w);
                m.line(x + 0.9, t, z + 0.5, x + 0.5, t - 0.6, z + 0.5, line, w);
                m.line(x + 0.5, t - 0.6, z + 0.5, x + 0.1, t, z + 0.5, line, w);
                m.line(x + 0.1, t, z + 0.5, x + 0.5, t + 0.6, z + 0.5, line, w);
                e.labelY = t + 1.2;
            }
        }
        return m;
    }

    /**
     * Path tiles / area fill: one tile per covered block on the ground — the material's top texture (with farmland
     * under crops), or the plain colour.
     */
    private static void ground(Mesh m, Shape s, Heights h, Paint paint, Paint.Look look, PlanObject o, ModConfig c, int fill) {
        float a = look == null ? 0 : textureAlpha(o, c);
        int topColor = look == null ? fill : textureColor(look.top(), o, a);
        int underColor = look == null || look.under() == null ? 0 : textureColor(look.under(), o, a);
        for (int i = 0; i + 1 < s.tiles.length; i += 2) {
            int x = s.tiles[i], z = s.tiles[i + 1];
            int y = h.at(x, z);
            if (y == NONE) continue;
            int light = paint.light(x, y, z);
            if (look == null) m.tile(x, y + 0.02, z, null, fill, light, false);
            else {
                if (look.under() != null) m.tile(x, y + 0.015, z, look.under(), underColor, light, false);
                m.tile(x, y + (look.under() != null ? 0.03 : 0.02), z, look.top(), topColor, light, false);
            }
        }
    }

    /** a wall segment as one quad per block (a block texture repeats once per block) */
    private static void texturedWall(Mesh m, int x0, int z0, int x1, int z1, int base, int top, Paint.Skin skin, int color, int light) {
        int n = Math.abs(x1 - x0) + Math.abs(z1 - z0);
        int dx = Integer.signum(x1 - x0), dz = Integer.signum(z1 - z0);
        for (int k = 0; k < n; k++) {
            int ax = x0 + dx * k, az = z0 + dz * k;
            for (int y = base; y < top; y++) m.wall(ax, az, ax + dx, az + dz, y, y + 1, skin, color, light);
        }
    }

    /** a plain roof: the footprint as rectangles (runs of covered blocks per row, merged with the rows below) */
    private static void roof(Mesh m, Shape s, int top, int color, int light) {
        if (s.tiles.length == 0 || s.grid == null) return;
        var b = s.bounds;
        Map<Long, int[]> open = new HashMap<>(); // run (x0, x1) → [startZ]
        for (int z = b.z0(); z <= b.z1() + 1; z++) {
            Map<Long, int[]> next = new HashMap<>();
            if (z <= b.z1()) {
                int x = b.x0();
                while (x <= b.x1()) {
                    if (!s.grid.has(x, z)) {
                        x++;
                        continue;
                    }
                    int start = x;
                    while (x <= b.x1() && s.grid.has(x, z)) x++;
                    long k = key(start, x);
                    int[] r = open.remove(k);
                    next.put(k, r != null ? r : new int[] {z});
                }
            }
            for (Map.Entry<Long, int[]> en : open.entrySet()) { // runs that ended at this row
                int x0 = (int) (en.getKey() >> 32), x1 = (int) (long) en.getKey();
                m.rect(x0, en.getValue()[0], x1, z, top, color, light);
            }
            open = next;
        }
    }

    /** outline along block edges at the height of the covered block next to each edge, joined by short risers */
    private static void terrainOutline(Mesh m, Shape s, Heights h, int fallbackY, int color, float width) {
        int[] ue = s.unitEdges;
        if (ue.length == 0) {
            int[] seg = s.outline;
            int y = h.at((int) Math.floor(s.labelX), (int) Math.floor(s.labelZ));
            double yy = (y == NONE ? fallbackY : y) + 0.05;
            for (int i = 0; i + 3 < seg.length; i += 4) m.line(seg[i], yy, seg[i + 1], seg[i + 2], yy, seg[i + 3], color, width);
            return;
        }
        Map<Long, int[]> ends = new HashMap<>();
        for (int i = 0; i + 5 < ue.length; i += 6) {
            int y = h.at(ue[i + 4], ue[i + 5]);
            if (y == NONE) continue;
            double yy = y + 0.05;
            m.line(ue[i], yy, ue[i + 1], ue[i + 2], yy, ue[i + 3], color, width);
            span(ends, key(ue[i], ue[i + 1]), y);
            span(ends, key(ue[i + 2], ue[i + 3]), y);
        }
        for (Map.Entry<Long, int[]> en : ends.entrySet()) {
            int[] mm = en.getValue();
            if (mm[0] == mm[1]) continue;
            int x = (int) (en.getKey() >> 32), z = (int) (long) en.getKey();
            m.line(x, mm[0] + 0.05, z, x, mm[1] + 0.05, z, color, width);
        }
    }

    private static void span(Map<Long, int[]> ends, long k, int y) {
        int[] mm = ends.get(k);
        if (mm == null) ends.put(k, new int[] {y, y});
        else {
            if (y < mm[0]) mm[0] = y;
            if (y > mm[1]) mm[1] = y;
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    @FunctionalInterface
    interface Heights {
        int at(int x, int z);
    }

    // ------------------------------------------------------------------ labels & navigation

    private void prepareLabels(List<Entry> labelled, Vec3 eye, Camera cam, ModConfig c, PlanObject target, Frame out) {
        Minecraft mc = planner.mc;
        Vector3fc up = cam.upVector(), left = cam.leftVector(), fwd = cam.forwardVector();
        int shown = 0;
        for (Entry e : labelled) {
            Shape s = e.shape;
            boolean isTarget = target != null && target.id.equals(s.o.id);
            if (!c.labels && !isTarget) continue;
            Vec3 at = new Vec3(s.labelX, e.labelY, s.labelZ);
            double d = at.distanceTo(eye);
            if (!isTarget && (d > c.labelDistance || shown >= MAX_LABELS)) continue;
            shown++;
            String name = Texts.ellipsize(s.o.name.isBlank() ? s.o.kind.label() : s.o.name, 40);
            Schematics.Entry se = planner.schematics.get(s.o.schematic);
            String sub = s.o.kind.label() + " · " + s.o.status.label() + " · " + Texts.distance(d)
                    + (se != null && se.state == Schematics.State.MISSING ? " · schematic file missing" : "");
            float scale = (float) Math.min(4.0, 0.4 * Math.max(1.0, d / 12.0));
            double px = scale / 16.0; // one font pixel in blocks
            int widthPx = Math.max(mc.font.width(name), mc.font.width(sub));
            boolean onTop = c.labelsThroughWalls || isTarget;

            // dark backdrop behind both lines (slightly further from the camera than the text)
            double halfW = (widthPx / 2.0 + 3) * px, topY = 12 * px, botY = -11 * px;
            Vec3 back = at.add(fwd.x() * 0.03, fwd.y() * 0.03, fwd.z() * 0.03);
            Vec3 a = offset(back, left, up, halfW, topY), b = offset(back, left, up, -halfW, topY);
            Vec3 cc = offset(back, left, up, -halfW, botY), dd = offset(back, left, up, halfW, botY);
            int nameColor = isTarget ? 0xFFFFE680 : 0xFFFFFFFF;
            Vec3 namePos = offset(at, left, up, 0, 11 * px), subPos = offset(at, left, up, 0, -1 * px);
            out.backdrops.add(new Backdrop(a, b, cc, dd, 0x70000000, onTop));
            out.gizmos.add(() -> {
                GizmoProperties p1 = Gizmos.billboardText(name, namePos, TextGizmo.Style.forColorAndCentered(nameColor).withScale(scale));
                GizmoProperties p2 = Gizmos.billboardText(sub, subPos, TextGizmo.Style.forColorAndCentered(0xFFC8C8C8).withScale(scale));
                if (onTop) {
                    p1.setAlwaysOnTop();
                    p2.setAlwaysOnTop();
                }
            });
        }
    }

    private static Vec3 offset(Vec3 p, Vector3fc left, Vector3fc up, double l, double u) {
        return new Vec3(p.x + left.x() * l + up.x() * u, p.y + left.y() * l + up.y() * u, p.z + left.z() * l + up.z() * u);
    }

    /** a line from the player towards the navigation target and a tall beam on it */
    private void prepareGuide(PlanObject target, Frame out) {
        Minecraft mc = planner.mc;
        if (mc.player == null || !target.dimension.equals(planner.dimension())) return;
        Entry e = entries.get(target.id);
        if (e == null || e.shape == null) return;
        Vec3 goal = new Vec3(e.shape.labelX, e.labelY, e.shape.labelZ);
        Vec3 feet = mc.player.position().add(0, 0.15, 0);
        Vec3 dir = goal.subtract(feet);
        double len = dir.length();
        if (len < 4) return;
        Vec3 end = feet.add(dir.scale(Math.min(len, 16) / len));
        int col = ARGB.color(220, 255, 230, 128);
        Vec3 start = feet.add(dir.scale(1.2 / len));
        out.gizmos.add(() -> {
            Gizmos.arrow(start, end, col, 2.5f).setAlwaysOnTop();
            Gizmos.line(goal, goal.add(0, 48, 0), ARGB.color(140, 255, 230, 128), 3f).setAlwaysOnTop();
        });
    }

    // ------------------------------------------------------------------ helpers

    private static int argb(float a, int rgb) {
        return ARGB.color(Math.max(0, Math.min(255, Math.round(a * 255))), (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
