package jp.mc.restapi.api;

import jp.mc.restapi.McRestApi;
import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.http.Json;
import jp.mc.restapi.http.Router;
import jp.mc.restapi.http.Scope;
import jp.mc.restapi.util.DailyLog;
import jp.mc.restapi.util.ServerLogs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 保存している履歴とログの一覧・ダウンロード（admin）。
 * チャット・出来事・操作の記録は DailyLog が、Minecraft のサーバーログはここで、どれも直近 keep 日分だけ残す。
 */
public final class HistoryApi {
    private static final Map<String, String> LABELS = Map.of(
            "chat", "チャット履歴",
            "events", "出来事の履歴",
            "audit", "操作の記録（MC REST API）",
            "server", "サーバーログ（Minecraft）");

    private final Map<String, DailyLog> logs = new LinkedHashMap<>();
    private final Path serverLogs;
    private final int keep;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mc-rest-api-history");
        t.setDaemon(true);
        return t;
    });

    public HistoryApi(List<DailyLog> dailyLogs, Path serverLogs, int keep) {
        for (DailyLog d : dailyLogs) {
            logs.put(d.kind(), d);
        }
        this.serverLogs = serverLogs;
        this.keep = keep;
        // 起動時と、そのあと 1 時間ごとに古いものを消す（日付が変わった直後に書き込みが無くても消えるように）
        timer.scheduleAtFixedRate(this::pruneAll, 0, 1, TimeUnit.HOURS);
    }

    public void register(Router r) {
        r.get("/history", Scope.ADMIN, req -> list());
        r.get("/history/{kind}/{name}", Scope.ADMIN, req -> {
            Path f = resolve(req.param("kind"), req.param("name"));
            return FileApi.fileResponse(f, contentType(f));
        });
    }

    public void shutdown() {
        timer.shutdownNow();
    }

    private void pruneAll() {
        logs.values().forEach(DailyLog::prune);
        pruneServerLogs();
    }

    private void pruneServerLogs() {
        for (String name : ServerLogs.prune(serverLogs, keep)) {
            McRestApi.LOGGER.info("[MC REST API] 古いサーバーログを消しました: {}", name);
        }
    }

    private Object list() throws IOException {
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (DailyLog d : logs.values()) {
            List<Map<String, Object>> files = new ArrayList<>();
            for (Path f : d.files()) {
                files.add(info(f, d.dateOf(f)));
            }
            kinds.add(Json.obj("kind", d.kind(), "label", LABELS.get(d.kind()), "files", files));
        }
        List<Map<String, Object>> server = new ArrayList<>();
        List<Path> serverFiles = new ArrayList<>();
        try (Stream<Path> s = Files.list(serverLogs)) {
            s.filter(p -> ServerLogs.LISTED.matcher(p.getFileName().toString()).matches()).forEach(serverFiles::add);
        }
        // latest.log を先頭に、あとは名前（日付）の新しい順
        serverFiles.sort(Comparator.comparing((Path p) -> !p.getFileName().toString().equals("latest.log"))
                .thenComparing(p -> p.getFileName().toString(), Comparator.reverseOrder()));
        for (Path f : serverFiles) {
            server.add(info(f, ServerLogs.dateOf(f.getFileName().toString())));
        }
        kinds.add(Json.obj("kind", "server", "label", LABELS.get("server"), "files", server));
        return Json.obj("keepDays", keep, "kinds", kinds);
    }

    private static Map<String, Object> info(Path f, String date) throws IOException {
        return Json.obj(
                "name", f.getFileName().toString(),
                "date", date,
                "size", Files.size(f),
                "modified", Files.getLastModifiedTime(f).toInstant().toString());
    }

    private Path resolve(String kind, String name) {
        Path f;
        if (kind.equals("server")) {
            if (!ServerLogs.LISTED.matcher(name).matches()) {
                throw ApiException.badRequest("サーバーログのファイル名ではありません: " + name);
            }
            f = serverLogs.resolve(name);
        } else {
            DailyLog d = logs.get(kind);
            if (d == null) {
                throw ApiException.notFound("kind_not_found", "種類は chat / events / audit / server のどれかです");
            }
            f = d.dir().resolve(name);
            if (d.dateOf(f) == null) {
                throw ApiException.badRequest("履歴のファイル名ではありません: " + name);
            }
        }
        if (!Files.isRegularFile(f)) {
            throw ApiException.notFound("file_not_found", "ありません（" + keep + " 日より古いものは消えています）: " + name);
        }
        return f;
    }

    private static String contentType(Path f) {
        String n = f.getFileName().toString();
        if (n.endsWith(".gz")) {
            return "application/gzip";
        }
        if (n.endsWith(".zip")) {
            return "application/zip";
        }
        return "text/plain; charset=utf-8";
    }
}
