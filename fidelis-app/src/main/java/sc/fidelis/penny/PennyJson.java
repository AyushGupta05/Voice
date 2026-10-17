package sc.fidelis.penny;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** JSON for the Penny bridge: Fiji's bundled Gson, Java maps and lists on both sides, typed argument readers. */
final class PennyJson {
    private PennyJson() {}

    static final Gson GSON = new GsonBuilder().serializeNulls().serializeSpecialFloatingPointValues().disableHtmlEscaping().create();

    static String write(Object value) {
        return GSON.toJson(value);
    }

    /** A JSON value as plain Java: Boolean, Long (integral numbers), Double, String, List, LinkedHashMap, or null. */
    static Object toJava(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isString()) return p.getAsString();
            double d = p.getAsDouble();
            if (d == Math.rint(d) && Math.abs(d) < 9.0e15) return (long) d;
            return d;
        }
        if (e.isJsonArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonElement x : (JsonArray) e) l.add(toJava(x));
            return l;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> x : ((JsonObject) e).entrySet()) m.put(x.getKey(), toJava(x.getValue()));
        return m;
    }

    // ---- typed argument readers (IllegalArgumentException = HTTP 400) --------------------------------

    static boolean has(JsonObject a, String key) {
        return a.has(key) && !a.get(key).isJsonNull();
    }

    static String str(JsonObject a, String key, String def) {
        if (!has(a, key)) return def;
        JsonElement e = a.get(key);
        if (!e.isJsonPrimitive()) throw new IllegalArgumentException("'" + key + "' must be text");
        return e.getAsString();
    }

    static Double num(JsonObject a, String key) {
        if (!has(a, key)) return null;
        JsonElement e = a.get(key);
        double d;
        try {
            d = e.getAsDouble();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("'" + key + "' must be a number");
        }
        if (!Double.isFinite(d)) throw new IllegalArgumentException("'" + key + "' must be a finite number");
        return d;
    }

    static Boolean bool(JsonObject a, String key) {
        if (!has(a, key)) return null;
        JsonElement e = a.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        if (e.isJsonPrimitive()) {
            String s = e.getAsString().trim();
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        throw new IllegalArgumentException("'" + key + "' must be true or false");
    }

    static boolean bool(JsonObject a, String key, boolean def) {
        Boolean b = bool(a, key);
        return b == null ? def : b;
    }

    static int integer(JsonObject a, String key, int def, int min, int max) {
        Double d = num(a, key);
        if (d == null) return def;
        if (d != Math.rint(d) || d < min || d > max)
            throw new IllegalArgumentException("'" + key + "' must be an integer in " + min + ".." + max);
        return (int) (double) d;
    }

    static JsonObject object(JsonObject a, String key) {
        if (!has(a, key)) return new JsonObject();
        JsonElement e = a.get(key);
        if (!e.isJsonObject()) throw new IllegalArgumentException("'" + key + "' must be an object");
        return e.getAsJsonObject();
    }

    static List<Object> list(JsonObject a, String key) {
        if (!has(a, key)) return null;
        JsonElement e = a.get(key);
        if (!e.isJsonArray()) throw new IllegalArgumentException("'" + key + "' must be a list");
        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>) toJava(e);
        return l;
    }
}
