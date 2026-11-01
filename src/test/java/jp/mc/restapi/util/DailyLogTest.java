package jp.mc.restapi.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyLogTest {
    @TempDir
    Path tmp;

    @Test
    void 今日のファイルに書く() throws IOException {
        DailyLog log = new DailyLog(tmp, "chat", 7);
        log.write("a");
        log.write("b\nc");
        log.close();
        Path today = tmp.resolve("chat-" + LocalDate.now() + ".log");
        assertEquals(List.of("a", "b c"), Files.readAllLines(today));
    }

    @Test
    void 古いものは7日分だけ残す() throws IOException {
        for (int d = 1; d <= 10; d++) {
            Files.writeString(tmp.resolve("chat-2026-10-" + String.format("%02d", d) + ".log"), "x");
        }
        Files.writeString(tmp.resolve("other.txt"), "keep");
        DailyLog log = new DailyLog(tmp, "chat", 7);
        List<Path> files = log.files();
        assertEquals(7, files.size());
        assertEquals("chat-2026-10-10.log", files.getFirst().getFileName().toString());
        assertFalse(Files.exists(tmp.resolve("chat-2026-10-03.log")));
        assertTrue(Files.exists(tmp.resolve("chat-2026-10-04.log")));
        // 関係ないファイルは消さない
        assertTrue(Files.exists(tmp.resolve("other.txt")));
    }

    @Test
    void 末尾から読み戻せる() throws IOException {
        Files.writeString(tmp.resolve("chat-2026-10-01.log"), "1\n2\n3\n");
        Files.writeString(tmp.resolve("chat-2026-10-02.log"), "4\n5\n");
        DailyLog log = new DailyLog(tmp, "chat", 7);
        assertEquals(List.of("3", "4", "5"), log.tail(3));
        assertEquals(List.of("1", "2", "3", "4", "5"), log.tail(100));
    }

    @Test
    void 日付を取り出す() throws IOException {
        DailyLog log = new DailyLog(tmp, "audit", 7);
        assertEquals("2026-11-01", log.dateOf(tmp.resolve("audit-2026-11-01.log")));
        assertNull(log.dateOf(tmp.resolve("chat-2026-11-01.log")));
        assertNull(log.dateOf(tmp.resolve("audit-2026-11-01.log.bak")));
    }
}
