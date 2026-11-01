package dev.lab43.mcplanner.core;

/**
 * Answer of {@code GET /api/mod/worlds/{room}/plan}.
 *
 * @param notModified 304: the plan didn't change since {@code etag} ({@code plan} is null then)
 * @param plan        the plan (null when notModified)
 * @param etag        ETag without quotes (send it back next time)
 * @param role        the person's role in this world (nullable)
 * @param paused      world is paused (read-only)
 * @param canEdit     the person may change the plan
 * @param name        world name from the wrapper (nullable)
 * @param pollSeconds how often to poll ({@code X-Poll-Interval}, default 10)
 * @param raw         the response body as received (for {@link PlanCache}; null when notModified)
 * @param fromCache   true when {@link PlanSync} delivered this from the offline cache, not the server
 */
public record PlanResponse(boolean notModified, Plan plan, String etag, String role, boolean paused, boolean canEdit,
                           String name, int pollSeconds, String raw, boolean fromCache) {}
