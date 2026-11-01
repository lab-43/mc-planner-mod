package dev.lab43.mcplanner.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Shared HTTP plumbing: one HttpClient (daemon threads), 15 s timeouts, JSON bodies, error mapping. */
final class Http {
    private Http() {}

    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static volatile HttpClient shared;

    static HttpClient client() {
        HttpClient c = shared;
        if (c == null) {
            synchronized (Http.class) {
                c = shared;
                if (c == null) {
                    shared = c = HttpClient.newBuilder()
                        .connectTimeout(TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER) // never re-send the Bearer token elsewhere
                        .build();
                }
            }
        }
        return c;
    }

    /** What the mod sends as User-Agent: "MCPlanner-Mod/1.0.0 (Minecraft 26.2)". */
    static String userAgent(String modVersion, String mcVersion) {
        return "MCPlanner-Mod/" + modVersion + " (Minecraft " + mcVersion + ")";
    }

    static String trimSlash(String url) {
        String s = url.strip();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Encodes one path segment (spaces → %20). */
    static String seg(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static HttpRequest.Builder request(String url, String userAgent) {
        URI uri = URI.create(url);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json");
        // plain http (local test servers): no h2c upgrade dance
        if ("http".equalsIgnoreCase(uri.getScheme())) b.version(HttpClient.Version.HTTP_1_1);
        return b;
    }

    static HttpRequest.BodyPublisher json(JsonElement body) {
        return HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8);
    }

    /** Something that turns a response into a result, or throws (an ApiException for error statuses). */
    interface Handler<T> {
        T handle(HttpResponse<String> r) throws Exception;
    }

    /**
     * Sends asynchronously. The returned future fails with an {@link ApiException} itself (not wrapped) for
     * network problems, and with whatever the handler throws (converted to ApiException when it isn't one).
     */
    static <T> CompletableFuture<T> send(HttpRequest req, String service, Handler<T> handler) {
        CompletableFuture<T> out = new CompletableFuture<>();
        CompletableFuture<HttpResponse<String>> f;
        try {
            f = client().sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            out.completeExceptionally(ApiException.network(e, service));
            return out;
        }
        f.whenComplete((r, err) -> {
            if (err != null) {
                out.completeExceptionally(ApiException.network(err, service));
                return;
            }
            try {
                out.complete(handler.handle(r));
            } catch (ApiException e) {
                out.completeExceptionally(e);
            } catch (PlanFormatException | com.google.gson.JsonParseException | IllegalStateException | ClassCastException
                     | UnsupportedOperationException | NullPointerException | NumberFormatException e) {
                out.completeExceptionally(ApiException.badResponse(r.statusCode(), service, e));
            } catch (Throwable t) {
                out.completeExceptionally(ApiException.of(t));
            }
        });
        return out;
    }

    static boolean ok(HttpResponse<?> r) {
        return r.statusCode() >= 200 && r.statusCode() < 300;
    }

    /** ETag header value without W/ and quotes; null when absent. */
    static String etag(HttpResponse<?> r) {
        String v = r.headers().firstValue("ETag").orElse(null);
        if (v == null) return null;
        v = v.strip();
        if (v.startsWith("W/") || v.startsWith("w/")) v = v.substring(2);
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
        return v.isEmpty() ? null : v;
    }
}
