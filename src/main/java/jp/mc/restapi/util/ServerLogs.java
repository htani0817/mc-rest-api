package jp.mc.restapi.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Minecraft（log4j）の logs/ にあるサーバーログの扱い。
 */
public final class ServerLogs {
    /** 日付つきのログ: 2026-11-01-1.log.gz など。同じ日の -1, -2 … はまとめて 1 世代 */
    public static final Pattern DATED = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})-\\d+\\.log(\\.gz|\\.zip)?$");
    /** 一覧に出すログ（debug は NeoForge が本数を決めているので消さない） */
    public static final Pattern LISTED = Pattern.compile(
            "^(latest\\.log|debug\\.log|debug-\\d+\\.log(\\.gz|\\.zip)?|\\d{4}-\\d{2}-\\d{2}-\\d+\\.log(\\.gz|\\.zip)?)$");

    private ServerLogs() {
    }

    /** 日付つきのログを、日付の新しいほうから keep 日分だけ残す。消したファイル名を返す */
    public static List<String> prune(Path dir, int keep) {
        List<Path> dated = new ArrayList<>();
        TreeSet<String> dates = new TreeSet<>(Comparator.reverseOrder());
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> {
                Matcher m = DATED.matcher(p.getFileName().toString());
                if (m.matches()) {
                    dated.add(p);
                    dates.add(m.group(1));
                }
            });
        } catch (IOException e) {
            return List.of();
        }
        List<String> keepDates = dates.stream().limit(keep).toList();
        List<String> deleted = new ArrayList<>();
        for (Path p : dated) {
            Matcher m = DATED.matcher(p.getFileName().toString());
            if (m.matches() && !keepDates.contains(m.group(1))) {
                try {
                    Files.deleteIfExists(p);
                    deleted.add(p.getFileName().toString());
                } catch (IOException ignored) {
                    // 次の機会に消す
                }
            }
        }
        return deleted;
    }

    /** 日付つきなら日付、そうでなければ null */
    public static String dateOf(String name) {
        Matcher m = DATED.matcher(name);
        return m.matches() ? m.group(1) : null;
    }
}
