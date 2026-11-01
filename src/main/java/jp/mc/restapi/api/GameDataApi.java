package jp.mc.restapi.api;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.http.Json;
import jp.mc.restapi.http.Request;
import jp.mc.restapi.http.Response;
import jp.mc.restapi.http.Router;
import jp.mc.restapi.http.Scope;
import jp.mc.restapi.util.CommandRunner;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * スコアボード・データパック・レジストリ（WebUI の選択肢用の ID 一覧）。
 */
public final class GameDataApi {
    /** スコアの持ち主・目的名に使える文字（空白や記号を含むとコマンドが壊れるため） */
    private static final Pattern SCORE_NAME = Pattern.compile("^[A-Za-z0-9_.+\\-#$]{1,40}$");

    private final ApiContext ctx;

    public GameDataApi(ApiContext ctx) {
        this.ctx = ctx;
    }

    public void register(Router r) {
        r.get("/scoreboard/objectives", Scope.READ, req -> ctx.exec().call(this::objectives));
        r.post("/scoreboard/objectives", Scope.WRITE, this::addObjective);
        r.delete("/scoreboard/objectives/{name}", Scope.WRITE, req -> {
            String name = scoreName(req.param("name"), "name");
            return ctx.exec().call(() -> CommandRunner.runOrFail(ctx.server(), null, "scoreboard objectives remove " + name).toMap());
        });
        r.get("/scoreboard/scores", Scope.READ, this::scores);
        r.put("/scoreboard/scores/{objective}/{holder}", Scope.WRITE, req -> {
            String objective = scoreName(req.param("objective"), "objective");
            String holder = scoreName(req.param("holder"), "holder");
            int score = Json.integer(req.json(), "score");
            return ctx.exec().call(() -> CommandRunner.runOrFail(ctx.server(), null,
                    "scoreboard players set " + holder + " " + objective + " " + score).toMap());
        });
        r.delete("/scoreboard/scores/{objective}/{holder}", Scope.WRITE, req -> {
            String objective = scoreName(req.param("objective"), "objective");
            String holder = scoreName(req.param("holder"), "holder");
            return ctx.exec().call(() -> CommandRunner.runOrFail(ctx.server(), null,
                    "scoreboard players reset " + holder + " " + objective).toMap());
        });
        r.get("/scoreboard/teams", Scope.READ, req -> ctx.exec().call(this::teams));

        r.get("/datapacks", Scope.READ, req -> ctx.exec().call(this::datapacks));
        r.post("/datapacks/{id}/enable", Scope.ADMIN, req -> datapack(req, true));
        r.post("/datapacks/{id}/disable", Scope.ADMIN, req -> datapack(req, false));

        r.get("/registries", Scope.READ, req -> ctx.server().registryAccess().registries()
                .map(e -> e.key().identifier().toString()).sorted().toList());
        r.get("/registries/{registry}", Scope.READ, this::registry);
    }

