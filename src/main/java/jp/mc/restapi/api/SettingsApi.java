package jp.mc.restapi.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.http.Json;
import jp.mc.restapi.http.Request;
import jp.mc.restapi.http.Router;
import jp.mc.restapi.http.Scope;
import jp.mc.restapi.util.CommandRunner;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 実行中に変えられるサーバー設定（バニラの管理プロトコルの serversettings と同じ 20 項目）。
 * 変更はバニラと同じく server.properties にも書き戻される。
 */
public final class SettingsApi {
    /** parser は値を検証して「適用する処理」を返す。全項目の検証が済んでから適用する */
    private record Setting(Function<DedicatedServer, Object> getter, Function<JsonElement, Consumer<DedicatedServer>> parser) {
    }

    private final ApiContext ctx;
    private final Map<String, Setting> settings = new LinkedHashMap<>();

    public SettingsApi(ApiContext ctx) {
        this.ctx = ctx;
        bool("autosave", DedicatedServer::isAutoSave, DedicatedServer::setAutoSave);
        // Paper 系（Youer など）は難易度がワールドごとで、getWorldData() の値は /difficulty でも変わらないため、オーバーワールドの値を返す
        add("difficulty", s -> s.overworld().getDifficulty().getSerializedName(), v -> {
            Difficulty d = v.isJsonPrimitive() ? Difficulty.byName(v.getAsString()) : null;
            if (d == null) {
                throw ApiException.badRequest("difficulty は peaceful / easy / normal / hard のどれかです");
            }
            return s -> {
                // server.properties への書き戻し。バニラはここで全ワールドにも効くが、Paper 系はワールドに効かない
                s.setDifficulty(d);
                if (!s.isHardcore()) {
                    for (ServerLevel level : s.getAllLevels()) {
                        if (level.getDifficulty() != d) {
                            CommandRunner.run(s, level, "difficulty " + d.getSerializedName());
                        }
                    }
                }
            };
        });
        bool("enforce_allowlist", DedicatedServer::isEnforceWhitelist, (s, v) -> {
            s.setEnforceWhitelist(v);
            s.kickUnlistedPlayers();
        });
        bool("use_allowlist", DedicatedServer::isUsingWhitelist, (s, v) -> {
            s.setUsingWhitelist(v);
            s.kickUnlistedPlayers();
        });
        integer("max_players", DedicatedServer::getMaxPlayers, DedicatedServer::setMaxPlayers, 0, Integer.MAX_VALUE);
        integer("pause_when_empty_seconds", DedicatedServer::pauseWhenEmptySeconds, DedicatedServer::setPauseWhenEmptySeconds, -1, Integer.MAX_VALUE);
        integer("player_idle_timeout", DedicatedServer::playerIdleTimeout, DedicatedServer::setPlayerIdleTimeout, 0, Integer.MAX_VALUE);
        bool("allow_flight", DedicatedServer::allowFlight, DedicatedServer::setAllowFlight);
        add("motd", DedicatedServer::getMotd, v -> {
            if (!v.isJsonPrimitive()) {
                throw ApiException.badRequest("motd は文字列で指定してください");
            }
            String motd = v.getAsString();
            return s -> s.setMotd(motd);
        });
        integer("spawn_protection_radius", DedicatedServer::spawnProtectionRadius, DedicatedServer::setSpawnProtectionRadius, 0, Integer.MAX_VALUE);
        bool("force_game_mode", DedicatedServer::forceGameMode, DedicatedServer::setForceGameMode);
        add("game_mode", s -> s.gameMode().getName(), v -> {
            GameType t = v.isJsonPrimitive() ? GameType.byName(v.getAsString(), null) : null;
            if (t == null) {
                throw ApiException.badRequest("game_mode は survival / creative / adventure / spectator のどれかです");
            }
            return s -> s.setGameMode(t);
        });
        integer("view_distance", DedicatedServer::viewDistance, DedicatedServer::setViewDistance, 2, 32);
        integer("simulation_distance", DedicatedServer::simulationDistance, DedicatedServer::setSimulationDistance, 2, 32);
        bool("accept_transfers", DedicatedServer::acceptsTransfers, DedicatedServer::setAcceptsTransfers);
        integer("status_heartbeat_interval", DedicatedServer::statusHeartbeatInterval,
                DedicatedServer::setStatusHeartbeatInterval, 0, Integer.MAX_VALUE);
        integer("operator_user_permission_level", s -> s.operatorUserPermissions().level().id(),
                (s, v) -> s.setOperatorUserPermissions(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(v))), 0, 4);
        bool("hide_online_players", DedicatedServer::hidesOnlinePlayers, DedicatedServer::setHidesOnlinePlayers);
        bool("status_replies", DedicatedServer::repliesToStatus, DedicatedServer::setRepliesToStatus);
        integer("entity_broadcast_range", DedicatedServer::entityBroadcastRangePercentage,
                DedicatedServer::setEntityBroadcastRangePercentage, 10, 1000);
    }

    public void register(Router r) {
        r.get("/settings", Scope.READ, req -> ctx.exec().call(this::all));
        r.get("/settings/{key}", Scope.READ, req -> {
            Setting s = find(req.param("key"));
            return ctx.exec().call(() -> Json.obj("key", req.param("key"), "value", s.getter.apply(ctx.server())));
        });
        r.patch("/settings", Scope.WRITE, this::patch);
    }

    private Map<String, Object> all() {
        Map<String, Object> out = new LinkedHashMap<>();
        settings.forEach((k, s) -> out.put(k, s.getter.apply(ctx.server())));
        return out;
    }

    private Object patch(Request req) {
        JsonObject body = req.json();
        if (body.isEmpty()) {
            throw ApiException.badRequest("変更する設定を { \"motd\": \"...\" } の形で送ってください");
        }
        List<Consumer<DedicatedServer>> changes = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : body.entrySet()) {
            Setting s = find(e.getKey());
            if (e.getValue().isJsonNull()) {
                throw ApiException.badRequest("'" + e.getKey() + "' に null は指定できません");
            }
            changes.add(s.parser.apply(e.getValue()));
        }
        return ctx.exec().call(() -> {
            changes.forEach(c -> c.accept(ctx.server()));
            return all();
        });
    }

    private Setting find(String key) {
        Setting s = settings.get(key);
        if (s == null) {
            throw ApiException.notFound("setting_not_found", "設定がありません: " + key + "（使えるもの: " + String.join(", ", settings.keySet()) + "）");
        }
        return s;
    }

    private void add(String key, Function<DedicatedServer, Object> getter, Function<JsonElement, Consumer<DedicatedServer>> parser) {
        settings.put(key, new Setting(getter, parser));
    }

    private void bool(String key, Function<DedicatedServer, Boolean> getter, BiConsumer<DedicatedServer, Boolean> setter) {
        add(key, getter::apply, v -> {
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) {
                throw ApiException.badRequest("'" + key + "' は true / false で指定してください");
            }
            boolean b = v.getAsBoolean();
            return s -> setter.accept(s, b);
        });
    }

    private void integer(String key, Function<DedicatedServer, Integer> getter, BiConsumer<DedicatedServer, Integer> setter, int min, int max) {
        add(key, getter::apply, v -> {
            int n;
            try {
                n = v.getAsInt();
                if (v.getAsDouble() != n) {
                    throw new NumberFormatException();
                }
            } catch (RuntimeException e) {
                throw ApiException.badRequest("'" + key + "' は整数で指定してください");
            }
            if (n < min || n > max) {
                throw ApiException.badRequest("'" + key + "' は " + min + "〜" + max + " で指定してください");
            }
            return s -> setter.accept(s, n);
        });
    }
}
