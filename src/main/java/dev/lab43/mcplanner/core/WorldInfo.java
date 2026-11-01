package dev.lab43.mcplanner.core;

/**
 * One shared world the signed-in person belongs to ({@code GET /api/mod/worlds}).
 *
 * @param roomId    room id used in the other API calls
 * @param name      world name
 * @param role      "owner" | "editor" | "viewer" … (nullable)
 * @param paused    the owner's plan doesn't keep this world live right now (read-only)
 * @param canEdit   this person may change the plan (status, markers)
 * @param ownerName display name of the owner (nullable)
 * @param members   number of members (0 when unknown)
 */
public record WorldInfo(String roomId, String name, String role, boolean paused, boolean canEdit, String ownerName,
                        int members) {}
