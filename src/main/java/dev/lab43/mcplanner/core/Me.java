package dev.lab43.mcplanner.core;

import java.util.List;

/**
 * Answer of {@code GET /api/mod/me}.
 *
 * @param userId Lab43 user id
 * @param name   display name
 * @param color  person colour 0xRRGGBB (nullable)
 * @param scopes what the device token may do: {@link #SCOPE_READ}, {@link #SCOPE_WRITE} (never null)
 *
 * <p>The mod never asks about the person's MC Planner plan (Mojang's usage guidelines: a mod can't check whether a
 * player has an out-of-game product or service that changes what it does in the game). Servers from before that
 * change also send an {@code access} field; it is ignored.
 */
public record Me(String userId, String name, Integer color, List<String> scopes) {

    /** May read plans (every working device token has it). */
    public static final String SCOPE_READ = "mcplanner:read";
    /** May change statuses and add markers. */
    public static final String SCOPE_WRITE = "mcplanner:write";

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    /** The token may make changes (the world must still allow it: see {@link WorldInfo#canEdit()}). */
    public boolean canWrite() {
        return scopes.contains(SCOPE_WRITE);
    }
}
