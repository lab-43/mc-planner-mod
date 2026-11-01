package dev.lab43.mcplanner.core;

import java.nio.file.Path;

/**
 * A plan file found on disk by {@link LocalPlans#scan}.
 *
 * @param displayName file name without .mcplan / .json
 * @param modified    last modified, epoch ms
 * @param size        bytes
 */
public record LocalPlanFile(Path path, String displayName, long modified, long size) {}
