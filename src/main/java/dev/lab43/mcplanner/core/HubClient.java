package dev.lab43.mcplanner.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Device sign-in against the Lab43 hub (auth.lab43.dev), RFC 8628 style. All calls are asynchronous and fail
 * with {@link ApiException}.
 */
public final class HubClient {
    static final String SERVICE = "the Lab43 sign-in service";
    static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";

    private final String hubUrl;
    private final String userAgent;

    public HubClient(String hubUrl, String userAgent) {
        this.hubUrl = Http.trimSlash(hubUrl);
        this.userAgent = userAgent;
    }

    public String hubUrl() {
        return hubUrl;
    }

    /** {@code POST {hub}/api/v1/device/code {client_id, device_name}}. */
    public CompletableFuture<DeviceCode> startDevice(String clientId, String deviceName) {
        JsonObject body = new JsonObject();
        body.addProperty("client_id", clientId);
        if (deviceName != null) body.addProperty("device_name", deviceName);
        HttpRequest req;
        try {
            req = Http.request(hubUrl + "/api/v1/device/code", userAgent)
                .header("Content-Type", "application/json")
                .POST(Http.json(body))
                .build();
        } catch (IllegalArgumentException e) {
            return badUrl(e);
        }
        return Http.send(req, SERVICE, r -> {
            if (!Http.ok(r)) throw ApiException.fromResponse(r.statusCode(), r.body(), SERVICE, null);
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            String deviceCode = Json.str(o, "device_code"), userCode = Json.str(o, "user_code");
            String uri = Json.str(o, "verification_uri");
            if (deviceCode == null || userCode == null || uri == null) throw ApiException.badResponse(r.statusCode(), SERVICE, null);
            String complete = Json.str(o, "verification_uri_complete");
            Double expires = Json.finite(o, "expires_in"), interval = Json.finite(o, "interval");
            return new DeviceCode(deviceCode, userCode, uri, complete != null ? complete : uri,
                expires != null ? Math.max(1, Json.roundInt(expires)) : 900,
                interval != null ? Math.max(1, Json.roundInt(interval)) : 5);
        });
    }

    /**
     * {@code POST {hub}/api/v1/device/token}. OAuth errors (400 {@code {"error": …}}) come back as a
     * {@link TokenResult} kind; only network problems and other failures (5xx, garbage) fail the future.
     */
    public CompletableFuture<TokenResult> pollToken(String clientId, String deviceCode) {
        JsonObject body = new JsonObject();
        body.addProperty("client_id", clientId);
        body.addProperty("device_code", deviceCode);
        body.addProperty("grant_type", GRANT_TYPE);
        HttpRequest req;
        try {
            req = Http.request(hubUrl + "/api/v1/device/token", userAgent)
                .header("Content-Type", "application/json")
                .POST(Http.json(body))
                .build();
        } catch (IllegalArgumentException e) {
            return badUrl(e);
        }
        return Http.send(req, SERVICE, HubClient::tokenResult);
    }

    static TokenResult tokenResult(HttpResponse<String> r) {
        int status = r.statusCode();
        if (status == 200) {
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            String token = Json.str(o, "access_token");
            if (token == null || token.isEmpty()) throw ApiException.badResponse(status, SERVICE, null);
            JsonObject user = Json.obj(Json.get(o, "user"));
            return new TokenResult(TokenResult.Kind.APPROVED, token, Json.text(user, "id"), Json.str(user, "name"), 0);
        }
        if (status == 429) return TokenResult.of(TokenResult.Kind.SLOW_DOWN);
        if (status >= 400 && status < 500) {
            JsonObject o = null;
            try {
                o = Json.obj(JsonParser.parseString(r.body()));
            } catch (RuntimeException ignored) {
                // not JSON
            }
            String error = Json.str(o, "error");
            if (error != null) {
                switch (error) {
                    case "authorization_pending":
                        return TokenResult.of(TokenResult.Kind.PENDING);
                    case "slow_down": {
                        Double iv = Json.finite(o, "interval");
                        return new TokenResult(TokenResult.Kind.SLOW_DOWN, null, null, null, iv != null ? Math.max(0, Json.roundInt(iv)) : 0);
                    }
                    case "access_denied":
                        return TokenResult.of(TokenResult.Kind.DENIED);
                    case "expired_token":
                        return TokenResult.of(TokenResult.Kind.EXPIRED);
                    default:
                        return TokenResult.of(TokenResult.Kind.INVALID);
                }
            }
        }
        throw ApiException.fromResponse(status, r.body(), SERVICE, null);
    }

    /**
     * {@code POST {hub}/api/v1/device/revoke} with the token as Bearer. A 401 counts as success (the token
     * doesn't work any more either way).
     */
    public CompletableFuture<Void> revoke(String token) {
        HttpRequest req;
        try {
            req = Http.request(hubUrl + "/api/v1/device/revoke", userAgent)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        } catch (IllegalArgumentException e) {
            return badUrl(e);
        }
        return Http.send(req, SERVICE, r -> {
            if (Http.ok(r) || r.statusCode() == 401) return null;
            throw ApiException.fromResponse(r.statusCode(), r.body(), SERVICE, Map.of());
        });
    }

    private <T> CompletableFuture<T> badUrl(IllegalArgumentException e) {
        return CompletableFuture.failedFuture(new ApiException(0, "network", "The sign-in address \"" + hubUrl + "\" is not valid.", e));
    }
}
