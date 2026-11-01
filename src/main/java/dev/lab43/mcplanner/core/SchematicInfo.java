package dev.lab43.mcplanner.core;

import java.util.List;

/**
 * A building made from an imported schematic: only what the file is (sha256), its size and orientation
 * and the bill of materials — never the file or its blocks (port of the web's {@code SchematicRef}).
 *
 * @param hash      sha256 of the file (64 hex chars)
 * @param file      original file name
 * @param size      {x (width), y (height), z (length)} as saved in the file, before turning
 * @param turns     quarter turns clockwise seen from above (0..3)
 * @param mirror    mirrored along x before turning
 * @param blocks    non-air blocks
 * @param format    "litematic" | "sponge" | "mcedit" | "structure"
 * @param author    creator from the file's metadata (nullable)
 * @param materials item id → count, largest first (empty when unknown)
 */
public record SchematicInfo(String hash, String file, int[] size, int turns, boolean mirror, int blocks,
                            String format, String author, List<Material> materials) {

    /** One line of the bill of materials. */
    public record Material(String id, int count) {}

    /** Footprint size {x, z} after turning (port of {@code schematicDims}). */
    public int[] dims() {
        int w = size[0], l = size[2];
        return (turns & 1) != 0 ? new int[] {l, w} : new int[] {w, l};
    }

    /** Height in blocks. */
    public int height() {
        return size[1];
    }
}
