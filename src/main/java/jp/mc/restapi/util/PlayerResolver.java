package jp.mc.restapi.util;

import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.http.Json;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * API で受けたプレイヤー指定（UUID か名前）を解決する。
 */
public final class PlayerResolver {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private PlayerResolver() {
    }

    public static UUID parseUuid(String s) {
        if (s == null || s.length() < 32) {
            return null;
        }
        try {
            if (s.length() == 32) {
                s = s.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
            }
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** オンラインのプレイヤーを探す。いなければ null（サーバースレッドで呼ぶ） */
    public static ServerPlayer findOnline(MinecraftServer server, String player) {
        UUID id = parseUuid(player);
        return id != null ? server.getPlayerList().getPlayer(id) : server.getPlayerList().getPlayerByName(player);
    }

    /** オンラインのプレイヤーを探す。いなければ 404（サーバースレッドで呼ぶ） */
    public static ServerPlayer requireOnline(MinecraftServer server, String player) {
        ServerPlayer p = findOnline(server, player);
        if (p == null) {
            throw ApiException.notFound("player_offline", "オンラインのプレイヤーにいません: " + player);
        }
        return p;
    }

    /**
     * オフラインも含めて名前と UUID を解決する。キャッシュに無い名前は Mojang に問い合わせるので、
     * サーバースレッド以外から呼ぶ。
     */
    public static NameAndId resolveProfile(ServerExec exec, String player) {
        if (player == null || player.isBlank()) {
            throw ApiException.badRequest("'player' が必要です");
        }
        MinecraftServer server = exec.server();
        ServerPlayer online = exec.call(() -> findOnline(server, player));
        if (online != null) {
            return online.nameAndId();
        }
        UUID id = parseUuid(player);
        Optional<NameAndId> found;
        if (id != null) {
            found = server.services().nameToIdCache().get(id);
            if (found.isEmpty()) {
                // 一度も来ていない UUID でも登録はできるよう、名前は UUID で仮置きする
                found = Optional.of(new NameAndId(id, id.toString()));
            }
        } else {
            if (!NAME.matcher(player).matches()) {
                throw ApiException.badRequest("プレイヤー名が不正です: " + player);
            }
            found = server.services().nameToIdCache().get(player);
        }
        return found.orElseThrow(() -> ApiException.notFound("player_not_found", "プレイヤーが見つかりません: " + player));
    }

    public static Map<String, Object> toMap(NameAndId p) {
        return Json.obj("name", p.name(), "uuid", p.id().toString());
    }
}
