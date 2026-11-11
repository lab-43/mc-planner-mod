package dev.lab43.mcplanner.mc.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.lab43.mcplanner.mc.Planner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;

import org.joml.Vector3fc;

/**
 * The block textures of plan materials ("minecraft:gravel" → its top and side texture in the block atlas), picked the
 * same way as the web app does (packages/assets catalog.ts {@code blockMaterial}): the highest flat face is the top,
 * the most northern upright one the side; crops are shown fully grown, logs upright; water and lava use their still
 * texture; see-through plants get farmland (or grass, soul sand) under them. Biome tints (grass, leaves, water) come
 * from the world where the object is. Works for modded blocks too — whatever the game has loaded.
 *
 * <p>Only used on the render thread. The lookups are cached until the resources reload (new atlas → new coordinates;
 * {@link #generation()} changes so the overlay rebuilds its meshes).
 */
final class Materials {
    private final Minecraft mc;
    private final Map<String, Optional<Resolved>> cache = new HashMap<>();
    private Object models;
    private int generation;
    /** the block atlas texture (where stone's texture is) */
    private Identifier atlas = Identifier.withDefaultNamespace("textures/atlas/blocks.png");

    /** one texture of a block and how to tint it */
    private record Face(TextureAtlasSprite sprite, BlockState state, int tintIndex, BlockTintSource fluidTint) {}

    private record Resolved(Face top, Face side, Face under) {}

    Materials(Minecraft mc) {
        this.mc = mc;
    }

    /** changes whenever the textures were reloaded (resource packs) */
    int generation() {
        return generation;
    }

    /** the texture all block textures are on (for the render type) */
    Identifier atlas() {
        return atlas;
    }

    /** Once per frame before {@link #paint}: notices a resource reload. */
    void check() {
        BlockStateModelSet now = mc.getModelManager().getBlockStateModelSet();
        if (now != models) {
            models = now;
            cache.clear();
            generation++;
            try {
                atlas = now.getParticleMaterial(Blocks.STONE.defaultBlockState()).sprite().atlasLocation();
            } catch (RuntimeException e) {
                Planner.LOG.warn("MC Planner: couldn't find the block atlas", e);
                Planner.noteError("textures: the block atlas", e);
            }
        }
    }

