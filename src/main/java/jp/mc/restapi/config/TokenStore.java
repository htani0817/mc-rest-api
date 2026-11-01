package jp.mc.restapi.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jp.mc.restapi.http.Json;
import jp.mc.restapi.http.Scope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * config/mc_rest_api/tokens.json のトークン一覧と、SSE 用の使い捨てチケット。
 */
public final class TokenStore {
    public record Token(String name, String value, Scope scope) {
    }

    private record Ticket(Token token, long expiresAt) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long TICKET_TTL_MILLIS = 30_000L;

    private final List<Token> tokens;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    TokenStore(List<Token> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    public record LoadResult(TokenStore store, boolean created) {
    }

    /** ファイルが無ければ admin / write / read の 3 本を生成して保存する */
    public static LoadResult loadOrCreate(Path file) throws IOException {
        if (!Files.exists(file)) {
            List<Token> generated = List.of(
                    new Token("admin", generate(), Scope.ADMIN),
                    new Token("write", generate(), Scope.WRITE),
                    new Token("read", generate(), Scope.READ));
            save(file, generated);
            return new LoadResult(new TokenStore(generated), true);
        }
        JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        List<Token> list = new ArrayList<>();
        for (JsonElement e : root.getAsJsonObject().getAsJsonArray("tokens")) {
            JsonObject o = e.getAsJsonObject();
            String value = o.get("token").getAsString();
            if (value.length() < 16) {
                throw new IOException("トークン '" + o.get("name").getAsString() + "' が短すぎます（16 文字以上）");
            }
            list.add(new Token(o.get("name").getAsString(), value, Scope.parse(o.get("scope").getAsString())));
        }
        if (list.isEmpty()) {
            throw new IOException(file + " にトークンがありません");
        }
        return new LoadResult(new TokenStore(list), false);
    }

    private static void save(Path file, List<Token> list) throws IOException {
        JsonArray arr = new JsonArray();
        for (Token t : list) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name());
            o.addProperty("token", t.value());
            o.addProperty("scope", t.scope().id());
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("tokens", arr);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.PRETTY.toJson(root) + "\n", StandardCharsets.UTF_8);
        try {
            // Linux では所有者だけ読めるようにする
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows など POSIX 権限が無い環境ではそのまま
        }
    }

    static String generate() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** 一致するトークンを探す。全件を定数時間で比較する */
    public Token find(String presented) {
        if (presented == null || presented.isEmpty()) {
            return null;
        }
        byte[] p = presented.getBytes(StandardCharsets.UTF_8);
        Token found = null;
        for (Token t : tokens) {
            if (MessageDigest.isEqual(p, t.value().getBytes(StandardCharsets.UTF_8))) {
                found = t;
            }
        }
        return found;
    }

    public String issueTicket(Token token) {
        long now = System.currentTimeMillis();
        tickets.values().removeIf(t -> t.expiresAt < now);
        String id = generate();
        tickets.put(id, new Ticket(token, now + TICKET_TTL_MILLIS));
        return id;
    }

    /** チケットは 1 回使うと消える */
    public Token redeemTicket(String id) {
        if (id == null) {
            return null;
        }
        Ticket t = tickets.remove(id);
        if (t == null || t.expiresAt < System.currentTimeMillis()) {
            return null;
        }
        return t.token;
    }

    public static long ticketTtlSeconds() {
        return TICKET_TTL_MILLIS / 1000;
    }
}
