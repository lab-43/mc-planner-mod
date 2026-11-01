package dev.lab43.mcplanner.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * A failed call to the MC Planner API or the Lab43 hub. {@link #getMessage()} is meant for people.
 *
 * <ul>
 *   <li>{@link #status()}: HTTP status, 0 for network problems / timeouts</li>
 *   <li>{@link #code()}: the body's {@code code}, else an OAuth {@code error} token, else derived from the status
 *       ("unauthorized" for every 401, "network", "timeout", "not_found", "server_error", …)</li>
 * </ul>
 */
public final class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** No answer at all (offline, DNS, refused, timeout). */
    public boolean isNetwork() {
        return status == 0;
    }

    public boolean isUnauthorized() {
        return status == 401 || "unauthorized".equals(code);
    }

    /** Worth retrying later: network problems, 408, 429 and 5xx. */
    public boolean isTransient() {
        return status == 0 || status == 408 || status == 429 || status >= 500;
    }

    /** Unwraps CompletionException / ExecutionException; anything that isn't an ApiException becomes one. */
    public static ApiException of(Throwable t) {
        Throwable c = t;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) c = c.getCause();
        if (c instanceof ApiException) return (ApiException) c;
        return new ApiException(-1, "internal", "Something went wrong: " + (c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName()), c);
    }

    static ApiException network(Throwable t, String service) {
        Throwable c = t;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) c = c.getCause();
        if (c instanceof ApiException) return (ApiException) c;
        if (c instanceof HttpTimeoutException)
            return new ApiException(0, "timeout", capitalize(service) + " did not answer in time. Try again in a moment.", c);
        return new ApiException(0, "network", "Could not reach " + service + ". Check your internet connection.", c);
    }

    static ApiException badResponse(int status, String service, Throwable cause) {
        return new ApiException(status, "bad_response", capitalize(service) + " sent an answer the mod doesn't understand. Is the mod up to date?", cause);
    }

    /** Builds the exception for an error response ({@code {error, code}} body, OAuth error, or nothing usable). */
    static ApiException fromResponse(int status, String body, String service, Map<Integer, String> fallbacks) {
        String code = null, message = null;
        JsonObject o = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonElement e = JsonParser.parseString(body);
                o = Json.obj(e);
            } catch (RuntimeException ignored) {
                // not JSON (e.g. a proxy's HTML page)
            }
        }
        if (o != null) {
            String c = Json.str(o, "code");
            String err = Json.str(o, "error");
            if (c != null && !c.isBlank()) code = c;
            if (err != null && isSentence(err)) message = err;
            else if (err != null && code == null && !err.isBlank()) code = err; // OAuth style: {"error":"slow_down"}
            if (message == null) {
                String desc = Json.str(o, "error_description", "message");
                if (desc != null && isSentence(desc)) message = desc;
            }
        }
        if (status == 401) code = "unauthorized";
        if (code == null) code = defaultCode(status);
        if (message == null) message = fallbacks != null && fallbacks.containsKey(status) ? fallbacks.get(status) : fallbackMessage(status, service);
        return new ApiException(status, code, message);
    }

    /** "This world is paused." is a sentence; "not_found" is a code. */
    static boolean isSentence(String s) {
        String t = s.strip();
        if (t.length() < 3 || t.length() > 400) return false;
        for (int i = 0; i < t.length(); i++) if (Character.isWhitespace(t.charAt(i))) return true;
        return false;
    }

    private static String defaultCode(int status) {
        switch (status) {
            case 400: return "bad_request";
            case 401: return "unauthorized";
            case 403: return "forbidden";
            case 404: return "not_found";
            case 409: return "conflict";
            case 413: return "too_large";
            case 429: return "rate_limited";
            default: return status >= 500 ? "server_error" : "http_" + status;
        }
    }

    private static String fallbackMessage(int status, String service) {
        switch (status) {
            case 400: return "The request was not accepted.";
            case 401: return "Your sign-in has expired. Please sign in again.";
            case 403: return "You don't have access to this.";
            case 404: return "Not found — it may have been deleted.";
            case 409: return "Someone changed this at the same time. Try again.";
            case 413: return "That's too large.";
            case 429: return "Too many requests — try again in a moment.";
            default:
                return status >= 500
                    ? capitalize(service) + " has a problem right now. Try again later."
                    : capitalize(service) + " answered with an error (HTTP " + status + ").";
        }
    }

    static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
