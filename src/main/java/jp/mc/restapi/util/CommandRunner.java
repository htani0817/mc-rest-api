package jp.mc.restapi.util;

import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.http.Json;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * サーバーコマンドを実行し、出力と成否を受け取る。必ずサーバースレッドで呼ぶ。
 */
public final class CommandRunner {
    /** 名前空間つき ID（minecraft:diamond など）。コマンドへ埋め込む値の検証に使う */
    public static final Pattern ID = Pattern.compile("^([a-z0-9_.-]+:)?[a-z0-9_./-]+$");

    public record Result(boolean success, int result, List<String> output) {
        public Map<String, Object> toMap() {
            return Json.obj("success", success, "result", result, "output", output);
        }
    }

    private CommandRunner() {
    }

    public static Result run(MinecraftServer server, String command) {
        return run(server, null, command);
    }

    /** level を指定するとそのディメンションで実行する（execute in と同じ） */
    public static Result run(MinecraftServer server, ServerLevel level, String command) {
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        if (cmd.isBlank() || cmd.indexOf('\n') >= 0 || cmd.indexOf('\r') >= 0) {
            throw ApiException.badRequest("コマンドが空か、改行を含んでいます");
        }
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        boolean[] success = {false};
        int[] result = {0};
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                output.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return true;
            }
        };
        CommandSourceStack source = server.createCommandSourceStack()
                .withSource(capture)
                .withCallback((ok, value) -> {
                    success[0] |= ok;
                    result[0] += value;
                });
        if (level != null) {
            source = source.withLevel(level);
        }
        server.getCommands().performPrefixedCommand(source, cmd);
        return new Result(success[0], result[0], List.copyOf(output));
    }

    /** 失敗したら出力をメッセージにして 400 を返す（構造化 API 用） */
    public static Result runOrFail(MinecraftServer server, ServerLevel level, String command) {
        Result r = run(server, level, command);
        if (!r.success()) {
            String msg = r.output().isEmpty() ? "コマンドが失敗しました: " + command : String.join(" / ", r.output());
            throw new ApiException(400, "command_failed", msg);
        }
        return r;
    }

    public static String requireId(String value, String field) {
        if (value == null || !ID.matcher(value).matches()) {
            throw ApiException.badRequest("'" + field + "' は minecraft:stone のような ID で指定してください");
        }
        return value;
    }

    /** コマンドに埋め込む自由文字列（アイテムのコンポーネント指定など）の最低限の検証 */
    public static String requireSingleLine(String value, String field) {
        if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw ApiException.badRequest("'" + field + "' に改行は使えません");
        }
        return value;
    }
}
