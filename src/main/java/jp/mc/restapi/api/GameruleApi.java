package jp.mc.restapi.api;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.http.Json;
import jp.mc.restapi.http.Request;
import jp.mc.restapi.http.Router;
import jp.mc.restapi.http.Scope;
import jp.mc.restapi.util.CommandRunner;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * ゲームルールの一覧・取得・変更。
 * Youer 26.3（Paper 系）はゲームルールがワールドごとで、MinecraftServer.getGameRules() は例外を投げる。
 * そのため読むときはオーバーワールドの値を返し、変えるときは値が違うワールドごとに /gamerule を流す。
 * GameRules.set() も Youer は第 3 引数が ServerLevel（vanilla は MinecraftServer）で、直接呼ぶと NoSuchMethodError になる。
 * （vanilla は全ワールドが同じ GameRules なので、最初の 1 回で残りのワールドも同じ値になり、コマンドは 1 回で済む）
 */
public final class GameruleApi {
    private final ApiContext ctx;

    public GameruleApi(ApiContext ctx) {
        this.ctx = ctx;
    }

    public void register(Router r) {
        r.get("/gamerules", Scope.READ, req -> ctx.exec().call(() -> {
            GameRules rules = ctx.server().overworld().getGameRules();
            List<Map<String, Object>> list = rules.availableRules().map(rule -> describe(rules, rule)).toList();
            return list;
        }));
        r.get("/gamerules/{name}", Scope.READ, req -> ctx.exec().call(() -> {
            GameRules rules = ctx.server().overworld().getGameRules();
            return describe(rules, find(rules, req.param("name")));
        }));
        r.put("/gamerules/{name}", Scope.WRITE, this::update);
    }

    private Object update(Request req) {
        JsonElement value = req.json().get("value");
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw ApiException.badRequest("{ \"value\": true } や { \"value\": 3 } の形で送ってください");
        }
        String raw = value.getAsString();
        return ctx.exec().call(() -> {
            GameRules rules = ctx.server().overworld().getGameRules();
            GameRule<?> rule = find(rules, req.param("name"));
            apply(rule, raw);
            return describe(rules, rule);
        });
    }

    private <T> void apply(GameRule<T> rule, String raw) {
        DataResult<T> parsed = rule.deserialize(raw);
        T v = parsed.result().orElseThrow(() -> ApiException.badRequest(
                "値が不正です（" + rule.gameRuleType().getSerializedName() + "）: " + parsed.error().map(e -> e.message()).orElse(raw)));
        MinecraftServer server = ctx.server();
        String command = "gamerule " + rule.getIdentifier() + " " + v;
        for (ServerLevel level : server.getAllLevels()) {
            if (!Objects.equals(level.getGameRules().get(rule), v)) {
                CommandRunner.runOrFail(server, level, command);
            }
        }
    }

    private static <T> Map<String, Object> describe(GameRules rules, GameRule<T> rule) {
        T value = rules.get(rule);
        return Json.obj(
                "key", rule.id(),
                "id", rule.getIdentifier().toString(),
                "type", rule.gameRuleType().getSerializedName(),
                "category", rule.category().id().toString(),
                "value", value,
                "default", rule.defaultValue());
    }

    private static GameRule<?> find(GameRules rules, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return rules.availableRules()
                .filter(r -> r.id().equals(n) || r.getIdentifier().toString().equals(n))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("gamerule_not_found", "ゲームルールがありません: " + name));
    }
}
