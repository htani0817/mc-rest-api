package jp.mc.restapi.config;

import com.google.gson.JsonParseException;
import jp.mc.restapi.http.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * config/mc_rest_api/config.json の中身。無い項目は既定値で補って書き戻す。
 */
public final class ApiConfig {
    /** 待ち受けアドレス。外から直接つなぐときは 0.0.0.0 */
    public String bindAddress = "127.0.0.1";
    public int port = 8765;
    /** CORS を許可するオリジン（例: http://localhost:3000）。空なら CORS ヘッダーを出さない */
    public List<String> corsAllowedOrigins = new ArrayList<>();
    /** JSON 本文の上限 */
    public long maxJsonBytes = 1024L * 1024L;
    /** ファイルアップロードの上限 */
    public long maxUploadBytes = 64L * 1024L * 1024L;
    /** ファイル管理 API の root（サーバーディレクトリからの相対） */
    public String filesRoot = ".";
    /** バックアップの保存先（サーバーディレクトリからの相対） */
    public String backupDir = "backups";
    /** 残すバックアップの数。0 以下なら自動削除しない */
    public int backupKeep = 10;
    /** /console/logs で保持する行数 */
    public int logBufferLines = 2000;
    /** /chat/history で保持する件数 */
    public int chatHistorySize = 500;
    /** チャット・出来事・操作の記録・サーバーログを残す日数（1 日 1 世代） */
    public int historyKeepDays = 7;
    /** 再起動のときに Java を終わらせる終了コード。起動した側（systemd など）がこれを見て立ち上げ直す */
    public int restartExitCode = 75;
    /** サーバースレッドで実行する処理の待ち時間（秒） */
    public int serverTaskTimeoutSeconds = 10;
    /** 同時に接続できる SSE クライアント数 */
    public int maxSseClients = 32;
    /** 認証失敗がこの回数を超えた IP を一時的に拒否する */
    public int authFailureLimit = 10;
    /** 拒否する時間（秒） */
    public int authBlockSeconds = 300;

    public static ApiConfig loadOrCreate(Path file) throws IOException {
        ApiConfig config;
        if (Files.exists(file)) {
            try {
                config = Json.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ApiConfig.class);
            } catch (JsonParseException e) {
                throw new IOException(file + " を JSON として読めません: " + e.getMessage(), e);
            }
            if (config == null) {
                config = new ApiConfig();
            }
            if (config.corsAllowedOrigins == null) {
                config.corsAllowedOrigins = new ArrayList<>();
            }
        } else {
            config = new ApiConfig();
        }
        Files.createDirectories(file.getParent());
        // 新しい項目が増えたときも書き戻しで補われる
        Files.writeString(file, Json.PRETTY.toJson(config) + "\n", StandardCharsets.UTF_8);
        return config;
    }
}
