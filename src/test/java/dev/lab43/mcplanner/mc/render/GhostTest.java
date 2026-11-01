package dev.lab43.mcplanner.mc.render;

import static dev.lab43.mcplanner.mc.render.MeshTest.check;
import static dev.lab43.mcplanner.mc.render.MeshTest.eq;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.Direction;

/**
 * Schematic ghosts without a running game: where each block of the file lands (mirror, quarter turns) and that block
 * states turn with it, block names from other versions, and when a block counts as built. Boots Minecraft's block
 * registry (about 10 s).
 */
final class GhostTest {
    private GhostTest() {}

    static void run() {
        placement();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        states();
        built();
    }

    static void placement() {
        int w = 3, l = 5;
        for (int turns = 0; turns < 4; turns++) {
            for (boolean mirror : new boolean[] {false, true}) {
                Ghost.Placement p = new Ghost.Placement(w, l, turns, mirror, 100, 64, -50);
                Set<Long> seen = new HashSet<>();
                boolean inside = true, steps = true;
                for (int x = 0; x < w; x++) {
                    for (int z = 0; z < l; z++) {
                        int wx = p.worldX(x, z), wz = p.worldZ(x, z);
                        seen.add(((long) wx << 32) | (wz & 0xFFFFFFFFL));
                        if (wx < 100 || wx >= 100 + p.worldW() || wz < -50 || wz >= -50 + p.worldL()) inside = false;
                        // a world step east/south leads to the file block the placement puts there
                        for (int[] d : new int[][] {{1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
                            int[] s = p.localStep(d[0], 0, d[1]);
                            int nx = x + s[0], nz = z + s[2];
                            if (nx < 0 || nz < 0 || nx >= w || nz >= l) continue;
                            if (p.worldX(nx, nz) != wx + d[0] || p.worldZ(nx, nz) != wz + d[1]) steps = false;
                        }
                    }
                }
                String what = "turns " + turns + (mirror ? " mirrored" : "");
                eq(seen.size(), w * l, what + ": every block lands on its own spot");
                check(inside, what + ": all inside the turned footprint from (x0, z0)");
                check(steps, what + ": world steps ↔ file steps agree");
                eq(p.localStep(0, 1, 0)[1], 1, what + ": up stays up");
            }
        }
        Ghost.Placement cw = new Ghost.Placement(3, 5, 1, false, 0, 0, 0);
        eq(cw.worldW(), 5, "a quarter turn swaps width and length");
        eq(List.of(cw.worldX(0, 0), cw.worldZ(0, 0)), List.of(4, 0), "turned clockwise: the file's north-west corner goes north-east");
        eq(List.of(cw.worldX(1, 0), cw.worldZ(1, 0)), List.of(4, 1), "…and the file's east is the world's south");
    }

    static void states() {
        String east = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]";
        BlockState s0 = Ghost.state(east);
        check(s0 != null && s0.getBlock() == Blocks.OAK_STAIRS, "block from its key");
        eq(s0.getValue(StairBlock.FACING), Direction.EAST, "properties applied");
        List<String> pal = List.of("minecraft:air", east, "minecraft:grass", "minecraft:not_a_block", "minecraft:stone[made_up=1]");
        eq(Ghost.states(pal, 1, false)[1].getValue(StairBlock.FACING), Direction.SOUTH, "turned clockwise: east → south (as the blocks move)");
        eq(Ghost.states(pal, 0, true)[1].getValue(StairBlock.FACING), Direction.WEST, "mirrored along x: east → west");
        eq(Ghost.states(pal, 1, true)[1].getValue(StairBlock.FACING), Direction.NORTH, "mirrored, then turned: east → west → north");
        BlockState[] st = Ghost.states(pal, 0, false);
        check(st[0] == null, "air: nothing");
        check(st[2] != null && st[2].getBlock() == Blocks.SHORT_GRASS, "an old name (grass) is found under its new one");
        check(st[3] == null, "an unknown block is left out");
        check(st[4] != null && st[4].getBlock() == Blocks.STONE, "unknown properties are ignored");
        eq(Ghost.state("minecraft:water[level=0]").getBlock(), Blocks.WATER, "water");
    }

    static void built() {
        BlockState want = Ghost.state("minecraft:oak_stairs[facing=east,half=bottom,shape=straight]");
        check(Ghost.matches(want.setValue(BlockStateProperties.STAIRS_SHAPE, net.minecraft.world.level.block.state.properties.StairsShape.OUTER_LEFT), want),
                "stairs that turned a corner by themselves count as built");
        check(!Ghost.matches(want.setValue(StairBlock.FACING, Direction.WEST), want), "facing the wrong way: not built");
        check(!Ghost.matches(Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState()), "another block: not built");
        check(Ghost.matches(Ghost.state("minecraft:water[level=3]"), Ghost.state("minecraft:water[level=0]")), "water is water");
        check(!Ghost.matches(Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState()), "no water yet: not built");
        check(Ghost.matches(Ghost.state("minecraft:oak_leaves[distance=3,persistent=false]"), Ghost.state("minecraft:oak_leaves[distance=1,persistent=true]")), "leaves");
    }
}
