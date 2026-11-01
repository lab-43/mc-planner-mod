package dev.lab43.mcplanner.core;

/**
 * Block counts for one object (port of the web's {@code PlanCounts}). Fields the web leaves undefined for a
 * kind are 0 here (e.g. {@code length} for areas, {@code ring}…{@code shell} for paths and areas).
 *
 * @param blocks       blocks covered: area = columns, path = blocks to pave, building = footprint, marker = 1
 * @param w            bounding size along x in blocks
 * @param d            bounding size along z in blocks
 * @param length       path: length along the centre line (rounded)
 * @param ring         building: blocks in the one-block wall ring
 * @param walls        building: ring × height
 * @param floor        building: floor blocks per storey (inside the walls)
 * @param storeys      building: number of storeys
 * @param storeyHeight building: blocks per storey
 * @param roof         building: roof blocks (= footprint)
 * @param volume       building: footprint × height
 * @param shell        building: walls + floors of the upper storeys + roof
 * @param approx       true when the shape was too large to count exactly
 */
public record PlanCounts(long blocks, int w, int d, long length, long ring, long walls, long floor, int storeys,
                         int storeyHeight, long roof, long volume, long shell, boolean approx) {

    static final PlanCounts EMPTY = new PlanCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
}
