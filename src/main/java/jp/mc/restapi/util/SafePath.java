package jp.mc.restapi.util;

import jp.mc.restapi.http.ApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * ファイル管理 API のパスを root の内側に閉じ込める。
 * 「..」とシンボリックリンクの両方で root の外へ出るのを拒否する。
 */
public final class SafePath {
    private final Path root;
    private final Path realRoot;
    private final List<Path> denied;

    public SafePath(Path root, List<Path> denied) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.realRoot = this.root.toRealPath();
        this.denied = denied.stream().map(p -> p.toAbsolutePath().normalize()).toList();
    }

    public Path root() {
        return root;
    }

    /** API で受けた相対パスを、root の内側の絶対パスにする */
    public Path resolve(String userPath) {
        String p = userPath == null ? "" : userPath.replace('\\', '/');
        if (p.indexOf('\0') >= 0) {
            throw ApiException.badRequest("パスに使えない文字があります");
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        Path resolved;
        try {
            resolved = root.resolve(p).normalize();
        } catch (InvalidPathException e) {
            throw ApiException.badRequest("パスが不正です: " + e.getMessage());
        }
        if (!resolved.startsWith(root)) {
            throw new ApiException(400, "path_outside_root", "root の外は指定できません");
        }
        // 実在する一番深い祖先を実体パスに直し、リンクで外へ出ていないか見る
        Path existing = resolved;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            try {
                Path real = existing.toRealPath();
                if (!real.startsWith(realRoot)) {
                    throw new ApiException(400, "path_outside_root", "root の外を指すリンクは辿れません");
                }
            } catch (IOException e) {
                throw new ApiException(400, "path_outside_root", "パスを確かめられません: " + e.getMessage());
            }
        }
        if (isDenied(resolved)) {
            throw new ApiException(403, "path_denied", "このパスは API から扱えません");
        }
        return resolved;
    }

    /** path 自身か、その配下に拒否対象があるか（ディレクトリの削除・移動の前に確認する） */
    public boolean containsDenied(Path path) {
        Path p = path.toAbsolutePath().normalize();
        for (Path d : denied) {
            if (d.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    public boolean isDenied(Path path) {
        Path p = path.toAbsolutePath().normalize();
        for (Path d : denied) {
            if (p.startsWith(d)) {
                return true;
            }
        }
        return false;
    }

    /** root からの相対パス（区切りは /） */
    public String relative(Path path) {
        String rel = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return rel.isEmpty() ? "." : rel;
    }
}
