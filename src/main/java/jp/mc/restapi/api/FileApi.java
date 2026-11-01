package jp.mc.restapi.api;

import com.google.gson.JsonObject;
import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.http.Json;
import jp.mc.restapi.http.Request;
import jp.mc.restapi.http.Response;
import jp.mc.restapi.http.Router;
import jp.mc.restapi.http.Scope;
import jp.mc.restapi.util.SafePath;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * サーバーディレクトリ内のファイル管理（admin 権限）。
 */
public final class FileApi {
    private static final Set<String> TEXT_EXT = Set.of(
            "txt", "log", "json", "json5", "toml", "yml", "yaml", "properties", "cfg", "conf", "ini",
            "md", "sh", "mcfunction", "mcmeta", "snbt", "csv", "xml", "html", "js", "css");

    private final SafePath safe;
    private final long maxUploadBytes;

    public FileApi(SafePath safe, long maxUploadBytes) {
        this.safe = safe;
        this.maxUploadBytes = maxUploadBytes;
    }

    public void register(Router r) {
        r.get("/files", Scope.ADMIN, this::list);
        r.get("/files/content", Scope.ADMIN, this::download);
        r.put("/files/content", Scope.ADMIN, this::upload);
        r.post("/files/mkdir", Scope.ADMIN, this::mkdir);
        r.post("/files/move", Scope.ADMIN, this::move);
        r.post("/files/copy", Scope.ADMIN, this::copy);
        r.delete("/files", Scope.ADMIN, this::delete);
    }

