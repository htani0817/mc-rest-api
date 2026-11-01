package jp.mc.restapi.api;

import com.google.common.net.InetAddresses;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.http.Json;
import jp.mc.restapi.http.Request;
import jp.mc.restapi.http.Response;
import jp.mc.restapi.http.Router;
import jp.mc.restapi.http.Scope;
import jp.mc.restapi.util.PlayerResolver;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.BanListEntry;
import net.minecraft.server.players.IpBanListEntry;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * OP・ホワイトリスト（allowlist）・BAN・IP BAN。バニラの管理プロトコルと同じ処理をする。
 */
public final class AccessListApi {
    private static final String BAN_SOURCE = "MC REST API";

    private final ApiContext ctx;

    public AccessListApi(ApiContext ctx) {
        this.ctx = ctx;
    }

    public void register(Router r) {
        // OP
        r.get("/operators", Scope.READ, req -> ctx.exec().call(this::operators));
        r.post("/operators", Scope.ADMIN, this::addOperator);
        r.delete("/operators/{player}", Scope.ADMIN, req -> {
            NameAndId p = PlayerResolver.resolveProfile(ctx.exec(), req.param("player"));
            ctx.exec().run(() -> list().deop(p));
            return ctx.exec().call(this::operators);
        });

        // ホワイトリスト
        r.get("/allowlist", Scope.READ, req -> ctx.exec().call(this::allowlist));
        r.post("/allowlist", Scope.WRITE, req -> {
            NameAndId p = PlayerResolver.resolveProfile(ctx.exec(), Json.str(req.json(), "player"));
            ctx.exec().run(() -> list().getWhiteList().add(new UserWhiteListEntry(p)));
            return Response.json(201, ctx.exec().call(this::allowlist));
        });
        r.put("/allowlist", Scope.WRITE, this::replaceAllowlist);
        r.delete("/allowlist/{player}", Scope.WRITE, req -> {
            NameAndId p = PlayerResolver.resolveProfile(ctx.exec(), req.param("player"));
            ctx.exec().run(() -> {
                list().getWhiteList().remove(p);
                ctx.server().kickUnlistedPlayers();
            });
            return ctx.exec().call(this::allowlist);
        });
        r.delete("/allowlist", Scope.WRITE, req -> {
            ctx.exec().run(() -> {
                list().getWhiteList().clear();
                ctx.server().kickUnlistedPlayers();
            });
            return ctx.exec().call(this::allowlist);
        });

        // BAN
        r.get("/bans", Scope.READ, req -> ctx.exec().call(this::bans));
        r.post("/bans", Scope.WRITE, this::addBan);
        r.delete("/bans/{player}", Scope.WRITE, req -> {
            NameAndId p = PlayerResolver.resolveProfile(ctx.exec(), req.param("player"));
            boolean removed = ctx.exec().call(() -> {
                boolean had = list().getBans().isBanned(p);
                list().getBans().remove(p);
                return had;
            });
            if (!removed) {
                throw ApiException.notFound("not_banned", "BAN されていません: " + p.name());
            }
            return ctx.exec().call(this::bans);
        });
        r.delete("/bans", Scope.ADMIN, req -> {
            ctx.exec().run(() -> list().getBans().clear());
            return ctx.exec().call(this::bans);
        });

        // IP BAN
        r.get("/ip-bans", Scope.READ, req -> ctx.exec().call(this::ipBans));
        r.post("/ip-bans", Scope.WRITE, this::addIpBan);
        r.delete("/ip-bans/{ip}", Scope.WRITE, req -> {
            String ip = req.param("ip");
            boolean removed = ctx.exec().call(() -> {
                boolean had = list().getIpBans().isBanned(ip);
                list().getIpBans().remove(ip);
                return had;
            });
            if (!removed) {
                throw ApiException.notFound("not_banned", "BAN されていない IP です: " + ip);
            }
            return ctx.exec().call(this::ipBans);
        });
        r.delete("/ip-bans", Scope.ADMIN, req -> {
            ctx.exec().run(() -> list().getIpBans().clear());
            return ctx.exec().call(this::ipBans);
        });
    }

    private PlayerList list() {
        return ctx.server().getPlayerList();
    }

