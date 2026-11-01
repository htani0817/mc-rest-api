package jp.mc.restapi.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import jp.mc.restapi.config.TokenStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1 リクエスト分の情報。パス変数・クエリ・本文・認証済みトークンを持つ。
 */
public final class Request {
    private final HttpExchange exchange;
    private final Map<String, String> params;
    private final Map<String, String> query;
    private final long maxJsonBytes;
    private TokenStore.Token token;
    private JsonObject jsonCache;

    public Request(HttpExchange exchange, Map<String, String> params, long maxJsonBytes) {
        this.exchange = exchange;
        this.params = decodeAll(params);
        this.query = parseQuery(exchange.getRequestURI().getRawQuery());
        this.maxJsonBytes = maxJsonBytes;
    }

    public HttpExchange exchange() {
        return exchange;
    }

    public String method() {
        return exchange.getRequestMethod();
    }

    public String path() {
        return exchange.getRequestURI().getPath();
    }

    public String remoteAddress() {
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    public String header(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    public TokenStore.Token token() {
        return token;
    }

    void token(TokenStore.Token token) {
        this.token = token;
    }

    public String param(String name) {
        String v = params.get(name);
        if (v == null) {
            throw new IllegalStateException("パス変数がない: " + name);
        }
        return v;
    }

    public int paramInt(String name) {
        try {
            return Integer.parseInt(param(name));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("'" + name + "' は整数で指定してください");
        }
    }

    public String query(String name) {
        return query.get(name);
    }

    public String query(String name, String def) {
        String v = query.get(name);
        return v == null || v.isEmpty() ? def : v;
    }

    public int queryInt(String name, int def) {
        String v = query.get(name);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("'" + name + "' は整数で指定してください");
        }
    }

    public double queryDouble(String name, double def) {
        String v = query.get(name);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("'" + name + "' は数値で指定してください");
        }
    }

    public boolean queryBool(String name, boolean def) {
        String v = query.get(name);
        if (v == null || v.isEmpty()) {
            return def;
        }
        return v.equalsIgnoreCase("true") || v.equals("1");
    }

    /** 本文を JSON オブジェクトとして読む。本文が空なら空オブジェクト */
    public JsonObject json() {
        if (jsonCache != null) {
            return jsonCache;
        }
        byte[] body = readBody(maxJsonBytes);
        if (body.length == 0) {
            jsonCache = new JsonObject();
            return jsonCache;
        }
        try {
            JsonElement e = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) {
                throw ApiException.badRequest("本文は JSON オブジェクトで送ってください");
            }
            jsonCache = e.getAsJsonObject();
            return jsonCache;
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ApiException.badRequest("JSON として読めません: " + e.getMessage());
        }
    }

    /** 本文をそのまま読む（ファイルアップロード用） */
    public InputStream bodyStream() {
        return exchange.getRequestBody();
    }

    private byte[] readBody(long limit) {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > limit) {
                    throw new ApiException(413, "payload_too_large", "本文が大きすぎます（上限 " + limit + " バイト）");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw ApiException.badRequest("本文を読めません: " + e.getMessage());
        }
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return map;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            map.putIfAbsent(decode(k), decode(v));
        }
        return map;
    }

    private static Map<String, String> decodeAll(Map<String, String> raw) {
        Map<String, String> map = new LinkedHashMap<>();
        raw.forEach((k, v) -> map.put(k, decode(v)));
        return map;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("URL エンコードが不正です");
        }
    }
}
