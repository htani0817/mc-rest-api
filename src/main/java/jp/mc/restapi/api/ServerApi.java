package jp.mc.restapi.api;

import com.google.gson.JsonObject;
import com.sun.management.OperatingSystemMXBean;
import jp.mc.restapi.McRestApi;
import jp.mc.restapi.http.Json;
import jp.mc.restapi.http.Request;
import jp.mc.restapi.http.Router;
import jp.mc.restapi.http.Scope;
import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.util.CommandRunner;
import jp.mc.restapi.util.Restarter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * サーバー本体の情報・状態・性能と、保存・停止・再起動・全体メッセージ・リロード。
 */
public final class ServerApi {
    /** 再起動までに置ける最長の秒数 */
    static final int MAX_RESTART_DELAY = 600;

    private final ApiContext ctx;
    private final Restarter restarter;

    public ServerApi(ApiContext ctx, Restarter restarter) {
        this.ctx = ctx;
        this.restarter = restarter;
    }

    public void register(Router r) {
        r.get("/health", Scope.NONE, req -> Json.obj("status", "ok"));
        r.get("/server", Scope.READ, req -> info());
        r.get("/server/status", Scope.READ, req -> ctx.exec().call(this::status));
        r.get("/performance", Scope.READ, req -> performance());
        r.post("/server/save", Scope.WRITE, this::save);
        r.post("/server/stop", Scope.ADMIN, req -> {
            McRestApi.LOGGER.info("REST API からサーバー停止を受け付けました（token={}）", req.token().name());
            ctx.exec().run(() -> ctx.server().halt(false));
            return Json.obj("stopping", true);
        });
        r.post("/server/restart", Scope.ADMIN, this::restart);
        r.delete("/server/restart", Scope.ADMIN, req -> {
            if (!restarter.cancel()) {
                throw ApiException.notFound("no_restart", "予告中の再起動はありません");
            }
            McRestApi.LOGGER.info("REST API から再起動を取り消しました（token={}）", req.token().name());
            return Json.obj("cancelled", true);
        });
        r.post("/server/broadcast", Scope.WRITE, this::broadcast);
        r.post("/server/reload", Scope.ADMIN, req -> ctx.exec().call(
                () -> CommandRunner.run(ctx.server(), "reload").toMap()));
    }

    private Object info() {
        DedicatedServer s = ctx.server();
        String neoVersion = ModList.get().getModContainerById("neoforge")
                .map(c -> c.getModInfo().getVersion().toString()).orElse(null);
        return Json.obj(
                "minecraftVersion", s.getServerVersion(),
                "serverModName", s.getServerModName(),
                "neoforgeVersion", neoVersion,
                "bukkit", ModApi.bukkitInfo(),
                "modVersion", ModList.get().getModContainerById(McRestApi.MODID)
                        .map(c -> c.getModInfo().getVersion().toString()).orElse(null),
                "motd", s.getMotd(),
                "port", s.getPort(),
                "localIp", s.getLocalIp(),
                "worldName", s.getWorldData().getLevelName(),
                "hardcore", s.isHardcore(),
                "defaultGameMode", s.getDefaultGameType().getName(),
                "onlineMode", s.usesAuthentication(),
                "startedAt", ctx.startedAt().toString(),
                "uptimeSeconds", Duration.between(ctx.startedAt(), Instant.now()).toSeconds(),
                "java", Json.obj("version", System.getProperty("java.version"), "vendor", System.getProperty("java.vendor")),
                "os", Json.obj("name", System.getProperty("os.name"), "version", System.getProperty("os.version"),
                        "arch", System.getProperty("os.arch")),
                "serverDirectory", ctx.serverDir().toString());
    }

    private Map<String, Object> status() {
        DedicatedServer s = ctx.server();
        double msptAvg = s.getAverageTickTimeNanos() / 1_000_000.0;
        float targetTps = s.tickRateManager().tickrate();
        return Json.obj(
                "ready", s.isReady(),
                "running", s.isRunning(),
                "paused", s.isPaused(),
                "players", Json.obj("online", s.getPlayerCount(), "max", s.getMaxPlayers(),
                        "names", List.of(s.getPlayerList().getPlayerNamesArray())),
                "tickCount", s.getTickCount(),
                "tps", round(Math.min(targetTps, msptAvg <= 0 ? targetTps : 1000.0 / msptAvg)),
                "targetTps", targetTps,
                "msptAverage", round(msptAvg),
                "frozen", s.tickRateManager().isFrozen(),
                "autosave", s.isAutoSave(),
                "restartAt", restarter.pendingAtText(),
                "uptimeSeconds", Duration.between(ctx.startedAt(), Instant.now()).toSeconds());
    }

