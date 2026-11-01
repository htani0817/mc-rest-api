package jp.mc.restapi;

import com.mojang.logging.LogUtils;
import jp.mc.restapi.api.AccessListApi;
import jp.mc.restapi.api.ApiContext;
import jp.mc.restapi.api.BackupApi;
import jp.mc.restapi.api.ConsoleApi;
import jp.mc.restapi.api.EventApi;
import jp.mc.restapi.api.FileApi;
import jp.mc.restapi.api.GameDataApi;
import jp.mc.restapi.api.GameruleApi;
import jp.mc.restapi.api.HistoryApi;
import jp.mc.restapi.api.ModApi;
import jp.mc.restapi.api.PlayerApi;
import jp.mc.restapi.api.ServerApi;
import jp.mc.restapi.api.SettingsApi;
import jp.mc.restapi.api.WorldApi;
import jp.mc.restapi.capture.ChatHistory;
import jp.mc.restapi.capture.EventHub;
import jp.mc.restapi.capture.GameEvents;
import jp.mc.restapi.capture.LogCapture;
import jp.mc.restapi.config.ApiConfig;
import jp.mc.restapi.config.TokenStore;
import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.http.HttpServerHost;
import jp.mc.restapi.http.Router;
import jp.mc.restapi.http.Scope;
import jp.mc.restapi.util.AuditLog;
import jp.mc.restapi.util.DailyLog;
import jp.mc.restapi.util.Restarter;
import jp.mc.restapi.util.SafePath;
import jp.mc.restapi.util.ServerExec;
import net.minecraft.server.dedicated.DedicatedServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Mod(value = McRestApi.MODID, dist = Dist.DEDICATED_SERVER)
public class McRestApi {
    public static final String MODID = "mc_rest_api";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final Path configDir = FMLPaths.CONFIGDIR.get().resolve(MODID);
    private ApiConfig config;
    private final EventHub hub = new EventHub();
    private LogCapture logs;
    private ChatHistory chat;
    private GameEvents events;
    private HttpServerHost http;
    private AuditLog audit;
    private BackupApi backups;
    private Restarter restarter;
    private HistoryApi history;
    /** 1 日 1 ファイルで残す履歴（logs/mc-rest-api/<種類>/） */
    private DailyLog chatLog;
    private DailyLog eventLog;
    private DailyLog auditLog;

