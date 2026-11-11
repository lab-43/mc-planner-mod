package dev.lab43.mcplanner.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpRequest;
import java.util.concurrent.CompletableFuture;

/**
 * Sends a crash report to Lab43's feedback system ({@code POST {hub}/api/v1/crash-reports}, auth.lab43.dev's
 * crash-report API): once, when the person presses Send. No sign-in and no key; nothing else goes along. Fails
 * with an {@link ApiException} whose message is meant for people; never retried by itself.
 */
public final class CrashSender {
    public static final String PATH = "/api/v1/crash-reports";
    static final String SERVICE = "Lab43";

    /** What Lab43 answered: the report's id; {@code repeat}: the same crash from here in the last 10 minutes, counted on the earlier one. */
    public record Result(String id, boolean repeat) {}

    private final String hubUrl;
    private final String userAgent;

    /** {@code hubUrl}: the Lab43 account site (ModConfig.hubUrl, https://auth.lab43.dev). */
    public CrashSender(String hubUrl, String userAgent) {
        this.hubUrl = Http.trimSlash(hubUrl);
        this.userAgent = userAgent;
    }

    public CompletableFuture<Result> send(JsonObject body) {
        HttpRequest req;
        try {
            req = Http.request(hubUrl + PATH, userAgent)
                .header("Content-Type", "application/json")
                .POST(Http.json(body))
                .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new ApiException(0, "network", "The address \"" + hubUrl + "\" is not valid.", e));
        }
        return Http.send(req, SERVICE, r -> {
            int status = r.statusCode();
            if (status == 200 || status == 201) {
                JsonObject o = Json.obj(JsonParser.parseString(r.body() == null || r.body().isBlank() ? "{}" : r.body()));
                return new Result(Json.str(o, "id"), Json.bool(o, false, "repeat"));
            }
            JsonObject o = null;
            try {
                o = Json.obj(JsonParser.parseString(r.body() == null ? "" : r.body()));
            } catch (RuntimeException ignored) {
                // not JSON (a proxy's page)
            }
            String said = Json.str(o, "message");
            String text = said == null || said.isBlank() ? null : Json.slice(said.strip(), 300);
            String code = Json.str(o, "error");
            String message = switch (status) {
                case 400 -> "Lab43 didn't accept the report" + (text != null ? ": " + text : ".");
                case 404 -> "Lab43 isn't taking crash reports from MC Planner right now.";
                case 413 -> "The report is too large to send.";
                case 429 -> text != null ? text : "Too many reports from this computer. Please try again later.";
                default -> status >= 500 ? "Lab43 has a problem right now. Please try again later." : "Lab43 answered with an error (HTTP " + status + ").";
            };
            throw new ApiException(status, code != null && !code.isBlank() ? code : "http_" + status, message);
        });
    }
}