    private Object performance() {
        Map<String, Object> game = ctx.exec().call(() -> {
            DedicatedServer s = ctx.server();
            long[] times = s.getTickTimesNanos();
            double avg = s.getAverageTickTimeNanos() / 1_000_000.0;
            float targetTps = s.tickRateManager().tickrate();
            List<Map<String, Object>> dims = new ArrayList<>();
            for (ServerLevel level : s.getAllLevels()) {
                long[] t = s.getTickTime(level.dimension());
                double dimAvg = t == null ? 0 : average(t) / 1_000_000.0;
                int entities = 0;
                for (var ignored : level.getAllEntities()) {
                    entities++;
                }
                dims.add(Json.obj(
                        "dimension", ApiContext.dimensionId(level),
                        "msptAverage", round(dimAvg),
                        "loadedChunks", level.getChunkSource().getLoadedChunksCount(),
                        "entities", entities,
                        "players", level.players().size()));
            }
            return Json.obj(
                    "tps", round(Math.min(targetTps, avg <= 0 ? targetTps : 1000.0 / avg)),
                    "targetTps", targetTps,
                    "msptAverage", round(avg),
                    "msptMax", round(max(times) / 1_000_000.0),
                    "msptMin", round(min(times) / 1_000_000.0),
                    "dimensions", dims);
        });

        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        Runtime rt = Runtime.getRuntime();
        List<Map<String, Object>> gcs = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcs.add(Json.obj("name", gc.getName(), "count", gc.getCollectionCount(), "timeMs", gc.getCollectionTime()));
        }
        Map<String, Object> cpu = Json.obj("availableProcessors", rt.availableProcessors());
        if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os) {
            cpu.put("processLoad", round(os.getProcessCpuLoad()));
            cpu.put("systemLoad", round(os.getCpuLoad()));
            cpu.put("systemLoadAverage", round(os.getSystemLoadAverage()));
            cpu.put("totalMemoryBytes", os.getTotalMemorySize());
            cpu.put("freeMemoryBytes", os.getFreeMemorySize());
        }
        Map<String, Object> disk = null;
        try {
            FileStore store = Files.getFileStore(ctx.serverDir());
            disk = Json.obj("totalBytes", store.getTotalSpace(), "usableBytes", store.getUsableSpace());
        } catch (IOException ignored) {
            // 取れない環境では null
        }
        return Json.obj(
                "game", game,
                "memory", Json.obj(
                        "heapUsedBytes", mem.getHeapMemoryUsage().getUsed(),
                        "heapCommittedBytes", mem.getHeapMemoryUsage().getCommitted(),
                        "heapMaxBytes", rt.maxMemory(),
                        "nonHeapUsedBytes", mem.getNonHeapMemoryUsage().getUsed()),
                "gc", gcs,
                "threads", Json.obj("live", ManagementFactory.getThreadMXBean().getThreadCount(),
                        "peak", ManagementFactory.getThreadMXBean().getPeakThreadCount()),
                "cpu", cpu,
                "disk", disk,
                "uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    }

    private Object save(Request req) {
        boolean flush = Json.bool(req.json(), "flush", false);
        // 大きいワールドの flush は時間がかかるので待ち時間を長めにする
        boolean ok = ctx.exec().call(() -> ctx.server().saveEverything(false, flush, true), 300);
        return Json.obj("saved", ok, "flush", flush);
    }

    /** { "delaySeconds": 10, "message": "Mod の更新" } → 予告してから停止し、終了コードで起動した側に立ち上げ直してもらう */
    private Object restart(Request req) {
        JsonObject b = req.json();
        int delay = Json.integer(b, "delaySeconds", 10);
        if (delay < 0 || delay > MAX_RESTART_DELAY) {
            throw ApiException.badRequest("'delaySeconds' は 0〜" + MAX_RESTART_DELAY + " で指定してください");
        }
        String message = b.has("message") ? CommandRunner.requireSingleLine(Json.str(b, "message"), "message") : null;
        if (!restarter.schedule(delay, message)) {
            throw ApiException.conflict("restart_pending", "もう再起動を予告しています（取り消すときは DELETE /server/restart）");
        }
        McRestApi.LOGGER.info("REST API から {} 秒後の再起動を受け付けました（token={}）", delay, req.token().name());
        return restarter.state();
    }

    private Object broadcast(Request req) {
        JsonObject b = req.json();
        String message = CommandRunner.requireSingleLine(Json.str(b, "message"), "message");
        boolean overlay = Json.bool(b, "overlay", false);
        ctx.exec().run(() -> ctx.server().getPlayerList().broadcastSystemMessage(Component.literal(message), overlay));
        return Json.obj("sent", true, "overlay", overlay);
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double average(long[] a) {
        long sum = 0;
        int n = 0;
        for (long v : a) {
            if (v > 0) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? 0 : (double) sum / n;
    }

    private static long max(long[] a) {
        long m = 0;
        for (long v : a) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static long min(long[] a) {
        long m = Long.MAX_VALUE;
        for (long v : a) {
            if (v > 0) {
                m = Math.min(m, v);
            }
        }
        return m == Long.MAX_VALUE ? 0 : m;
    }
}