    private List<Map<String, Object>> operators() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ServerOpListEntry e : list().getOps().getEntries()) {
            NameAndId u = e.getUser();
            if (u == null) {
                continue;
            }
            Map<String, Object> m = PlayerResolver.toMap(u);
            m.put("level", e.permissions().level().id());
            m.put("bypassesPlayerLimit", e.getBypassesPlayerLimit());
            m.put("online", list().getPlayer(u.id()) != null);
            out.add(m);
        }
        return out;
    }

    private Object addOperator(Request req) {
        JsonObject b = req.json();
        NameAndId p = PlayerResolver.resolveProfile(ctx.exec(), Json.str(b, "player"));
        Optional<LevelBasedPermissionSet> level = Optional.empty();
        if (Json.has(b, "level")) {
            int lv = Json.integer(b, "level");
            if (lv < 1 || lv > 4) {
                throw ApiException.badRequest("level は 1〜4 で指定してください");
            }
            level = Optional.of(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(lv)));
        }
        Optional<Boolean> bypass = Json.has(b, "bypassesPlayerLimit")
                ? Optional.of(Json.bool(b, "bypassesPlayerLimit")) : Optional.empty();
        Optional<LevelBasedPermissionSet> fLevel = level;
        ctx.exec().run(() -> list().op(p, fLevel, bypass));
        return Response.json(201, ctx.exec().call(this::operators));
    }

    private Map<String, Object> allowlist() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserWhiteListEntry e : list().getWhiteList().getEntries()) {
            if (e.getUser() != null) {
                Map<String, Object> m = PlayerResolver.toMap(e.getUser());
                m.put("online", list().getPlayer(e.getUser().id()) != null);
                out.add(m);
            }
        }
        return Json.obj("enabled", ctx.server().isUsingWhitelist(), "enforced", ctx.server().isEnforceWhitelist(), "players", out);
    }

    private Object replaceAllowlist(Request req) {
        JsonElement arr = req.json().get("players");
        if (arr == null || !arr.isJsonArray()) {
            throw ApiException.badRequest("{ \"players\": [\"名前 or UUID\", ...] } の形で送ってください");
        }
        List<NameAndId> wanted = new ArrayList<>();
        for (JsonElement e : arr.getAsJsonArray()) {
            wanted.add(PlayerResolver.resolveProfile(ctx.exec(), e.getAsString()));
        }
        ctx.exec().run(() -> {
            Set<UUID> keep = new HashSet<>();
            wanted.forEach(p -> keep.add(p.id()));
            for (UserWhiteListEntry e : List.copyOf(list().getWhiteList().getEntries())) {
                if (e.getUser() != null && !keep.contains(e.getUser().id())) {
                    list().getWhiteList().remove(e.getUser());
                }
            }
            for (NameAndId p : wanted) {
                if (!list().isWhiteListed(p)) {
                    list().getWhiteList().add(new UserWhiteListEntry(p));
                }
            }
            ctx.server().kickUnlistedPlayers();
        });
        return ctx.exec().call(this::allowlist);
    }

    private List<Map<String, Object>> bans() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserBanListEntry e : list().getBans().getEntries()) {
            if (e.getUser() != null) {
                Map<String, Object> m = PlayerResolver.toMap(e.getUser());
                m.putAll(banInfo(e));
                out.add(m);
            }
        }
        return out;
    }

    private List<Map<String, Object>> ipBans() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IpBanListEntry e : list().getIpBans().getEntries()) {
            Map<String, Object> m = Json.obj("ip", e.getUser());
            m.putAll(banInfo(e));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> banInfo(BanListEntry<?> e) {
        return Json.obj(
                "reason", e.getReason(),
                "source", e.getSource(),
                "created", e.getCreated() == null ? null : e.getCreated().toInstant().toString(),
                "expires", e.getExpires() == null ? null : e.getExpires().toInstant().toString());
    }

    private Object addBan(Request req) {
        JsonObject b = req.json();
        NameAndId p = PlayerResolver.resolveProfile(ctx.exec(), Json.str(b, "player"));
        String reason = Json.str(b, "reason", null);
        String source = Json.str(b, "source", BAN_SOURCE);
        Date expires = parseExpires(b);
        ctx.exec().run(() -> {
            list().getBans().add(new UserBanListEntry(p, null, source, expires, reason));
            ServerPlayer online = list().getPlayer(p.id());
            if (online != null) {
                online.connection.disconnect(Component.translatable("multiplayer.disconnect.banned"));
            }
        });
        return Response.json(201, ctx.exec().call(this::bans));
    }

    private Object addIpBan(Request req) {
        JsonObject b = req.json();
        String reason = Json.str(b, "reason", null);
        String source = Json.str(b, "source", BAN_SOURCE);
        Date expires = parseExpires(b);
        String ipArg = Json.str(b, "ip", null);
        String playerArg = Json.str(b, "player", null);
        if ((ipArg == null) == (playerArg == null)) {
            throw ApiException.badRequest("'ip' か 'player'（オンラインのプレイヤー）のどちらか一方を指定してください");
        }
        List<String> kicked = ctx.exec().call(() -> {
            String ip = ipArg;
            if (ip == null) {
                ip = PlayerResolver.requireOnline(ctx.server(), playerArg).getIpAddress();
            }
            if (!InetAddresses.isInetAddress(ip)) {
                throw ApiException.badRequest("IP アドレスが不正です: " + ip);
            }
            list().getIpBans().add(new IpBanListEntry(ip, null, source, expires, reason));
            List<String> names = new ArrayList<>();
            for (ServerPlayer sp : List.copyOf(list().getPlayersWithAddress(ip))) {
                names.add(sp.getPlainTextName());
                sp.connection.disconnect(Component.translatable("multiplayer.disconnect.ip_banned"));
            }
            return names;
        });
        return Response.json(201, Json.obj("kicked", kicked, "bans", ctx.exec().call(this::ipBans)));
    }

    private static Date parseExpires(JsonObject b) {
        String raw = Json.str(b, "expires", null);
        if (raw == null) {
            return null;
        }
        try {
            return Date.from(Instant.parse(raw));
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("expires は ISO 8601（例: 2026-12-31T15:00:00Z）で指定してください");
        }
    }
}
