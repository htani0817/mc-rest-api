package jp.mc.restapi.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLogsTest {
    @TempDir
    Path logs;

    @Test
    void サーバーログは日付単位で7日分だけ残す() throws IOException {
        // 10 日分。同じ日の -1, -2 はまとめて 1 世代
        for (int d = 1; d <= 10; d++) {
            String day = "2026-10-" + String.format("%02d", d);
            Files.writeString(logs.resolve(day + "-1.log.gz"), "x");
            Files.writeString(logs.resolve(day + "-2.log.zip"), "x");
        }
        Files.writeString(logs.resolve("latest.log"), "now");
        Files.writeString(logs.resolve("debug-1.log.gz"), "debug");
        Files.writeString(logs.resolve("debug.log"), "debug");

        List<String> deleted = ServerLogs.prune(logs, 7);
        assertTrue(deleted.contains("2026-10-01-1.log.gz"));

        assertFalse(Files.exists(logs.resolve("2026-10-03-1.log.gz")));
        assertFalse(Files.exists(logs.resolve("2026-10-03-2.log.zip")));
        assertTrue(Files.exists(logs.resolve("2026-10-04-1.log.gz")));
        assertTrue(Files.exists(logs.resolve("2026-10-04-2.log.zip")));
        assertTrue(Files.exists(logs.resolve("2026-10-10-1.log.gz")));
        // 書き込み中のログと NeoForge のデバッグログは消さない
        assertTrue(Files.exists(logs.resolve("latest.log")));
        assertTrue(Files.exists(logs.resolve("debug-1.log.gz")));
        assertTrue(Files.exists(logs.resolve("debug.log")));
    }
}
