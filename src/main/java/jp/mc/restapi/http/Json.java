package jp.mc.restapi.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON の共通処理。
 */
public final class Json {
    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();
    public static final Gson PRETTY = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private Json() {
    }

    /** キーと値を交互に並べて順序付きの Map を作る */
    public static Map<String, Object> obj(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("キーと値の数が合わない");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    public static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            throw ApiException.badRequest("'" + key + "' が必要です");
        }
        try {
            return e.getAsString();
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("'" + key + "' は文字列で指定してください");
        }
    }

    public static String str(JsonObject o, String key, String def) {
        return has(o, key) ? str(o, key) : def;
    }

    public static int integer(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            throw ApiException.badRequest("'" + key + "' が必要です");
        }
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("'" + key + "' は整数で指定してください");
        }
    }

    public static int integer(JsonObject o, String key, int def) {
        return has(o, key) ? integer(o, key) : def;
    }

    public static double number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            throw ApiException.badRequest("'" + key + "' が必要です");
        }
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("'" + key + "' は数値で指定してください");
        }
    }

    public static double number(JsonObject o, String key, double def) {
        return has(o, key) ? number(o, key) : def;
    }

    public static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw ApiException.badRequest("'" + key + "' は true / false で指定してください");
        }
        return e.getAsBoolean();
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        return has(o, key) ? bool(o, key) : def;
    }

    public static boolean has(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull();
    }
}
