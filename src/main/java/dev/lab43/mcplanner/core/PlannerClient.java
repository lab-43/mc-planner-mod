package dev.lab43.mcplanner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The MC Planner API for the mod ({@code /api/mod/*}), authenticated with the Lab43 device token as Bearer.
 * Every call is asynchronous (never blocks the caller) and fails with {@link ApiException}: code
 * "unauthorized" for 401 (sign in again), "network"/"timeout" when the server can't be reached.
 */
public final class PlannerClient {
    static final String SERVICE = "MC Planner";
    public static final int DEFAULT_POLL_SECONDS = 10;
    private static final Map<Integer, String> WORLD_ERRORS = Map.of(
        403, "You don't have access to this world any more.",
        404, "This world doesn't exist any more (or you were removed from it).");

    private final String apiUrl;
    private final String userAgent;
    private final Supplier<String> token;

    public PlannerClient(String apiUrl, String userAgent, Supplier<String> token) {
        this.apiUrl = Http.trimSlash(apiUrl);
        this.userAgent = Objects.requireNonNull(userAgent);
        this.token = Objects.requireNonNull(token);
    }

    /** "MCPlanner-Mod/1.0.0 (Minecraft 26.2)" — the User-Agent format both clients expect. */
    public static String userAgent(String modVersion, String mcVersion) {
        return Http.userAgent(modVersion, mcVersion);
    }

    public String apiUrl() {
        return apiUrl;
    }

    /** {@code GET /api/mod/me}. */
    public CompletableFuture<Me> me() {
        return call("GET", "/api/mod/me", null, null, null, r -> {
            ok(r, null);
            JsonObject o = parseObject(r);
            JsonObject user = Json.obj(Json.get(o, "user"));
            JsonObject src = user != null ? user : o;
            String id = Json.text(src, "userId", "user_id", "id");
            if (id == null) id = Json.text(o, "userId", "user_id", "id");
            String name = Json.str(src, "name", "userName", "user_name");
            if (name == null) name = Json.str(o, "name");
            Integer color = parseColor(Json.get(src, "color"));
            if (color == null) color = parseColor(Json.get(o, "color"));
            List<String> scopes = new ArrayList<>();
            JsonArray sa = Json.arr(Json.get(o, "scopes"));
            if (sa != null) {
                for (JsonElement e : sa) if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) scopes.add(e.getAsString());
            } else {
                String scope = Json.str(o, "scope");
                if (scope != null) for (String s : scope.trim().split("\\s+")) if (!s.isEmpty()) scopes.add(s);
            }
            return new Me(id, name, color, Collections.unmodifiableList(scopes), Access.parse(Json.get(o, "access")));
        });
    }

    /** {@code GET /api/mod/worlds} — the shared worlds this person belongs to. */
    public CompletableFuture<List<WorldInfo>> worlds() {
        return call("GET", "/api/mod/worlds", null, null, null, r -> {
            ok(r, null);
            JsonElement root = JsonParser.parseString(r.body());
            JsonArray list = Json.arr(root);
            if (list == null) list = Json.arr(Json.get(Json.obj(root), "worlds"));
            if (list == null) throw ApiException.badResponse(r.statusCode(), SERVICE, null);
            List<WorldInfo> out = new ArrayList<>();
            for (JsonElement e : list) {
                WorldInfo w = parseWorld(Json.obj(e));
                if (w != null) out.add(w);
            }
            return Collections.unmodifiableList(out);
        });
    }

    static WorldInfo parseWorld(JsonObject o) {
        if (o == null) return null;
        String room = Json.text(o, "room_id", "roomId", "id");
        if (room == null || room.isEmpty()) return null;
        String owner = Json.str(o, "ownerName", "owner_name");
        if (owner == null) owner = Json.str(Json.obj(Json.get(o, "owner")), "name");
        int members = 0;
        JsonElement m = Json.get(o, "members");
        if (m == null) m = Json.get(o, "memberCount");
        if (m == null) m = Json.get(o, "member_count");
        if (m != null && m.isJsonArray()) members = m.getAsJsonArray().size();
        else {
            Double n = Json.finite(m);
            if (n != null) members = Math.max(0, Json.roundInt(n));
        }
        String name = Json.str(o, "name");
        return new WorldInfo(room, name != null ? name : "World", Json.str(o, "role"),
            Json.bool(o, false, "paused"), Json.bool(o, false, "canEdit", "can_edit"), owner, members);
    }

    /**
     * {@code GET /api/mod/worlds/{room}/plan}, with {@code If-None-Match} when {@code etag} is given.
     * 304 → {@code notModified = true}.
     */
    public CompletableFuture<PlanResponse> plan(String roomId, String etag) {
        String path = "/api/mod/worlds/" + Http.seg(roomId) + "/plan";
        String inm = etag != null && !etag.isEmpty() ? "\"" + etag + "\"" : null;
        return call("GET", path, null, inm, null, r -> {
            int poll = pollSeconds(r);
            if (r.statusCode() == 304) {
                String et = Http.etag(r);
                return new PlanResponse(true, null, et != null ? et : etag, null, false, false, null, poll, null, false);
            }
            ok(r, WORLD_ERRORS);
            return planResponse(r.body(), Http.etag(r), poll, false);
        });
    }

    /** Builds a PlanResponse from the server's wrapper JSON (also used for the offline cache). */
    static PlanResponse planResponse(String body, String etag, int pollSeconds, boolean fromCache) {
        JsonElement root = JsonParser.parseString(body);
        JsonObject w = Json.obj(root);
        Plan plan = PlanParser.parse(root);
        boolean wrapped = w != null && w.has("plan") && Json.arr(Json.get(w, "objects")) == null;
        String role = wrapped ? Json.str(w, "role") : null;
        boolean paused = wrapped && Json.bool(w, false, "paused");
        boolean canEdit = wrapped && Json.bool(w, false, "canEdit", "can_edit");
        String name = wrapped ? Json.str(w, "name") : null;
        if (name == null) name = plan.worldName;
        return new PlanResponse(false, plan, etag, role, paused, canEdit, name, pollSeconds, body, fromCache);
    }

    /** {@code PATCH /api/mod/worlds/{room}/objects/{id} {status}} → the changed object. */
    public CompletableFuture<PlanObject> setStatus(String roomId, String objectId, Status status) {
        JsonObject body = new JsonObject();
        body.addProperty("status", status.id());
        String path = "/api/mod/worlds/" + Http.seg(roomId) + "/objects/" + Http.seg(objectId);
        return call("PATCH", path, body, null, null, r -> {
            ok(r, WORLD_ERRORS);
            return objectFrom(r);
        });
    }

    /** {@code POST /api/mod/worlds/{room}/markers {name, x, y, z, dimension, color?, notes?}} → the new marker. */
    public CompletableFuture<PlanObject> addMarker(String roomId, MarkerRequest req) {
        JsonObject body = new JsonObject();
        body.addProperty("name", req.name());
        body.addProperty("x", req.x());
        body.addProperty("y", req.y());
        body.addProperty("z", req.z());
        body.addProperty("dimension", req.dimension());
        if (req.color() != null) body.addProperty("color", req.color());
        if (req.notes() != null) body.addProperty("notes", req.notes());
        String path = "/api/mod/worlds/" + Http.seg(roomId) + "/markers";
        return call("POST", path, body, null, null, r -> {
            ok(r, WORLD_ERRORS);
            return objectFrom(r);
        });
    }

    /** {@code POST /api/mod/logout} — ends this device's session on the server. */
    public CompletableFuture<Void> logout() {
        return call("POST", "/api/mod/logout", new JsonObject(), null, null, r -> {
            if (r.statusCode() == 401) return null; // already signed out
            ok(r, null);
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------

    private static PlanObject objectFrom(HttpResponse<String> r) {
        JsonObject o = parseObject(r);
        JsonElement obj = Json.get(o, "object");
        PlanObject p = PlanParser.parseObject(obj != null ? obj : o);
        if (p == null) throw ApiException.badResponse(r.statusCode(), SERVICE, null);
        return p;
    }

    private static JsonObject parseObject(HttpResponse<String> r) {
        JsonObject o = Json.obj(JsonParser.parseString(r.body()));
        if (o == null) throw ApiException.badResponse(r.statusCode(), SERVICE, null);
        return o;
    }

    private static void ok(HttpResponse<String> r, Map<Integer, String> fallbacks) {
        if (!Http.ok(r)) throw ApiException.fromResponse(r.statusCode(), r.body(), SERVICE, fallbacks);
    }

    static int pollSeconds(HttpResponse<?> r) {
        String v = r.headers().firstValue("X-Poll-Interval").orElse(null);
        if (v != null) {
            try {
                double d = Double.parseDouble(v.strip());
                if (Double.isFinite(d) && d > 0) return (int) Math.max(1, Math.round(d));
            } catch (NumberFormatException ignored) {
                // default
            }
        }
        return DEFAULT_POLL_SECONDS;
    }

    private static Integer parseColor(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        if (e.getAsJsonPrimitive().isString()) {
            int c = PlanParser.parseColor(e.getAsString());
            return c >= 0 ? c : null;
        }
        Double d = Json.finite(e);
        return d != null ? ((int) d.doubleValue()) & 0xffffff : null;
    }

    private <T> CompletableFuture<T> call(String method, String path, JsonObject body, String ifNoneMatch,
                                          Map<String, String> headers, Http.Handler<T> handler) {
        String t;
        try {
            t = token.get();
        } catch (RuntimeException e) {
            t = null;
        }
        if (t == null || t.isEmpty()) {
            return CompletableFuture.failedFuture(new ApiException(401, "unauthorized", "Please sign in first."));
        }
        HttpRequest.Builder b;
        try {
            b = Http.request(apiUrl + path, userAgent).header("Authorization", "Bearer " + t);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new ApiException(0, "network", "The MC Planner address \"" + apiUrl + "\" is not valid.", e));
        }
        if (ifNoneMatch != null) b.header("If-None-Match", ifNoneMatch);
        if (headers != null) headers.forEach(b::header);
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, Http.json(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return Http.send(b.build(), SERVICE, handler);
    }
}
