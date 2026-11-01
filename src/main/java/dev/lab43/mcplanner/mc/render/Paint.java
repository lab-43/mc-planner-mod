package dev.lab43.mcplanner.mc.render;

/**
 * What the fills are painted with: the block textures of an object's material (gravel on a path, water in a pond,
 * wheat on a field, stone bricks on a wall) and the light at a spot. {@link Overlay#build} only sees this interface,
 * so the geometry can be checked without a running game ({@link #PLAIN}); in the game it's {@link Materials}.
 */
interface Paint {
    /**
     * How a block material looks, or null when it should be drawn in the object's plain colour (no material, an
     * unknown block, or textures switched off). {@code x, y, z} is where it is (for the biome tint of grass, water …).
     */
    Look look(String material, int x, int y, int z);

    /**
     * Packed light coordinates for a fill at a block: its block light (torches) and full sky light — plans are lit like
     * open ground, also underground or before the chunk's light is known, and dim at night like the world.
     */
    int light(int x, int y, int z);

    /** no textures, full brightness */
    Paint PLAIN = new Paint() {
        @Override
        public Look look(String material, int x, int y, int z) {
            return null;
        }

        @Override
        public int light(int x, int y, int z) {
            return FULL_BRIGHT;
        }
    };

    /** block light 15, sky light 15 */
    int FULL_BRIGHT = 0xF000F0;

    /**
     * One texture of the block atlas: its corner coordinates and the colour it's multiplied with (biome tint of grass,
     * leaves, water; 0xFFFFFF = as it is).
     */
    record Skin(float u0, float v0, float u1, float v1, int rgb) {}

    /**
     * The textures of a block: on top (paths, areas, roofs), on the side (walls), and what's under a see-through top
     * (farmland under wheat), or null.
     */
    record Look(Skin top, Skin side, Skin under) {}
}
