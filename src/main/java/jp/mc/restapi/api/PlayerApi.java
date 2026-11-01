package jp.mc.restapi.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.http.Json;
import jp.mc.restapi.http.Request;
import jp.mc.restapi.http.Router;
import jp.mc.restapi.http.Scope;
import jp.mc.restapi.util.CommandRunner;
import jp.mc.restapi.util.PlayerResolver;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * プレイヤーの一覧・詳細・持ち物・操作。
 */
public final class PlayerApi {
    private static final Set<String> GAME_MODES = Set.of("survival", "creative", "adventure", "spectator");

    private final ApiContext ctx;

    public PlayerApi(ApiContext ctx) {
        this.ctx = ctx;
    }

    public void register(Router r) {
        r.get("/players", Scope.READ, req -> ctx.exec().call(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (ServerPlayer p : ctx.server().getPlayerList().getPlayers()) {
                out.add(summary(p));
            }
            return out;
        }));
        r.get("/players/known", Scope.READ, req -> known());
        r.get("/players/{player}", Scope.READ, this::detail);
        r.get("/players/{player}/inventory", Scope.READ, req -> ctx.exec().call(() -> {
            ServerPlayer p = PlayerResolver.requireOnline(ctx.server(), req.param("player"));
            return Json.obj("selectedSlot", p.getInventory().getSelectedSlot(), "items", items(p.getInventory()));
        }));
        r.get("/players/{player}/enderchest", Scope.READ, req -> ctx.exec().call(() -> {
            ServerPlayer p = PlayerResolver.requireOnline(ctx.server(), req.param("player"));
            return Json.obj("items", items(p.getEnderChestInventory()));
        }));
        r.post("/players/{player}/give", Scope.WRITE, this::give);
        r.post("/players/{player}/inventory/clear", Scope.WRITE, this::clear);
        r.post("/players/{player}/kick", Scope.WRITE, this::kick);
        r.post("/players/{player}/teleport", Scope.WRITE, this::teleport);
        r.put("/players/{player}/gamemode", Scope.WRITE, this::gamemode);
        r.patch("/players/{player}/vitals", Scope.WRITE, this::vitals);
        r.get("/players/{player}/effects", Scope.READ, req -> ctx.exec().call(() -> {
            ServerPlayer p = PlayerResolver.requireOnline(ctx.server(), req.param("player"));
            List<Map<String, Object>> out = new ArrayList<>();
            for (MobEffectInstance e : p.getActiveEffects()) {
                out.add(Json.obj(
                        "effect", e.getEffect().getRegisteredName(),
                        "amplifier", e.getAmplifier(),
                        "durationTicks", e.isInfiniteDuration() ? -1 : e.getDuration(),
                        "ambient", e.isAmbient(),
                        "visible", e.isVisible(),
                        "showIcon", e.showIcon()));
            }
            return out;
        }));
        r.post("/players/{player}/effects", Scope.WRITE, this::addEffect);
        r.delete("/players/{player}/effects", Scope.WRITE, req -> {
            String effect = req.query("effect");
            if (effect != null) {
                CommandRunner.requireId(effect, "effect");
            }
            return ctx.exec().call(() -> {
                ServerPlayer p = PlayerResolver.requireOnline(ctx.server(), req.param("player"));
                return CommandRunner.runOrFail(ctx.server(), null,
                        "effect clear " + target(p) + (effect == null ? "" : " " + effect)).toMap();
            });
        });
        r.post("/players/{player}/message", Scope.WRITE, this::message);
        r.get("/players/{player}/stats", Scope.READ, req -> playerFile(req, LevelResource.PLAYER_STATS_DIR, true));
        r.get("/players/{player}/advancements", Scope.READ, req -> playerFile(req, LevelResource.PLAYER_ADVANCEMENTS_DIR, false));
    }

    /**
     * コマンドに渡すプレイヤー指定。UUID だとバニラは「エンティティも含みうる指定」と扱い、
     * gamemode・give・experience・clear など players 専用の引数で弾かれるので名前を使う。
     */
    private static String target(ServerPlayer p) {
        return p.getScoreboardName();
    }

    private Map<String, Object> summary(ServerPlayer p) {
        return Json.obj(
                "name", p.getPlainTextName(),
                "uuid", p.getStringUUID(),
                "dimension", ApiContext.dimensionId(p.level()),
                "position", Json.obj("x", ServerApi.round(p.getX()), "y", ServerApi.round(p.getY()), "z", ServerApi.round(p.getZ())),
                "gameMode", p.gameMode().getName(),
                "health", ServerApi.round(p.getHealth()),
                "ping", p.connection.latency());
    }

    private Object detail(Request req) {
        String player = req.param("player");
        Map<String, Object> online = ctx.exec().call(() -> {
            ServerPlayer p = PlayerResolver.findOnline(ctx.server(), player);
            if (p == null) {
                return null;
            }
            Map<String, Object> m = summary(p);
            m.put("online", true);
            m.put("rotation", Json.obj("yaw", ServerApi.round(p.getYRot()), "pitch", ServerApi.round(p.getXRot())));
            m.put("maxHealth", ServerApi.round(p.getMaxHealth()));
            m.put("food", p.getFoodData().getFoodLevel());
            m.put("saturation", ServerApi.round(p.getFoodData().getSaturationLevel()));
            m.put("air", p.getAirSupply());
            m.put("xp", Json.obj("level", p.experienceLevel, "progress", ServerApi.round(p.experienceProgress),
                    "total", p.totalExperience));
            m.put("ip", p.getIpAddress());
            m.put("language", p.getLanguage());
            m.put("alive", p.isAlive());
            m.put("sleeping", p.isSleeping());
            m.put("onFire", p.isOnFire());
            m.put("flying", p.getAbilities().flying);
            m.putAll(flags(p.nameAndId()));
            return m;
        });
        if (online != null) {
            return online;
        }
        NameAndId profile = PlayerResolver.resolveProfile(ctx.exec(), player);
        Map<String, Object> m = PlayerResolver.toMap(profile);
        m.put("online", false);
        m.putAll(ctx.exec().call(() -> flags(profile)));
        return m;
    }

    /** OP・ホワイトリスト・BAN の状態（サーバースレッドで呼ぶ） */
    private Map<String, Object> flags(NameAndId p) {
        var list = ctx.server().getPlayerList();
        var op = list.getOps().get(p);
        return Json.obj(
                "op", op != null,
                "opLevel", op == null ? 0 : op.permissions().level().id(),
                "allowlisted", list.isWhiteListed(p),
                "banned", list.getBans().isBanned(p));
    }

    /** usercache.json（一度でも来たことのあるプレイヤー） */
    private Object known() throws IOException {
        Path file = ctx.serverDir().resolve("usercache.json");
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (!root.isJsonArray()) {
            return out;
        }
        List<NameAndId> profiles = new ArrayList<>();
        List<String> expires = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            java.util.UUID id = PlayerResolver.parseUuid(Json.str(o, "uuid", ""));
            if (id != null) {
                profiles.add(new NameAndId(id, Json.str(o, "name", "")));
                expires.add(Json.str(o, "expiresOn", null));
            }
        }
        return ctx.exec().call(() -> {
            for (int i = 0; i < profiles.size(); i++) {
                NameAndId p = profiles.get(i);
                Map<String, Object> m = PlayerResolver.toMap(p);
                m.put("online", ctx.server().getPlayerList().getPlayer(p.id()) != null);
                m.put("cacheExpiresOn", expires.get(i));
                m.putAll(flags(p));
                out.add(m);
            }
            return out;
        });
    }

    private List<Map<String, Object>> items(Container c) {
        var ops = ctx.server().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack stack = c.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            JsonElement json = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
            out.add(Json.obj(
                    "slot", i,
                    "id", stack.typeHolder().getRegisteredName(),
                    "count", stack.getCount(),
                    "name", stack.getHoverName().getString(),
                    "item", json));
        }
        return out;
    }

    private Object give(Request req) {
        JsonObject b = req.json();
        String item = CommandRunner.requireId(Json.str(b, "item"), "item");
        String components = CommandRunner.requireSingleLine(Json.str(b, "components", ""), "components");
        if (!components.isEmpty() && !components.startsWith("[")) {
            throw ApiException.badRequest("components は [minecraft:custom_name='\"名前\"'] のような形で指定してください");
        }
        int count = Json.integer(b, "count", 1);
        if (count < 1 || count > 6400) {
            throw ApiException.badRequest("count は 1〜6400 で指定してください");
        }
        return ctx.exec().call(() -> {
            ServerPlayer p = PlayerResolver.requireOnline(ctx.server(), req.param("player"));
            return CommandRunner.runOrFail(ctx.server(), null,
                    "give " + target(p) + " " + item + components + " " + count).toMap();
        });
    }

    private Object clear(Request req) {
        JsonObject b = req.json();
        String item = Json.has(b, "item") ? CommandRunner.requireId(Json.str(b, "item"), "item") : null;
        Integer max = Json.has(b, "maxCount") ? Json.integer(b, "maxCount") : null;
        if (max != null && item == null) {
            throw ApiException.badRequest("maxCount を使うときは item も指定してください");
        }
        return ctx.exec().call(() -> {
            ServerPlayer p = PlayerResolver.requireOnline(ctx.server(), req.param("player"));
            String cmd = "clear " + target(p) + (item == null ? "" : " " + item) + (max == null ? "" : " " + max);
            return CommandRunner.run(ctx.server(), cmd).toMap();
        });
    }

    private Object kick(Request req) {
        String reason = CommandRunner.requireSingleLine(Json.str(req.json(), "reason", ""), "reason");
        return ctx.exec().call(() -> {
            ServerPlayer p = PlayerResolver.requireOnline(ctx.server(), req.param("player"));
            p.connection.disconnect(reason.isEmpty()
                    ? Component.translatable("multiplayer.disconnect.kicked") : Component.literal(reason));
            return Json.obj("kicked", p.getPlainTextName());
        });
    }

    private Object teleport(Request req) {
        JsonObject b = req.json();
        String target = Json.str(b, "target", null);
        return ctx.exec().call(() -> {
            ServerPlayer p = PlayerResolver.requireOnline(ctx.server(), req.param("player"));
            if (target != null) {
                ServerPlayer t = PlayerResolver.requireOnline(ctx.server(), target);
                return CommandRunner.runOrFail(ctx.server(), null, "tp " + target(p) + " " + target(t)).toMap();
            }
            double x = Json.number(b, "x");
            double y = Json.number(b, "y");
            double z = Json.number(b, "z");
            ServerLevel level = Json.has(b, "dimension") ? ctx.level(Json.str(b, "dimension")) : p.level();
            String rot = "";
            if (Json.has(b, "yaw") || Json.has(b, "pitch")) {
                rot = " " + Json.number(b, "yaw", p.getYRot()) + " " + Json.number(b, "pitch", p.getXRot());
            }
            String cmd = String.format(Locale.ROOT, "tp %s %f %f %f%s", target(p), x, y, z, rot);
            return CommandRunner.runOrFail(ctx.server(), level, cmd).toMap();
        });
    }

    private Object gamemode(Request req) {
        String mode = Json.str(req.json(), "mode").toLowerCase(Locale.ROOT);
        if (!GAME_MODES.contains(mode)) {
            throw ApiException.badRequest("mode は survival / creative / adventure / spectator のどれかです");
        }
        return ctx.exec().call(() -> {
            ServerPlayer p = PlayerResolver.requireOnline(ctx.server(), req.param("player"));
            return CommandRunner.runOrFail(ctx.server(), null, "gamemode " + mode + " " + target(p)).toMap();
        });
    }

    private Object vitals(Request req) {
        JsonObject b = req.json();
        Double health = Json.has(b, "health") ? Json.number(b, "health") : null;
        Integer food = Json.has(b, "food") ? Json.integer(b, "food") : null;
        Double saturation = Json.has(b, "saturation") ? Json.number(b, "saturation") : null;
        Integer xpLevel = Json.has(b, "xpLevel") ? Json.integer(b, "xpLevel") : null;
        Integer xpPoints = Json.has(b, "xpPoints") ? Json.integer(b, "xpPoints") : null;
        if (food != null && (food < 0 || food > 20)) {
            throw ApiException.badRequest("food は 0〜20 で指定してください");
        }
        if (xpLevel != null && xpLevel < 0 || xpPoints != null && xpPoints < 0) {
            throw ApiException.badRequest("xpLevel / xpPoints は 0 以上で指定してください");
        }
        ctx.exec().run(() -> {
            ServerPlayer p = PlayerResolver.requireOnline(ctx.server(), req.param("player"));
            // 失敗しうるコマンドを先に実行し、途中で失敗したときに一部だけ変わるのを避ける
            if (xpLevel != null) {
                CommandRunner.runOrFail(ctx.server(), null, "experience set " + target(p) + " " + xpLevel + " levels");
            }
            if (xpPoints != null) {
                CommandRunner.runOrFail(ctx.server(), null, "experience set " + target(p) + " " + xpPoints + " points");
            }
            if (health != null) {
                p.setHealth((float) Math.max(0, Math.min(health, p.getMaxHealth())));
            }
            if (food != null) {
                p.getFoodData().setFoodLevel(food);
            }
            if (saturation != null) {
                p.getFoodData().setSaturation((float) Math.max(0, Math.min(saturation, 20)));
            }
        });
        return detail(req);
    }

    private Object addEffect(Request req) {
        JsonObject b = req.json();
        String effect = CommandRunner.requireId(Json.str(b, "effect"), "effect");
        String seconds = "30";
        if (Json.has(b, "seconds")) {
            seconds = Json.str(b, "seconds").equalsIgnoreCase("infinite") ? "infinite" : String.valueOf(Json.integer(b, "seconds"));
        }
        int amplifier = Json.integer(b, "amplifier", 0);
        boolean hide = Json.bool(b, "hideParticles", false);
        if (amplifier < 0 || amplifier > 255) {
            throw ApiException.badRequest("amplifier は 0〜255 で指定してください");
        }
        String sec = seconds;
        return ctx.exec().call(() -> {
            ServerPlayer p = PlayerResolver.requireOnline(ctx.server(), req.param("player"));
            return CommandRunner.runOrFail(ctx.server(), null,
                    "effect give " + target(p) + " " + effect + " " + sec + " " + amplifier + " " + hide).toMap();
        });
    }

    private Object message(Request req) {
        JsonObject b = req.json();
        String text = Json.str(b, "message");
        boolean overlay = Json.bool(b, "overlay", false);
        return ctx.exec().call(() -> {
            ServerPlayer p = PlayerResolver.requireOnline(ctx.server(), req.param("player"));
            p.sendSystemMessage(Component.literal(text), overlay);
            return Json.obj("sent", true, "to", p.getPlainTextName());
        });
    }

    /** world/players/stats/<uuid>.json などを返す。オンラインなら先に保存して最新にする */
    private Object playerFile(Request req, LevelResource dir, boolean stats) throws IOException {
        NameAndId profile = PlayerResolver.resolveProfile(ctx.exec(), req.param("player"));
        ctx.exec().run(() -> {
            ServerPlayer p = ctx.server().getPlayerList().getPlayer(profile.id());
            if (p != null) {
                if (stats) {
                    p.getStats().save();
                } else {
                    p.getAdvancements().save();
                }
            }
        });
        Path file = ctx.server().getWorldPath(dir).resolve(profile.id() + ".json");
        if (!Files.exists(file)) {
            throw ApiException.notFound("no_data", "データがありません（一度もログインしていない可能性があります）");
        }
        JsonElement data = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        return Json.obj("player", PlayerResolver.toMap(profile), stats ? "stats" : "advancements", data);
    }
}
