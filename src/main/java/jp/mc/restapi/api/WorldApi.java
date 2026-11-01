package jp.mc.restapi.api;

import com.google.gson.JsonObject;
import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.http.Json;
import jp.mc.restapi.http.Request;
import jp.mc.restapi.http.Router;
import jp.mc.restapi.http.Scope;
import jp.mc.restapi.util.CommandRunner;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ディメンションごとの情報と、時刻・天候・スポーン・ワールドボーダー・エンティティ・ブロックの操作。
 * 書き込みはバニラのコマンドで行い、検証と仕様変更への追従をバニラに任せる。
 */
public final class WorldApi {
    private static final Set<String> WEATHER = Set.of("clear", "rain", "thunder");
    private static final Set<String> SETBLOCK_MODES = Set.of("replace", "destroy", "keep", "strict");

    private final ApiContext ctx;

    public WorldApi(ApiContext ctx) {
        this.ctx = ctx;
    }

    public void register(Router r) {
        r.get("/worlds", Scope.READ, req -> ctx.exec().call(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (ServerLevel level : ctx.server().getAllLevels()) {
                out.add(info(level, false));
            }
            return out;
        }));
        r.get("/worlds/{dim}", Scope.READ, req -> ctx.exec().call(() -> info(ctx.level(req.param("dim")), true)));
        r.put("/worlds/{dim}/time", Scope.WRITE, this::time);
        r.put("/worlds/{dim}/weather", Scope.WRITE, this::weather);
        r.put("/worlds/{dim}/spawn", Scope.WRITE, this::spawn);
        r.put("/worlds/{dim}/worldborder", Scope.WRITE, this::worldborder);
        r.get("/worlds/{dim}/entities", Scope.READ, this::entities);
        r.delete("/worlds/{dim}/entities", Scope.WRITE, this::killEntities);
        r.get("/worlds/{dim}/blocks/{x}/{y}/{z}", Scope.READ, this::getBlock);
        r.put("/worlds/{dim}/blocks/{x}/{y}/{z}", Scope.WRITE, this::setBlock);
    }

    private Map<String, Object> info(ServerLevel level, boolean detail) {
        LevelData.RespawnData spawn = level.getRespawnData();
        WorldBorder border = level.getWorldBorder();
        long clock = level.getDefaultClockTime();
        Map<String, Object> m = Json.obj(
                "dimension", ApiContext.dimensionId(level),
                "players", level.players().size(),
                "gameTime", level.getGameTime(),
                "clockTime", clock,
                "day", clock / 24000L,
                "timeOfDay", Math.floorMod(clock, 24000L),
                "raining", level.isRaining(),
                "thundering", level.isThundering(),
                "loadedChunks", level.getChunkSource().getLoadedChunksCount());
        if (detail) {
            int entities = 0;
            for (Entity ignored : level.getAllEntities()) {
                entities++;
            }
            m.put("entities", entities);
            m.put("seed", level.getSeed());
            m.put("difficulty", level.getDifficulty().getSerializedName());
            m.put("spawn", Json.obj("x", spawn.pos().getX(), "y", spawn.pos().getY(), "z", spawn.pos().getZ(),
                    "dimension", spawn.dimension().identifier().toString()));
            m.put("worldBorder", Json.obj(
                    "centerX", border.getCenterX(),
                    "centerZ", border.getCenterZ(),
                    "size", border.getSize(),
                    "lerpTarget", border.getLerpTarget(),
                    "lerpTimeMs", border.getLerpTime(),
                    "damagePerBlock", border.getDamagePerBlock(),
                    "safeZone", border.getSafeZone(),
                    "warningBlocks", border.getWarningBlocks(),
                    "warningTimeSeconds", border.getWarningTime()));
        }
        return m;
    }

    /** { "time": 1000 } / { "marker": "minecraft:day" } / { "add": 600 } / { "paused": true } / { "rate": 2.0 } */
    private Object time(Request req) {
        JsonObject b = req.json();
        List<String> cmds = new ArrayList<>();
        if (Json.has(b, "time")) {
            int t = Json.integer(b, "time");
            if (t < 0) {
                throw ApiException.badRequest("time は 0 以上で指定してください");
            }
            cmds.add("time set " + t);
        }
        if (Json.has(b, "marker")) {
            cmds.add("time set " + CommandRunner.requireId(Json.str(b, "marker"), "marker"));
        }
        if (Json.has(b, "add")) {
            cmds.add("time add " + Json.integer(b, "add"));
        }
        if (Json.has(b, "paused")) {
            cmds.add(Json.bool(b, "paused") ? "time pause" : "time resume");
        }
        if (Json.has(b, "rate")) {
            cmds.add(String.format(Locale.ROOT, "time rate %f", Json.number(b, "rate")));
        }
        if (cmds.isEmpty()) {
            throw ApiException.badRequest("time / marker / add / paused / rate のどれかを指定してください");
        }
        return runAll(req.param("dim"), cmds);
    }

    /** { "type": "rain", "seconds": 600 } */
    private Object weather(Request req) {
        JsonObject b = req.json();
        String type = Json.str(b, "type").toLowerCase(Locale.ROOT);
        if (!WEATHER.contains(type)) {
            throw ApiException.badRequest("type は clear / rain / thunder のどれかです");
        }
        String cmd = "weather " + type;
        if (Json.has(b, "seconds")) {
            int s = Json.integer(b, "seconds");
            if (s < 1) {
                throw ApiException.badRequest("seconds は 1 以上で指定してください");
            }
            cmd += " " + s + "s";
        }
        return runAll(req.param("dim"), List.of(cmd));
    }

    /** { "x": 0, "y": 64, "z": 0, "angle": 0 } */
    private Object spawn(Request req) {
        JsonObject b = req.json();
        int x = Json.integer(b, "x");
        int y = Json.integer(b, "y");
        int z = Json.integer(b, "z");
        String cmd = "setworldspawn " + x + " " + y + " " + z;
        if (Json.has(b, "angle")) {
            cmd += String.format(Locale.ROOT, " %f", Json.number(b, "angle"));
        }
        return runAll(req.param("dim"), List.of(cmd));
    }

    /** { "size": 1000, "seconds": 0, "centerX": 0, "centerZ": 0, "damagePerBlock": 0.2, "damageBuffer": 5, "warningDistance": 5, "warningTime": 15 } */
    private Object worldborder(Request req) {
        JsonObject b = req.json();
        List<String> cmds = new ArrayList<>();
        if (Json.has(b, "centerX") || Json.has(b, "centerZ")) {
            cmds.add(String.format(Locale.ROOT, "worldborder center %f %f", Json.number(b, "centerX"), Json.number(b, "centerZ")));
        }
        if (Json.has(b, "size")) {
            double size = Json.number(b, "size");
            int seconds = Json.integer(b, "seconds", 0);
            cmds.add(String.format(Locale.ROOT, "worldborder set %f", size) + (seconds > 0 ? " " + seconds : ""));
        }
        if (Json.has(b, "damagePerBlock")) {
            cmds.add(String.format(Locale.ROOT, "worldborder damage amount %f", Json.number(b, "damagePerBlock")));
        }
        if (Json.has(b, "damageBuffer")) {
            cmds.add(String.format(Locale.ROOT, "worldborder damage buffer %f", Json.number(b, "damageBuffer")));
        }
        if (Json.has(b, "warningDistance")) {
            cmds.add("worldborder warning distance " + Json.integer(b, "warningDistance"));
        }
        if (Json.has(b, "warningTime")) {
            cmds.add("worldborder warning time " + Json.integer(b, "warningTime"));
        }
        if (cmds.isEmpty()) {
            throw ApiException.badRequest("size / centerX・centerZ / damagePerBlock / damageBuffer / warningDistance / warningTime のどれかを指定してください");
        }
        return runAll(req.param("dim"), cmds);
    }

    /** ?type=minecraft:zombie&x=0&y=64&z=0&radius=32&limit=200 */
    private Object entities(Request req) {
        String type = req.query("type");
        if (type != null) {
            CommandRunner.requireId(type, "type");
        }
        Double radius = req.query("radius") == null ? null : req.queryDouble("radius", 0);
        double x = req.queryDouble("x", 0);
        double y = req.queryDouble("y", 0);
        double z = req.queryDouble("z", 0);
        int limit = Math.max(1, Math.min(req.queryInt("limit", 200), 5000));
        return ctx.exec().call(() -> {
            ServerLevel level = ctx.level(req.param("dim"));
            List<Map<String, Object>> out = new ArrayList<>();
            int total = 0;
            for (Entity e : level.getAllEntities()) {
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                if (type != null && !id.equals(type.contains(":") ? type : "minecraft:" + type)) {
                    continue;
                }
                if (radius != null && e.distanceToSqr(x, y, z) > radius * radius) {
                    continue;
                }
                total++;
                if (out.size() < limit) {
                    out.add(Json.obj(
                            "id", e.getId(),
                            "uuid", e.getStringUUID(),
                            "type", id,
                            "name", e.getName().getString(),
                            "customName", e.getCustomName() == null ? null : e.getCustomName().getString(),
                            "position", Json.obj("x", ServerApi.round(e.getX()), "y", ServerApi.round(e.getY()), "z", ServerApi.round(e.getZ())),
                            "alive", e.isAlive()));
                }
            }
            return Json.obj("total", total, "returned", out.size(), "entities", out);
        });
    }

    /** ?type=minecraft:zombie（必須）&x=&y=&z=&radius= 。プレイヤーは対象にできない */
    private Object killEntities(Request req) {
        String type = CommandRunner.requireId(req.query("type"), "type");
        String full = type.contains(":") ? type : "minecraft:" + type;
        if (full.equals("minecraft:player")) {
            throw ApiException.badRequest("プレイヤーは対象にできません");
        }
        StringBuilder selector = new StringBuilder("@e[type=" + full);
        String prefix = "";
        if (req.query("radius") != null) {
            double radius = req.queryDouble("radius", 0);
            selector.append(String.format(Locale.ROOT, ",distance=..%f", radius));
            prefix = String.format(Locale.ROOT, "execute positioned %f %f %f run ",
                    req.queryDouble("x", 0), req.queryDouble("y", 0), req.queryDouble("z", 0));
        }
        selector.append("]");
        String cmd = prefix + "kill " + selector;
        return ctx.exec().call(() -> {
            ServerLevel level = ctx.level(req.param("dim"));
            CommandRunner.Result res = CommandRunner.run(ctx.server(), level, cmd);
            return Json.obj("killed", res.result(), "output", res.output());
        });
    }

    private Object getBlock(Request req) {
        BlockPos pos = new BlockPos(req.paramInt("x"), req.paramInt("y"), req.paramInt("z"));
        return ctx.exec().call(() -> {
            ServerLevel level = ctx.level(req.param("dim"));
            if (!level.isLoaded(pos)) {
                throw ApiException.conflict("chunk_not_loaded", "そのチャンクは読み込まれていません");
            }
            BlockState state = level.getBlockState(pos);
            return Json.obj(
                    "dimension", ApiContext.dimensionId(level),
                    "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(),
                    "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                    "state", BlockStateParser.serialize(state),
                    "hasBlockEntity", level.getBlockEntity(pos) != null);
        });
    }

    /** { "block": "minecraft:oak_stairs[facing=north]", "mode": "replace" } */
    private Object setBlock(Request req) {
        JsonObject b = req.json();
        String block = CommandRunner.requireSingleLine(Json.str(b, "block"), "block");
        String mode = Json.str(b, "mode", "replace").toLowerCase(Locale.ROOT);
        if (!SETBLOCK_MODES.contains(mode)) {
            throw ApiException.badRequest("mode は replace / destroy / keep / strict のどれかです");
        }
        String cmd = "setblock " + req.paramInt("x") + " " + req.paramInt("y") + " " + req.paramInt("z") + " " + block + " " + mode;
        return runAll(req.param("dim"), List.of(cmd));
    }

    private Object runAll(String dim, List<String> cmds) {
        return ctx.exec().call(() -> {
            ServerLevel level = ctx.level(dim);
            List<Map<String, Object>> results = new ArrayList<>();
            for (String c : cmds) {
                results.add(CommandRunner.runOrFail(ctx.server(), level, c).toMap());
            }
            return Json.obj("results", results, "world", info(level, true));
        });
    }
}
