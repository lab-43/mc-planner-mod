package dev.lab43.mcplanner.core;

import java.util.List;

/**
 * Answer of {@code GET /api/mod/me}.
 *
 * @param userId Lab43 user id
 * @param name   display name
 * @param color  person colour 0xRRGGBB (nullable)
 * @param scopes what the device token may do: {@link #SCOPE_READ}, {@link #SCOPE_WRITE} (never null)
 * @param access the person's own MC Planner plan (plan files); null when the server doesn't say (before the access
 *               rule) — then everything works as before ({@link Access#UNRESTRICTED})
 */
public record Me(String userId, String name, Integer color, List<String> scopes, Access access) {
    public Me(String userId, String name, Integer color, List<String> scopes) {
        this(userId, name, color, scopes, null);
    }

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
