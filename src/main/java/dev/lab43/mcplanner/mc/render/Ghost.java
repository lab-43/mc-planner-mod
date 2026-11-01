package dev.lab43.mcplanner.mc.render;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.lab43.mcplanner.core.Schematic;
import dev.lab43.mcplanner.core.SchematicInfo;
import dev.lab43.mcplanner.mc.Planner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;

import org.joml.Vector3fc;

/**
 * A schematic building's blocks as see-through "ghost" blocks with the game's own block models — like the app shows
 * them, and like Litematica. Placed the way the app places it: the file's box from the footprint's min corner at the
 * building's y, mirrored along x and then turned clockwise.
 *
 * <p>Faces hidden by a neighbouring block of the schematic are left out. Blocks that are already built in the world
 * (the same block, facing the same way) disappear, so what's left is what's still to build; this is re-checked a few
 * thousand blocks per frame. Ghost blocks are a hair bigger than real ones (0.2 %), so where the world has a different
 * block their faces lie in front of it instead of flickering with it.
 */
final class Ghost {
    /** properties that follow from neighbours or use, not from how a block was placed: ignored for "already built" */
    private static final Set<String> LOOSE = Set.of("shape", "north", "east", "south", "west", "up", "down", "distance", "persistent",
            "snowy", "waterlogged", "power", "powered", "occupied", "honey_level", "age", "moisture", "stage", "lit", "open", "in_wall",
            "attached", "disarmed", "triggered", "enabled", "has_book", "has_record", "note", "instrument", "level", "bites", "candles",
            "pickles", "eggs", "hatch", "berries", "tilt", "drag", "bottom", "leaves", "segment_amount", "flower_amount");

    /** ghost blocks are scaled by this around their centre */
    private static final float GROW = 1.004f;

    /** larger schematics are shown as their box (building them would stall the game for too long) */
    static final int MAX_BLOCKS = 600_000;

    /** blocks renamed between Minecraft versions (both ways), as in the app's resolveBlockName */
    private static final Map<String, String> RENAME = new HashMap<>();

    static {
        String[][] pairs = {{"minecraft:grass", "minecraft:short_grass"}, {"minecraft:grass_path", "minecraft:dirt_path"},
                {"minecraft:chain", "minecraft:iron_chain"}, {"minecraft:sign", "minecraft:oak_sign"}, {"minecraft:wall_sign", "minecraft:oak_wall_sign"}};
        for (String[] p : pairs) {
            RENAME.put(p[0], p[1]);
            RENAME.put(p[1], p[0]);
        }
    }

    /** world position (BlockPos.asLong) and the state that should be there, per block with faces */
    final long[] pos;
    final BlockState[] expected;
    /** blocks already built (left out when drawing) */
    final BitSet built;
    private int cursor;

    /** after a rebuild at the same spot: keep what was already known to be built (no flash of finished blocks) */
    void takeBuilt(Ghost old) {
        if (old != null && java.util.Arrays.equals(old.pos, pos)) {
            built.or(old.built);
            cursor = old.cursor;
        }
    }

    private Ghost(long[] pos, BlockState[] expected) {
        this.pos = pos;
        this.expected = expected;
        this.built = new BitSet(pos.length);
    }

    int size() {
        return pos.length;
    }

