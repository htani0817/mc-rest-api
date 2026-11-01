package jp.mc.restapi.api;

import jp.mc.restapi.McRestApi;
import jp.mc.restapi.capture.LogCapture;
import jp.mc.restapi.http.Json;
import jp.mc.restapi.http.Request;
import jp.mc.restapi.http.Router;
import jp.mc.restapi.http.Scope;
import jp.mc.restapi.util.CommandRunner;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 任意コマンドの実行・ログ・チャット。
 */
public final class ConsoleApi {
    private final ApiContext ctx;

    public ConsoleApi(ApiContext ctx) {
        this.ctx = ctx;
    }

    public void register(Router r) {
        r.post("/console/command", Scope.ADMIN, this::command);
        r.get("/console/logs", Scope.ADMIN, this::logs);
        r.get("/chat/history", Scope.READ, req -> {
            int limit = Math.max(1, Math.min(req.queryInt("limit", 100), ctx.config().chatHistorySize));
            return ctx.chat().buffer().last(limit).stream().map(m -> m.toMap()).toList();
        });
        r.post("/chat", Scope.WRITE, this::chat);
    }

    /** { "command": "list" }（先頭の / はあってもなくてもよい） */
    private Object command(Request req) {
        String command = Json.str(req.json(), "command").trim();
        McRestApi.LOGGER.info("[REST API] {} がコマンドを実行: /{}", req.token().name(),
                command.startsWith("/") ? command.substring(1) : command);
        // 重いコマンド（fill・保存など）もあるので待ち時間を長めにする
        return ctx.exec().call(() -> CommandRunner.run(ctx.server(), command).toMap(), 60);
    }

    /** ?lines=200&level=WARN（指定レベル以上）&q=文字列 */
    private Object logs(Request req) {
        int lines = Math.max(1, Math.min(req.queryInt("lines", 200), ctx.config().logBufferLines));
        String level = req.query("level", "").toUpperCase(Locale.ROOT);
        String q = req.query("q", "");
        int min = rank(level);
        List<Map<String, Object>> out = ctx.logs().buffer().last(ctx.config().logBufferLines).stream()
                .filter(l -> rank(l.level()) >= min)
                .filter(l -> q.isEmpty() || l.message().contains(q))
                .map(LogCapture.Line::toMap)
                .toList();
        return out.subList(Math.max(0, out.size() - lines), out.size());
    }

    private static int rank(String level) {
        return switch (level) {
            case "TRACE" -> 0;
            case "DEBUG" -> 1;
            case "INFO" -> 2;
            case "WARN" -> 3;
            case "ERROR" -> 4;
            case "FATAL" -> 5;
            default -> -1;
        };
    }

    /** { "message": "こんにちは", "sender": "WebUI" } → チャット欄に「[WebUI] こんにちは」 */
    private Object chat(Request req) {
        String message = CommandRunner.requireSingleLine(Json.str(req.json(), "message"), "message");
        String sender = CommandRunner.requireSingleLine(Json.str(req.json(), "sender", "Server"), "sender");
        ctx.exec().run(() -> ctx.server().getPlayerList()
                .broadcastSystemMessage(Component.literal("[" + sender + "] " + message), false));
        McRestApi.LOGGER.info("[REST API chat] [{}] {}", sender, message);
        ctx.chat().add(sender, null, message, "api");
        return Json.obj("sent", true);
    }
}