    public McRestApi() {
        Path historyDir = FMLPaths.GAMEDIR.get().resolve("logs").resolve("mc-rest-api");
        migrateFromOldName(configDir, historyDir);
        try {
            config = ApiConfig.loadOrCreate(configDir.resolve("config.json"));
        } catch (IOException e) {
            LOGGER.error("[MC REST API] 設定を読めないため既定値で動きます: {}", e.getMessage());
            config = new ApiConfig();
        }
        // 起動中のログも拾えるよう、Mod の読み込み時点で付ける
        logs = LogCapture.install(config.logBufferLines);
        logs.hub(hub);
        int keep = Math.max(1, config.historyKeepDays);
        try {
            chatLog = new DailyLog(historyDir.resolve("chat"), "chat", keep);
            eventLog = new DailyLog(historyDir.resolve("events"), "events", keep);
            auditLog = new DailyLog(historyDir.resolve("audit"), "audit", keep);
        } catch (IOException e) {
            LOGGER.error("[MC REST API] 履歴の保存先を作れないため、履歴はファイルに残りません: {}", e.getMessage());
        }
        chat = new ChatHistory(config.chatHistorySize, hub, chatLog);
        events = new GameEvents(hub, chat, eventLog);
        events.register();
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, this::onStarted);
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, this::onStopping);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, this::onStopped);
    }

    /**
     * 旧名（Tani REST API）のときの設定・トークンと履歴を引き継ぐ。新しい側がまだ無いときだけ行う。
     * 設定は旧 jar に戻せるようコピー、履歴は 2 か所で古いものが消えずに残らないよう移動する。
     */
    private static void migrateFromOldName(Path configDir, Path historyDir) {
        try {
            Path oldConfig = configDir.resolveSibling("tani_rest_api");
            if (!Files.exists(configDir) && Files.isDirectory(oldConfig)) {
                Files.createDirectories(configDir);
                for (String name : List.of("config.json", "tokens.json")) {
                    Path f = oldConfig.resolve(name);
                    if (Files.isRegularFile(f)) {
                        // tokens.json の権限（所有者だけ読める）もそのまま写す
                        Files.copy(f, configDir.resolve(name), StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
                LOGGER.info("[MC REST API] 旧名の設定とトークンを引き継ぎました: {} → {}", oldConfig, configDir);
            }
            Path oldHistory = historyDir.resolveSibling("tani-rest-api");
            if (!Files.exists(historyDir) && Files.isDirectory(oldHistory)) {
                Files.move(oldHistory, historyDir);
                LOGGER.info("[MC REST API] 旧名の履歴を移しました: {} → {}", oldHistory, historyDir);
            }
        } catch (IOException e) {
            LOGGER.warn("[MC REST API] 旧名の設定・履歴を引き継げませんでした: {}", e.toString());
        }
    }

    private void onStarted(ServerStartedEvent event) {
        if (!(event.getServer() instanceof DedicatedServer server)) {
            return;
        }
        try {
            Path serverDir = server.getServerDirectory().toAbsolutePath().normalize();
            Path tokenFile = configDir.resolve("tokens.json").toAbsolutePath().normalize();
            TokenStore.LoadResult loaded = TokenStore.loadOrCreate(tokenFile);
            if (loaded.created()) {
                LOGGER.info("[MC REST API] トークンを生成しました（admin / write / read）: {}", tokenFile);
            }
            audit = new AuditLog(auditLog);

            ServerExec exec = new ServerExec(server, config.serverTaskTimeoutSeconds);
            ApiContext ctx = new ApiContext(server, exec, config, serverDir, hub, logs, chat, Instant.now());
            SafePath safe = new SafePath(serverDir.resolve(config.filesRoot), List.of(tokenFile));

            Router router = new Router();
            router.get("/openapi.json", Scope.NONE, req -> openapi());
            restarter = new Restarter(server, config.restartExitCode);
            new ServerApi(ctx, restarter).register(router);
            new SettingsApi(ctx).register(router);
            new GameruleApi(ctx).register(router);
            new PlayerApi(ctx).register(router);
            new AccessListApi(ctx).register(router);
            new WorldApi(ctx).register(router);
            new GameDataApi(ctx).register(router);
            new ModApi().register(router);
            new ConsoleApi(ctx).register(router);
            new EventApi(hub, loaded.store(), config.maxSseClients).register(router);
            new FileApi(safe, config.maxUploadBytes).register(router);
            backups = new BackupApi(ctx);
            backups.register(router);
            history = new HistoryApi(Stream.of(chatLog, eventLog, auditLog).filter(Objects::nonNull).toList(),
                    serverDir.resolve("logs"), Math.max(1, config.historyKeepDays));
            history.register(router);

            http = new HttpServerHost(config, loaded.store(), router, audit);
            http.start();
            LOGGER.info("[MC REST API] http://{}:{}{} で待ち受けています（エンドポイント {} 個）",
                    config.bindAddress, config.port, HttpServerHost.PREFIX, router.routes().size());
            if (!config.bindAddress.equals("127.0.0.1") && !config.bindAddress.equals("localhost")) {
                LOGGER.warn("[MC REST API] {} で待ち受けています。外部に公開する場合は HTTPS のリバースプロキシを前に置いてください", config.bindAddress);
            }
            events.server("started");
        } catch (Exception e) {
            LOGGER.error("[MC REST API] 起動に失敗しました。サーバーはそのまま動きますが API は使えません", e);
        }
    }

    private void onStopping(ServerStoppingEvent event) {
        events.server("stopping");
        if (restarter != null) {
            restarter.onStopping();
        }
        if (http != null) {
            http.stop();
            http = null;
        }
        if (backups != null) {
            backups.shutdown();
        }
        if (history != null) {
            history.shutdown();
        }
        for (DailyLog d : new DailyLog[] {chatLog, eventLog, auditLog}) {
            if (d != null) {
                try {
                    d.close();
                } catch (IOException ignored) {
                    // 停止中なので無視
                }
            }
        }
    }

    private void onStopped(ServerStoppedEvent event) {
        if (restarter != null) {
            restarter.onStopped();
            restarter = null;
        }
    }

    private static Object openapi() throws IOException {
        try (InputStream in = McRestApi.class.getResourceAsStream("/openapi.json")) {
            if (in == null) {
                throw ApiException.notFound("not_found", "openapi.json が jar に入っていません");
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return (jp.mc.restapi.http.Response) exchange -> {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            };
        }
    }
}