    /** Checks up to {@code budget} blocks against the world (round-robin); returns how many were checked. */
    int check(ClientLevel level, int budget) {
        int n = Math.min(budget, pos.length);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k < n; k++) {
            if (cursor >= pos.length) cursor = 0;
            int i = cursor++;
            p.set(BlockPos.getX(pos[i]), BlockPos.getY(pos[i]), BlockPos.getZ(pos[i]));
            built.set(i, matches(level.getBlockState(p), expected[i]));
        }
        return n;
    }

    /** the same block, placed the same way (properties that follow from neighbours or use don't count) */
    static boolean matches(BlockState world, BlockState want) {
        if (world == want) return true;
        if (world.getBlock() != want.getBlock()) {
            // water / lava placed by the schematic: any of it counts
            FluidState wf = want.getFluidState(), f = world.getFluidState();
            return !wf.isEmpty() && want.getRenderShape() == RenderShape.INVISIBLE && !f.isEmpty() && f.getType().isSame(wf.getType());
        }
        for (Property<?> prop : want.getProperties()) {
            if (LOOSE.contains(prop.getName()) || !world.hasProperty(prop)) continue;
            if (!world.getValue(prop).equals(want.getValue(prop))) return false;
        }
        return true;
    }

    /**
     * Adds the schematic's blocks to {@code m} (as fills, grouped per block) and returns what's needed to hide the
     * built ones. (x0, z0): min corner of the footprint; y0: the building's y.
     */
    static Ghost build(Mesh m, Schematic s, SchematicInfo ref, int x0, int y0, int z0, int rgbCast, float alpha, Identifier atlas,
                       ClientLevel level, Minecraft mc) {
        Placement pl = new Placement(s.w, s.l, ref.turns(), ref.mirror(), x0, y0, z0);
        BlockState[] states = states(s.palette, ref.turns(), ref.mirror());
        BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
        BlockPos centre = new BlockPos(x0 + pl.worldW() / 2, y0 + s.h / 2, z0 + pl.worldL() / 2);
        Map<Long, Integer> tints = new HashMap<>();
        Direction[] dirs = Direction.values();
        // world direction → step in the file's coordinates
        int[][] step = new int[dirs.length][];
        for (int d = 0; d < dirs.length; d++) step[d] = pl.localStep(dirs[d].getStepX(), dirs[d].getStepY(), dirs[d].getStepZ());

        List<Long> posList = new ArrayList<>();
        List<BlockState> wantList = new ArrayList<>();
        List<BlockStateModelPart> parts = new ArrayList<>();
        BlockState[] around = new BlockState[dirs.length];
        RandomSource random = RandomSource.create();
        for (int y = 0; y < s.h; y++) {
            for (int z = 0; z < s.l; z++) {
                for (int x = 0; x < s.w; x++) {
                    int idx = s.blocks[(y * s.l + z) * s.w + x];
                    if (idx == 0) continue;
                    BlockState st = states[idx];
                    if (st == null) continue;
                    for (int d = 0; d < dirs.length; d++) {
                        int n = s.at(x + step[d][0], y + step[d][1], z + step[d][2]);
                        around[d] = n == 0 ? null : states[n];
                    }
                    int wx = pl.worldX(x, z), wy = y0 + y, wz = pl.worldZ(x, z);
                    int light = LightCoordsUtil.pack(Math.min(15, Math.max(0, st.getLightEmission())), 15);
                    int group = posList.size();
                    int before = m.size();
                    m.group(group);
                    FluidState fluid = st.getFluidState();
                    if (st.getRenderShape() == RenderShape.INVISIBLE) {
                        if (!fluid.isEmpty()) fluid(m, st, fluid, around, dirs, wx, wy, wz, idx, tints, rgbCast, alpha, light, atlas, level, centre, mc);
                    } else {
                        BlockStateModel model = models.get(st);
                        if (model != null && model != models.missingModel()) {
                            parts.clear();
                            random.setSeed(st.getSeed(new BlockPos(wx, wy, wz)));
                            model.collectParts(random, parts);
                            for (BlockStateModelPart part : parts) {
                                for (int d = 0; d < dirs.length; d++) {
                                    BlockState n = around[d];
                                    if (n != null && !Block.shouldRenderFace(st, n, dirs[d])) continue;
                                    for (BakedQuad q : part.getQuads(dirs[d])) quad(m, q, st, wx, wy, wz, idx, tints, rgbCast, alpha, light, atlas, level, centre, mc);
                                }
                                for (BakedQuad q : part.getQuads(null)) quad(m, q, st, wx, wy, wz, idx, tints, rgbCast, alpha, light, atlas, level, centre, mc);
                            }
                        }
                    }
                    if (m.size() > before) {
                        posList.add(BlockPos.asLong(wx, wy, wz));
                        wantList.add(st);
                    }
                }
            }
        }
        m.group(-1);
        long[] pos = new long[posList.size()];
        for (int i = 0; i < pos.length; i++) pos[i] = posList.get(i);
        return new Ghost(pos, wantList.toArray(new BlockState[0]));
    }

    private static void quad(Mesh m, BakedQuad q, BlockState st, int wx, int wy, int wz, int idx, Map<Long, Integer> tints,
                             int rgbCast, float alpha, int light, Identifier atlas, ClientLevel level, BlockPos centre, Minecraft mc) {
        var info = q.materialInfo();
        TextureAtlasSprite sprite = info.sprite();
        if (sprite == null || !atlas.equals(sprite.atlasLocation())) return; // drawn with the block atlas only
        int rgb = 0xFFFFFF;
        if (info.isTinted()) rgb = tint(idx, info.tintIndex(), () -> mc.getBlockColors().getTintSource(st, info.tintIndex()), st, tints, level, centre);
        Vector3fc a = q.position0(), b = q.position1(), c = q.position2(), d = q.position3();
        float[] uv = {
                UVPair.unpackU(q.packedUV0()), UVPair.unpackV(q.packedUV0()), UVPair.unpackU(q.packedUV1()), UVPair.unpackV(q.packedUV1()),
                UVPair.unpackU(q.packedUV2()), UVPair.unpackV(q.packedUV2()), UVPair.unpackU(q.packedUV3()), UVPair.unpackV(q.packedUV3())};
        put(m, wx, wy, wz, new float[] {a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), c.x(), c.y(), c.z(), d.x(), d.y(), d.z()}, uv,
                color(rgb, rgbCast, alpha), light);
    }

    /** a quad given in block coordinates (0…1), grown a hair around the block's centre */
    private static void put(Mesh m, int wx, int wy, int wz, float[] p, float[] uv, int color, int light) {
        double[] g = new double[12];
        for (int k = 0; k < 12; k++) g[k] = (p[k] - 0.5f) * GROW + 0.5f + (k % 3 == 0 ? wx : k % 3 == 1 ? wy : wz);
        m.quad(g[0], g[1], g[2], g[3], g[4], g[5], g[6], g[7], g[8], g[9], g[10], g[11], uv, true, color, light, false);
    }

    /** water / lava: the still texture on every face that isn't against the same fluid or a solid block */
    private static void fluid(Mesh m, BlockState st, FluidState fluid, BlockState[] around, Direction[] dirs, int wx, int wy, int wz, int idx,
                              Map<Long, Integer> tints, int rgbCast, float alpha, int light, Identifier atlas, ClientLevel level, BlockPos centre,
                              Minecraft mc) {
        FluidModel fm = mc.getModelManager().getFluidStateModelSet().get(fluid);
        if (fm == null || fm.stillMaterial() == null) return;
        TextureAtlasSprite sp = fm.stillMaterial().sprite();
        if (sp == null || !atlas.equals(sp.atlasLocation())) return;
        int rgb = fm.tintSource() == null ? 0xFFFFFF : tint(idx, 0, fm::tintSource, st, tints, level, centre);
        int color = color(rgb, rgbCast, alpha);
        BlockState above = around[Direction.UP.ordinal()];
        float h = above != null && above.getFluidState().getType().isSame(fluid.getType()) ? 1f : Math.max(0.1f, fluid.getOwnHeight());
        float[] uv = {sp.getU0(), sp.getV0(), sp.getU0(), sp.getV1(), sp.getU1(), sp.getV1(), sp.getU1(), sp.getV0()};
        for (int d = 0; d < dirs.length; d++) {
            BlockState n = around[d];
            if (n != null && (n.getFluidState().getType().isSame(fluid.getType()) || n.isSolidRender())) continue;
            float[] f = switch (dirs[d]) { // corners counter-clockwise seen from outside
                case UP -> new float[] {0, h, 0, 0, h, 1, 1, h, 1, 1, h, 0};
                case DOWN -> new float[] {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1};
                case NORTH -> new float[] {1, h, 0, 1, 0, 0, 0, 0, 0, 0, h, 0};
                case SOUTH -> new float[] {0, h, 1, 0, 0, 1, 1, 0, 1, 1, h, 1};
                case WEST -> new float[] {0, h, 0, 0, 0, 0, 0, 0, 1, 0, h, 1};
                case EAST -> new float[] {1, h, 1, 1, 0, 1, 1, 0, 0, 1, h, 0};
            };
            put(m, wx, wy, wz, f, uv, color, light);
        }
    }

    /** biome tint of a palette entry's tint layer, taken once at the schematic's centre */
    private static int tint(int idx, int layer, java.util.function.Supplier<BlockTintSource> source, BlockState st, Map<Long, Integer> tints,
                            ClientLevel level, BlockPos centre) {
        long key = ((long) idx << 8) | (layer & 0xFF);
        Integer hit = tints.get(key);
        if (hit != null) return hit;
        int rgb = 0xFFFFFF;
        try {
            BlockTintSource src = source.get();
            if (src != null) rgb = src.colorInWorld(st, level, centre) & 0xFFFFFF;
        } catch (RuntimeException e) {
            rgb = 0xFFFFFF;
        }
        tints.put(key, rgb);
        return rgb;
    }

    private static int color(int rgb, int cast, float alpha) {
        int r = ((rgb >> 16) & 0xFF) * ((cast >> 16) & 0xFF) / 255;
        int g = ((rgb >> 8) & 0xFF) * ((cast >> 8) & 0xFF) / 255;
        int b = (rgb & 0xFF) * (cast & 0xFF) / 255;
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ---------------- block states ----------------

    /** the palette as game block states, mirrored and turned like the building (null: air or unknown) */
    static BlockState[] states(List<String> palette, int turns, boolean mirror) {
        Rotation rot = switch (((turns % 4) + 4) % 4) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        BlockState[] out = new BlockState[palette.size()];
        int unknown = 0;
        for (int i = 1; i < out.length; i++) {
            BlockState st = state(palette.get(i));
            if (st == null) {
                unknown++;
                continue;
            }
            if (mirror) st = st.mirror(Mirror.FRONT_BACK);
            out[i] = st.rotate(rot);
        }
        if (unknown > 0) Planner.LOG.info("MC Planner: {} block types of a schematic aren't in this game (other version or mods), left out", unknown);
        return out;
    }

    /** "minecraft:oak_stairs[facing=east,half=bottom]" → the block state (unknown properties are ignored) */
    static BlockState state(String key) {
        Schematic.State k = Schematic.parseKey(key);
        Block block = block(k.name());
        if (block == null && RENAME.containsKey(k.name())) block = block(RENAME.get(k.name()));
        if (block == null) return null;
        BlockState st = block.defaultBlockState();
        if (st.isAir()) return null;
        for (Map.Entry<String, String> p : k.props().entrySet()) {
            Property<?> prop = block.getStateDefinition().getProperty(p.getKey());
            if (prop != null) st = with(st, prop, p.getValue());
        }
        return st;
    }

    private static Block block(String name) {
        try {
            return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(name)).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String value) {
        return p.getValue(value).map(v -> s.setValue(p, v)).orElse(s);
    }

    // ---------------- placement ----------------

    /**
     * File coordinates → world, as the app does it (StructureRenderer orient): mirror along x, then quarter turns
     * clockwise, the turned box starting at (x0, z0).
     */
    record Placement(int w, int l, int turns, boolean mirror, int x0, int y0, int z0) {
        Placement {
            turns = ((turns % 4) + 4) % 4;
        }

        int worldW() {
            return (turns & 1) != 0 ? l : w;
        }

        int worldL() {
            return (turns & 1) != 0 ? w : l;
        }

        private int mx(int x) {
            return mirror ? w - 1 - x : x;
        }

        int worldX(int x, int z) {
            int mx = mx(x);
            return x0 + switch (turns) {
                case 1 -> l - 1 - z;
                case 2 -> w - 1 - mx;
                case 3 -> z;
                default -> mx;
            };
        }

        int worldZ(int x, int z) {
            int mx = mx(x);
            return z0 + switch (turns) {
                case 1 -> mx;
                case 2 -> l - 1 - z;
                case 3 -> w - 1 - mx;
                default -> z;
            };
        }

        /** a step in the world (dx, dy, dz) as a step in the file's coordinates */
        int[] localStep(int dx, int dy, int dz) {
            int dmx, lz;
            switch (turns) {
                case 1 -> { lz = -dx; dmx = dz; }
                case 2 -> { dmx = -dx; lz = -dz; }
                case 3 -> { lz = dx; dmx = -dz; }
                default -> { dmx = dx; lz = dz; }
            }
            return new int[] {mirror ? -dmx : dmx, dy, lz};
        }
    }
}
