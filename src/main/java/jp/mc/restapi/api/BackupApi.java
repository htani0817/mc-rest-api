package jp.mc.restapi.api;

import jp.mc.restapi.McRestApi;
import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.http.Json;
import jp.mc.restapi.http.Request;
import jp.mc.restapi.http.Response;
import jp.mc.restapi.http.Router;
import jp.mc.restapi.http.Scope;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ワールドのバックアップ（zip）。作成は非同期ジョブで、保存を止めてから固めて、終わったら元に戻す。
 * 復元はサーバー停止中にしかできないので API には無い（README の手順で行う）。
 */
public final class BackupApi {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.\\-]+\\.zip$");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final Instant startedAt = Instant.now();
        volatile String state = "running";
        volatile String phase = "queued";
        volatile String file;
        volatile long files;
        volatile long bytes;
        volatile String error;
        volatile Instant finishedAt;

        Map<String, Object> toMap() {
            return Json.obj("id", id, "state", state, "phase", phase, "file", file, "files", files, "bytes", bytes,
                    "error", error, "startedAt", startedAt.toString(),
                    "finishedAt", finishedAt == null ? null : finishedAt.toString());
        }
    }

    private final ApiContext ctx;
    private final Path dir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mc-rest-api-backup");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    public BackupApi(ApiContext ctx) {
        this.ctx = ctx;
        this.dir = ctx.serverDir().resolve(ctx.config().backupDir).normalize();
    }

    public void register(Router r) {
        r.get("/backups", Scope.ADMIN, req -> list());
        r.post("/backups", Scope.ADMIN, req -> start());
        r.get("/backups/jobs", Scope.ADMIN, req -> {
            synchronized (jobs) {
                return jobs.values().stream().map(Job::toMap).toList();
            }
        });
        r.get("/backups/jobs/{id}", Scope.ADMIN, req -> {
            synchronized (jobs) {
                Job j = jobs.get(req.param("id"));
                if (j == null) {
                    throw ApiException.notFound("job_not_found", "ジョブがありません");
                }
                return j.toMap();
            }
        });
        r.get("/backups/{name}", Scope.ADMIN, req -> FileApi.fileResponse(file(req), "application/zip"));
        r.delete("/backups/{name}", Scope.ADMIN, req -> {
            Path f = file(req);
            Files.delete(f);
            return Json.obj("deleted", f.getFileName().toString());
        });
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    private Path file(Request req) {
        String name = req.param("name");
        if (!NAME.matcher(name).matches()) {
            throw ApiException.badRequest("バックアップ名が不正です");
        }
        Path f = dir.resolve(name);
        if (!Files.isRegularFile(f)) {
            throw ApiException.notFound("backup_not_found", "バックアップがありません: " + name);
        }
        return f;
    }

    private Object list() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return Json.obj("directory", dir.toString(), "backups", out);
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> NAME.matcher(p.getFileName().toString()).matches()).sorted(Comparator.reverseOrder()).toList()) {
                out.add(Json.obj("name", p.getFileName().toString(), "size", Files.size(p),
                        "created", Files.getLastModifiedTime(p).toInstant().toString()));
            }
        }
        return Json.obj("directory", dir.toString(), "backups", out);
    }

    private Object start() {
        if (!running.compareAndSet(false, true)) {
            throw ApiException.conflict("backup_running", "バックアップが実行中です");
        }
        Job job = new Job();
        synchronized (jobs) {
            jobs.put(job.id, job);
            // ジョブ履歴は直近 20 件だけ持つ
            while (jobs.size() > 20) {
                jobs.remove(jobs.keySet().iterator().next());
            }
        }
        worker.submit(() -> run(job));
        return Response.json(202, job.toMap());
    }

    private void run(Job job) {
        boolean prevAutoSave = true;
        boolean autoSaveChanged = false;
        try {
            job.phase = "saving";
            Path world = ctx.server().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            // 保存を止めて全部書き出す（save-off → save-all flush と同じ）
            prevAutoSave = ctx.exec().call(() -> {
                boolean prev = ctx.server().isAutoSave();
                ctx.server().setAutoSave(false);
                ctx.server().saveEverything(true, true, true);
                return prev;
            }, 600);
            autoSaveChanged = true;

            job.phase = "zipping";
            Files.createDirectories(dir);
            String name = "world-" + LocalDateTime.now().format(STAMP) + ".zip";
            Path tmp = dir.resolve(name + ".part");
            Path target = dir.resolve(name);
            job.file = name;
            zip(world, tmp, job);
            Files.move(tmp, target);

            job.phase = "pruning";
            prune();
            job.state = "done";
            job.phase = "done";
            McRestApi.LOGGER.info("[REST API] バックアップを作成しました: {}（{} ファイル, {} バイト）", name, job.files, Files.size(target));
        } catch (Throwable t) {
            job.state = "failed";
            job.error = t.toString();
            McRestApi.LOGGER.error("[REST API] バックアップに失敗しました", t);
        } finally {
            if (autoSaveChanged) {
                boolean restore = prevAutoSave;
                try {
                    ctx.exec().run(() -> ctx.server().setAutoSave(restore));
                } catch (RuntimeException e) {
                    McRestApi.LOGGER.error("[REST API] 自動保存の設定を戻せませんでした（save-on を手動で実行してください）", e);
                }
            }
            job.finishedAt = Instant.now();
            running.set(false);
        }
    }

    private void zip(Path world, Path out, Job job) throws IOException {
        // バックアップ先がワールドの中にあっても自分自身を含めない
        Path self = dir.toAbsolutePath().normalize();
        String base = world.getFileName() == null ? "world" : world.getFileName().toString();
        try (OutputStream os = Files.newOutputStream(out); ZipOutputStream zos = new ZipOutputStream(os)) {
            Files.walkFileTree(world, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    return d.toAbsolutePath().normalize().startsWith(self) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                    if (f.getFileName().toString().equals("session.lock")) {
                        return FileVisitResult.CONTINUE;
                    }
                    String entry = base + "/" + world.relativize(f).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(entry));
                    Files.copy(f, zos);
                    zos.closeEntry();
                    job.files++;
                    job.bytes += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException exc) {
                    // 保存中に消えた一時ファイルなどは飛ばす
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(out);
            throw e;
        }
    }

    private void prune() throws IOException {
        int keep = ctx.config().backupKeep;
        if (keep <= 0) {
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().startsWith("world-") && NAME.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.reverseOrder()).toList();
        }
        for (int i = keep; i < files.size(); i++) {
            Files.deleteIfExists(files.get(i));
        }
    }
}
