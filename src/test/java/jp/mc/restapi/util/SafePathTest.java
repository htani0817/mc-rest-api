package jp.mc.restapi.util;

import jp.mc.restapi.http.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SafePathTest {
    @TempDir
    Path tmp;

    private SafePath safe() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("server"));
        Path tokens = root.resolve("config/mc_rest_api/tokens.json");
        Files.createDirectories(tokens.getParent());
        Files.writeString(tokens, "{}");
        return new SafePath(root, List.of(tokens));
    }

    @Test
    void 通常の相対パスは通る() throws IOException {
        SafePath s = safe();
        Path p = s.resolve("world/level.dat");
        assertTrue(p.startsWith(s.root()));
        assertEquals("world/level.dat", s.relative(p));
    }

    @Test
    void 先頭スラッシュとバックスラッシュはroot基準に直す() throws IOException {
        SafePath s = safe();
        assertEquals("config/a.toml", s.relative(s.resolve("/config\\a.toml")));
        assertEquals(".", s.relative(s.resolve("")));
    }

    @Test
    void ドットドットで外へ出られない() throws IOException {
        SafePath s = safe();
        ApiException e = assertThrows(ApiException.class, () -> s.resolve("../secret.txt"));
        assertEquals(400, e.status());
        assertThrows(ApiException.class, () -> s.resolve("world/../../x"));
    }

    @Test
    void 中で戻るだけなら通る() throws IOException {
        SafePath s = safe();
        assertEquals("config", s.relative(s.resolve("world/../config")));
    }

    @Test
    void トークンファイルは拒否() throws IOException {
        SafePath s = safe();
        ApiException e = assertThrows(ApiException.class, () -> s.resolve("config/mc_rest_api/tokens.json"));
        assertEquals(403, e.status());
    }

    @Test
    void 拒否対象を含むディレクトリを判定できる() throws IOException {
        SafePath s = safe();
        assertTrue(s.containsDenied(s.resolve("config")));
        assertFalse(s.containsDenied(s.resolve("world")));
    }

    @Test
    void 外を指すシンボリックリンクは辿れない() throws IOException {
        SafePath s = safe();
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path link = s.root().resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows で権限が無いときは試せない
            assumeTrue(false, "シンボリックリンクを作れない環境");
        }
        assertThrows(ApiException.class, () -> s.resolve("link/file.txt"));
    }

    @Test
    void ヌル文字は拒否() throws IOException {
        SafePath s = safe();
        assertThrows(ApiException.class, () -> s.resolve("a\0b"));
    }
}