    private List<Map<String, Object>> objectives() {
        ServerScoreboard sb = ctx.server().getScoreboard();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Objective o : sb.getObjectives()) {
            out.add(Json.obj(
                    "name", o.getName(),
                    "displayName", o.getDisplayName().getString(),
                    "criteria", o.getCriteria().getName(),
                    "renderType", o.getRenderType().getSerializedName(),
                    "entries", sb.listPlayerScores(o).size()));
        }
        return out;
    }

    /** { "name": "kills", "criteria": "playerKillCount", "displayName": "キル数" } */
    private Object addObjective(Request req) {
        JsonObject b = req.json();
        String name = scoreName(Json.str(b, "name"), "name");
        String criteria = Json.str(b, "criteria", "dummy");
        if (!criteria.matches("^[A-Za-z0-9_.:\\-]+$")) {
            throw ApiException.badRequest("criteria が不正です: " + criteria);
        }
        String display = Json.has(b, "displayName") ? " " + Json.GSON.toJson(Json.str(b, "displayName")) : "";
        return Response.json(201, ctx.exec().call(() -> CommandRunner.runOrFail(ctx.server(), null,
                "scoreboard objectives add " + name + " " + criteria + display).toMap()));
    }

    /** ?objective=kills か ?holder=TANI0817（どちらか必須） */
    private Object scores(Request req) {
        String objective = req.query("objective");
        String holder = req.query("holder");
        if (objective == null && holder == null) {
            throw ApiException.badRequest("objective か holder を指定してください");
        }
        return ctx.exec().call(() -> {
            ServerScoreboard sb = ctx.server().getScoreboard();
            List<Map<String, Object>> out = new ArrayList<>();
            if (objective != null) {
                Objective o = sb.getObjective(objective);
                if (o == null) {
                    throw ApiException.notFound("objective_not_found", "スコアの目的がありません: " + objective);
                }
                Collection<PlayerScoreEntry> entries = sb.listPlayerScores(o);
                for (PlayerScoreEntry e : entries) {
                    if (holder == null || holder.equals(e.owner())) {
                        out.add(Json.obj("objective", o.getName(), "holder", e.owner(), "score", e.value()));
                    }
                }
            } else {
                for (Object2IntMap.Entry<Objective> e : sb.listPlayerScores(ScoreHolder.forNameOnly(holder)).object2IntEntrySet()) {
                    out.add(Json.obj("objective", e.getKey().getName(), "holder", holder, "score", e.getIntValue()));
                }
            }
            out.sort(Comparator.comparing(m -> (String) m.get("holder")));
            return out;
        });
    }

    private List<Map<String, Object>> teams() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlayerTeam t : ctx.server().getScoreboard().getPlayerTeams()) {
            out.add(Json.obj(
                    "name", t.getName(),
                    "displayName", t.getDisplayName().getString(),
                    "color", t.getColor().map(c -> c.getSerializedName()).orElse(null),
                    "prefix", t.getPlayerPrefix().getString(),
                    "suffix", t.getPlayerSuffix().getString(),
                    "friendlyFire", t.isAllowFriendlyFire(),
                    "seeFriendlyInvisibles", t.canSeeFriendlyInvisibles(),
                    "nameTagVisibility", t.getNameTagVisibility().toString().toLowerCase(Locale.ROOT),
                    "collisionRule", t.getCollisionRule().toString().toLowerCase(Locale.ROOT),
                    "members", List.copyOf(t.getPlayers())));
        }
        return out;
    }

    private List<Map<String, Object>> datapacks() {
        PackRepository repo = ctx.server().getPackRepository();
        Collection<String> selected = repo.getSelectedIds();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Pack p : repo.getAvailablePacks()) {
            out.add(Json.obj(
                    "id", p.getId(),
                    "title", p.getTitle().getString(),
                    "description", p.getDescription().getString(),
                    "enabled", selected.contains(p.getId()),
                    "required", p.isRequired()));
        }
        return out;
    }

    private Object datapack(Request req, boolean enable) {
        String id = CommandRunner.requireSingleLine(req.param("id"), "id");
        if (id.contains("\"") || id.contains("\\")) {
            throw ApiException.badRequest("データパック ID が不正です");
        }
        // データパックの切り替えはリロードを伴うので待ち時間を長めにする
        return ctx.exec().call(() -> {
            if (ctx.server().getPackRepository().getPack(id) == null) {
                throw ApiException.notFound("datapack_not_found", "データパックがありません: " + id);
            }
            return CommandRunner.runOrFail(ctx.server(), null,
                    "datapack " + (enable ? "enable" : "disable") + " \"" + id + "\"").toMap();
        }, 120);
    }

    /** ?q=diamond で絞り込み */
    private Object registry(Request req) {
        String name = req.param("registry");
        Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
        if (id == null) {
            throw ApiException.badRequest("レジストリ名が不正です: " + name);
        }
        String q = req.query("q", "").toLowerCase(Locale.ROOT);
        RegistryAccess access = ctx.server().registryAccess();
        Registry<?> reg = access.lookup(ResourceKey.createRegistryKey(id))
                .orElseThrow(() -> ApiException.notFound("registry_not_found", "レジストリがありません: " + id + "（一覧は /registries）"));
        List<String> ids = reg.keySet().stream().map(Identifier::toString)
                .filter(s -> q.isEmpty() || s.contains(q)).sorted().toList();
        return Json.obj("registry", id.toString(), "count", ids.size(), "ids", ids);
    }

    private static String scoreName(String v, String field) {
        if (v == null || !SCORE_NAME.matcher(v).matches()) {
            throw ApiException.badRequest("'" + field + "' に使えるのは英数字と _ . + - # $ だけです（40 文字まで）");
        }
        return v;
    }
}