    /** The paint for this frame: block textures (if {@code textures}) and the light of {@code level}. */
    Paint paint(ClientLevel level, boolean textures) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return new Paint() {
            @Override
            public Look look(String material, int x, int y, int z) {
                if (!textures || material == null || material.isBlank()) return null;
                Resolved r = resolve(material.strip(), 0);
                if (r == null) return null;
                pos.set(x, y, z);
                return new Look(skin(r.top(), level, pos), skin(r.side(), level, pos), r.under() == null ? null : skin(r.under(), level, pos));
            }

            @Override
            public int light(int x, int y, int z) {
                pos.set(x, y, z);
                return LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, pos), 15);
            }
        };
    }

    private Paint.Skin skin(Face f, ClientLevel level, BlockPos pos) {
        TextureAtlasSprite s = f.sprite();
        int rgb = 0xFFFFFF;
        try {
            if (f.fluidTint() != null) rgb = f.fluidTint().colorInWorld(f.state(), level, pos);
            else if (f.tintIndex() >= 0) {
                BlockTintSource src = mc.getBlockColors().getTintSource(f.state(), f.tintIndex());
                if (src != null) rgb = src.colorInWorld(f.state(), level, pos);
            }
        } catch (RuntimeException e) { // a modded tint that needs more than a level and a position
            rgb = 0xFFFFFF;
        }
        return new Paint.Skin(s.getU0(), s.getV0(), s.getU1(), s.getV1(), rgb & 0xFFFFFF);
    }

    private Resolved resolve(String id, int depth) {
        Optional<Resolved> hit = cache.get(id);
        if (hit != null) return hit.orElse(null);
        Resolved r = null;
        try {
            r = load(id, depth);
        } catch (RuntimeException e) {
            Planner.LOG.warn("MC Planner: couldn't read the textures of {}", id, e);
            Planner.noteError("textures: a block", e);
        }
        cache.put(id, Optional.ofNullable(r));
        return r;
    }

    private Resolved load(String id, int depth) {
        Identifier key;
        try {
            key = Identifier.parse(id.indexOf(':') < 0 ? "minecraft:" + id : id);
        } catch (RuntimeException e) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
        if (block == null) return null;
        BlockState state = shownState(block.defaultBlockState());
        if (state.isAir()) return null;

        // water, lava (and modded fluids): the still texture
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && state.getRenderShape() == RenderShape.INVISIBLE) {
            FluidModel fm = mc.getModelManager().getFluidStateModelSet().get(fluid);
            if (fm == null || fm.stillMaterial() == null || !onBlockAtlas(fm.stillMaterial().sprite())) return null;
            Face f = new Face(fm.stillMaterial().sprite(), state, -1, fm.tintSource());
            return new Resolved(f, f, null);
        }

        BlockStateModelSet set = mc.getModelManager().getBlockStateModelSet();
        BlockStateModel model = set.get(state);
        if (model == null || model == set.missingModel()) return null;
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            for (Direction d : Direction.values()) quads.addAll(part.getQuads(d));
            quads.addAll(part.getQuads(null));
        }
        quads.removeIf(q -> !onBlockAtlas(q.materialInfo().sprite()));
        if (quads.isEmpty()) { // no faces (block entities like chests): the particle texture
            TextureAtlasSprite p = model.particleMaterial() == null ? null : model.particleMaterial().sprite();
            if (p == null || !onBlockAtlas(p)) return null;
            Face f = new Face(p, state, -1, null);
            return new Resolved(f, f, null);
        }

        // flat quads by axis: the highest horizontal one is the top, the most northern upright one the side
        List<BakedQuad> tops = flat(quads, 1), sides = flat(quads, 2), sidesX = flat(quads, 0);
        tops.sort(Comparator.<BakedQuad>comparingDouble(q -> -q.position0().y()).thenComparingDouble(q -> -area(q)));
        sides.sort(Comparator.<BakedQuad>comparingDouble(q -> q.position0().z()).thenComparingDouble(q -> -area(q)));
        sidesX.sort(Comparator.<BakedQuad>comparingDouble(q -> -area(q)));
        List<BakedQuad> byArea = new ArrayList<>(quads);
        byArea.sort(Comparator.<BakedQuad>comparingDouble(q -> -area(q)));
        BakedQuad up = tops.isEmpty() ? byArea.get(0) : tops.get(0);
        BakedQuad side = !sides.isEmpty() ? sides.get(0) : !sidesX.isEmpty() ? sidesX.get(0) : up;

        // plants (no top face, see-through texture) stand on something: crops on farmland, nether wart on soul sand
        Face under = null;
        if (tops.isEmpty() && !up.materialInfo().sprite().transparency().isOpaque() && depth == 0) {
            String ground = block instanceof NetherWartBlock ? "minecraft:soul_sand" : hasAge(state) ? "minecraft:farmland" : "minecraft:grass_block";
            Resolved g = resolve(ground, depth + 1);
            if (g != null) under = g.top();
        }
        return new Resolved(face(up, state), face(side, state), under);
    }

    private static Face face(BakedQuad q, BlockState state) {
        var info = q.materialInfo();
        return new Face(info.sprite(), state, info.isTinted() ? info.tintIndex() : -1, null);
    }

    private boolean onBlockAtlas(TextureAtlasSprite s) {
        return s != null && atlas.equals(s.atlasLocation());
    }

    /** quads whose 4 corners share one coordinate on {@code axis} (0 x, 1 y, 2 z) */
    private static List<BakedQuad> flat(List<BakedQuad> quads, int axis) {
        List<BakedQuad> out = new ArrayList<>();
        for (BakedQuad q : quads) {
            float a = c(q.position0(), axis);
            if (a == c(q.position1(), axis) && a == c(q.position2(), axis) && a == c(q.position3(), axis)) out.add(q);
        }
        return out;
    }

    private static float c(Vector3fc v, int axis) {
        return axis == 0 ? v.x() : axis == 1 ? v.y() : v.z();
    }

    /** bounding area of a quad in the plane of its face (enough to pick the biggest) */
    private static double area(BakedQuad q) {
        float[] xs = {q.position0().x(), q.position1().x(), q.position2().x(), q.position3().x()};
        float[] ys = {q.position0().y(), q.position1().y(), q.position2().y(), q.position3().y()};
        float[] zs = {q.position0().z(), q.position1().z(), q.position2().z(), q.position3().z()};
        double sx = span(xs), sy = span(ys), sz = span(zs);
        return Math.max(sx * sz, Math.max(sx * sy, sy * sz));
    }

    private static double span(float[] a) {
        float lo = a[0], hi = a[0];
        for (float v : a) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return hi - lo;
    }

    /** the state a plan means by a block: crops fully grown, logs upright, slabs/stairs at the bottom, no water in it */
    private static BlockState shownState(BlockState state) {
        BlockState s = state;
        for (Property<?> p : state.getProperties()) {
            switch (p.getName()) {
                case "age" -> {
                    if (p instanceof IntegerProperty ip) s = s.setValue(ip, Collections.max(ip.getPossibleValues()));
                }
                case "axis" -> s = with(s, p, "y");
                case "half" -> s = with(with(s, p, "bottom"), p, "lower");
                case "type" -> s = with(s, p, "bottom");
                case "waterlogged" -> s = with(s, p, "false");
                default -> { }
            }
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String value) {
        Optional<T> v = p.getValue(value);
        return v.isPresent() ? s.setValue(p, v.get()) : s;
    }

    private static boolean hasAge(BlockState s) {
        for (Property<?> p : s.getProperties()) if (p.getName().equals("age")) return true;
        return false;
    }
}