    private Object list(Request req) throws IOException {
        Path dir = safe.resolve(req.query("path", ""));
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.notFound("file_not_found", "見つかりません: " + req.query("path", ""));
        }
        if (!Files.isDirectory(dir)) {
            return info(dir);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!safe.isDenied(p)) {
                    entries.add(info(p));
                }
            }
        }
        // ディレクトリを先に、名前順
        entries.sort(Comparator.<Map<String, Object>, Boolean>comparing(m -> !"directory".equals(m.get("type")))
                .thenComparing(m -> ((String) m.get("name")).toLowerCase(Locale.ROOT)));
        return Json.obj("path", safe.relative(dir), "entries", entries);
    }

    private Object download(Request req) throws IOException {
        Path file = safe.resolve(requirePath(req.query("path")));
        if (!Files.isRegularFile(file)) {
            throw ApiException.notFound("file_not_found", "ファイルがありません: " + req.query("path"));
        }
        return fileResponse(file, contentType(file));
    }

    /** ファイルをそのまま返す（バックアップのダウンロードでも使う） */
    static Response fileResponse(Path file, String contentType) {
        return exchange -> {
            long size = Files.size(file);
            String name = file.getFileName().toString();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, size == 0 ? -1 : size);
            if (size > 0) {
                // 書き込み中のファイル（latest.log など）でも、最初に測った大きさまでで止める
                try (InputStream in = Files.newInputStream(file); OutputStream out = exchange.getResponseBody()) {
                    byte[] buf = new byte[64 * 1024];
                    long left = size;
                    int n;
                    while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                        out.write(buf, 0, n);
                        left -= n;
                    }
                }
            }
        };
    }

    private Object upload(Request req) throws IOException {
        Path file = safe.resolve(requirePath(req.query("path")));
        boolean overwrite = req.queryBool("overwrite", true);
        if (Files.isDirectory(file)) {
            throw ApiException.conflict("is_directory", "ディレクトリには書き込めません");
        }
        boolean existed = Files.exists(file);
        if (existed && !overwrite) {
            throw ApiException.conflict("file_exists", "すでにあります（上書きするなら overwrite=true）");
        }
        Files.createDirectories(file.getParent());
        // 同じディレクトリの一時ファイルに書いてから置き換える（途中で切れても元のファイルは壊れない）
        Path tmp = Files.createTempFile(file.getParent(), ".upload-", ".tmp");
        try {
            long total = 0;
            try (InputStream in = req.bodyStream(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxUploadBytes) {
                        throw new ApiException(413, "payload_too_large", "アップロードの上限は " + maxUploadBytes + " バイトです");
                    }
                    out.write(buf, 0, n);
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return Response.json(existed ? 200 : 201, info(file));
    }

    private Object mkdir(Request req) throws IOException {
        Path dir = safe.resolve(requirePath(Json.str(req.json(), "path")));
        if (Files.exists(dir) && !Files.isDirectory(dir)) {
            throw ApiException.conflict("file_exists", "同名のファイルがあります");
        }
        Files.createDirectories(dir);
        return Response.json(201, info(dir));
    }

    private Object move(Request req) throws IOException {
        JsonObject b = req.json();
        Path from = safe.resolve(requirePath(Json.str(b, "from")));
        Path to = safe.resolve(requirePath(Json.str(b, "to")));
        boolean overwrite = Json.bool(b, "overwrite", false);
        checkMutable(from);
        if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.notFound("file_not_found", "移動元がありません");
        }
        if (Files.exists(to) && !overwrite) {
            throw ApiException.conflict("file_exists", "移動先にすでにあります（上書きするなら overwrite: true）");
        }
        Files.createDirectories(to.getParent());
        if (overwrite) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(from, to);
        }
        return info(to);
    }

    private Object copy(Request req) throws IOException {
        JsonObject b = req.json();
        Path from = safe.resolve(requirePath(Json.str(b, "from")));
        Path to = safe.resolve(requirePath(Json.str(b, "to")));
        boolean overwrite = Json.bool(b, "overwrite", false);
        if (!Files.isRegularFile(from)) {
            throw ApiException.badRequest("コピーできるのはファイルだけです");
        }
        if (Files.exists(to) && !overwrite) {
            throw ApiException.conflict("file_exists", "コピー先にすでにあります（上書きするなら overwrite: true）");
        }
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        return Response.json(201, info(to));
    }

    private Object delete(Request req) throws IOException {
        Path target = safe.resolve(requirePath(req.query("path")));
        boolean recursive = req.queryBool("recursive", false);
        checkMutable(target);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.notFound("file_not_found", "見つかりません");
        }
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            boolean empty;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(target)) {
                empty = !ds.iterator().hasNext();
            }
            if (!empty && !recursive) {
                throw ApiException.conflict("directory_not_empty", "中身のあるディレクトリは recursive=true で消してください");
            }
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            Files.delete(target);
        }
        return Json.obj("deleted", safe.relative(target));
    }

    private void checkMutable(Path p) {
        if (p.equals(safe.root())) {
            throw ApiException.badRequest("root 自体は動かせません・消せません");
        }
        if (safe.containsDenied(p)) {
            throw new ApiException(403, "path_denied", "API から扱えないファイルを含んでいます");
        }
    }

    private static String requirePath(String p) {
        if (p == null || p.isBlank() || p.equals("/") || p.equals(".")) {
            throw ApiException.badRequest("'path' が必要です");
        }
        return p;
    }

    private Map<String, Object> info(Path p) throws IOException {
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String type = a.isSymbolicLink() ? "symlink" : a.isDirectory() ? "directory" : a.isRegularFile() ? "file" : "other";
        return Json.obj(
                "name", p.getFileName() == null ? "." : p.getFileName().toString(),
                "path", safe.relative(p),
                "type", type,
                "size", a.isDirectory() ? null : a.size(),
                "modified", a.lastModifiedTime().toInstant().toString(),
                "text", a.isRegularFile() && TEXT_EXT.contains(ext(p)));
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String contentType(Path p) {
        String e = ext(p);
        if (e.equals("json") || e.equals("mcmeta")) {
            return "application/json; charset=utf-8";
        }
        if (TEXT_EXT.contains(e)) {
            return "text/plain; charset=utf-8";
        }
        if (e.equals("zip") || e.equals("jar")) {
            return "application/zip";
        }
        if (e.equals("png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }
}
