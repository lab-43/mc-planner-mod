package dev.lab43.mcplanner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;

/** Small JSON helpers with JavaScript semantics (Number.isFinite, String(x), truthiness). */
final class Json {
    private Json() {}

    static JsonObject obj(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static JsonArray arr(JsonElement e) {
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    static JsonElement get(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e;
    }

    /** Value of the first key that holds a JSON string; null if none. */
    static String str(JsonObject o, String... keys) {
        for (String k : keys) {
            JsonElement e = get(o, k);
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return e.getAsString();
        }
        return null;
    }

    /** First key holding a string or number, as text (ids and revs can be either). */
    static String text(JsonObject o, String... keys) {
        for (String k : keys) {
            JsonElement e = get(o, k);
            if (e != null && e.isJsonPrimitive() && !e.getAsJsonPrimitive().isBoolean()) return jsString(e);
        }
        return null;
    }

    /** The number if {@code e} is a finite JSON number (JS Number.isFinite), else null. */
    static Double finite(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) return null;
        double v;
        try {
            v = p.getAsDouble();
        } catch (NumberFormatException ex) {
            return null;
        }
        return Double.isFinite(v) ? v : null;
    }

    static Double finite(JsonObject o, String key) {
        return finite(get(o, key));
    }

    /** JS Number.isInteger. */
    static boolean isInteger(JsonElement e) {
        Double v = finite(e);
        return v != null && Math.floor(v) == v;
    }

    /** First boolean-ish key (true/false, or JS truthiness of 0/1) — missing → def. */
    static boolean bool(JsonObject o, boolean def, String... keys) {
        for (String k : keys) {
            JsonElement e = get(o, k);
            if (e != null) return truthy(e);
        }
        return def;
    }

    static int roundInt(double v) {
        long r = Math.round(v);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, r));
    }

    /** JavaScript truthiness of a JSON value. */
    static boolean truthy(JsonElement e) {
        if (e == null || e.isJsonNull()) return false;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isString()) return !p.getAsString().isEmpty();
            double v = p.getAsDouble();
            return v != 0 && !Double.isNaN(v);
        }
        return true; // objects and arrays
    }

    /** JavaScript String(x) of a JSON value. */
    static String jsString(JsonElement e) {
        if (e == null || e.isJsonNull()) return "null";
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isString()) return p.getAsString();
            if (p.isBoolean()) return p.getAsBoolean() ? "true" : "false";
            return jsNumber(p.getAsDouble());
        }
        if (e.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                if (i > 0) sb.append(',');
                JsonElement x = a.get(i);
                if (x != null && !x.isJsonNull()) sb.append(jsString(x));
            }
            return sb.toString();
        }
        return "[object Object]";
    }

    /** JavaScript Number#toString for the common cases. */
    static String jsNumber(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "Infinity" : "-Infinity";
        if (v == 0) return "0";
        if (Math.rint(v) == v && Math.abs(v) < 1e21) return new BigDecimal(v).toBigInteger().toString();
        String s = Double.toString(v);
        int e = s.indexOf('E');
        if (e < 0) return s;
        String mant = s.substring(0, e), exp = s.substring(e + 1);
        if (mant.endsWith(".0")) mant = mant.substring(0, mant.length() - 2);
        return mant + "e" + (exp.startsWith("-") ? exp : "+" + exp);
    }

    /** JS str.slice(0, n). */
    static String slice(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
