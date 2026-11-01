package jp.mc.restapi.api;

import jp.mc.restapi.capture.ChatHistory;
import jp.mc.restapi.capture.EventHub;
import jp.mc.restapi.capture.LogCapture;
import jp.mc.restapi.config.ApiConfig;
import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.util.ServerExec;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 各 API が共有するもの（サーバー・設定・ログ・イベント）。
 */
public record ApiContext(
        DedicatedServer server,
        ServerExec exec,
        ApiConfig config,
        Path serverDir,
        EventHub hub,
        LogCapture logs,
        ChatHistory chat,
        Instant startedAt) {

    /** "overworld" / "minecraft:the_nether" / "the_end" などからディメンションを引く（サーバースレッドで呼ぶ） */
    public ServerLevel level(String dim) {
        Identifier id = Identifier.tryParse(dim.contains(":") ? dim : "minecraft:" + dim);
        if (id == null) {
            throw ApiException.badRequest("ディメンション名が不正です: " + dim);
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) {
            throw ApiException.notFound("dimension_not_found", "ディメンションがありません: " + id);
        }
        return level;
    }

    public static String dimensionId(Level level) {
        return level.dimension().identifier().toString();
    }
}
