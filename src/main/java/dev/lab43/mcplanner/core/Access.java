package dev.lab43.mcplanner.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * What {@code GET /api/mod/me} says about the person's OWN MC Planner plan ({@code access}, docs/MOD-API.md →
 * "Access"). It decides whether plan files (.mcplan) may be opened in the mod; shared worlds go by their owner's plan
 * instead (the server answers 402 for a paused world).
 *
 * @param active         any plan above Free (Cloud, Team, Lifetime, the 7-day trial, a plan given by hand)
 * @param plan           plan id ("free", "trial", "cloud", …), nullable
 * @param name           plan name for people ("Cloud trial"), nullable
 * @param source         where it comes from ("subscription", "trial", "lifetime", "grant", …), nullable
 * @param expiresAt      when the plan ends by itself, epoch ms; 0 = it renews or doesn't end
 * @param trialAvailable Free and the trial was never used: offer "Start 7 days free"
 * @param files          may open plan files (the server decides: {@code active}, or everybody when it's switched off)
 */
public record Access(boolean active, String plan, String name, String source, long expiresAt, boolean trialAvailable,
                     boolean files) {

    /** A server from before the access rule (no {@code access} in /me): everything works as before. */
    public static final Access UNRESTRICTED = new Access(true, null, null, null, 0, false, true);

    /** {@code access} of /me (or of the saved check); null when it's missing or not an object. */
    public static Access parse(JsonElement e) {
        JsonObject o = Json.obj(e);
        if (o == null) return null;
        boolean active = Json.bool(o, false, "active");
        long expires = parseTime(Json.get(o, "expiresAt"));
        if (expires == 0) expires = parseTime(Json.get(o, "expires_at"));
        return new Access(active, Json.str(o, "plan"), Json.str(o, "name"), Json.str(o, "source"), expires,
            Json.bool(o, false, "trialAvailable", "trial_available"),
            // "files" is the server's answer; an answer without it falls back to "active"
            Json.bool(o, active, "files"));
    }

    /** the form {@link #parse} reads back (for the saved check) */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("active", active);
        if (plan != null) o.addProperty("plan", plan);
        if (name != null) o.addProperty("name", name);
        if (source != null) o.addProperty("source", source);
        if (expiresAt > 0) o.addProperty("expiresAt", expiresAt);
        o.addProperty("trialAvailable", trialAvailable);
        o.addProperty("files", files);
        return o;
    }

    /** ISO date-time ("2026-10-09T10:00:00.5+00:00") or epoch ms → epoch ms; 0 when missing or unreadable */
    static long parseTime(JsonElement e) {
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return 0;
        if (e.getAsJsonPrimitive().isNumber()) {
            Double d = Json.finite(e);
            return d != null && d > 0 ? d.longValue() : 0;
        }
        String s = e.getAsString().strip();
        if (s.isEmpty()) return 0;
        try {
            return OffsetDateTime.parse(s.replace(' ', 'T')).toInstant().toEpochMilli();
        } catch (DateTimeParseException ex) {
            return 0;
        }
    }
}
