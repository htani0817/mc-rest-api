package jp.mc.restapi.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 1 日 1 ファイルで書き、古いものを消して keep 日分だけ残すログ。
 * ファイル名は &lt;kind&gt;-YYYY-MM-DD.log。
 */
public final class DailyLog implements AutoCloseable {
    private final Path dir;
    private final String kind;
    private final int keep;
    private final Pattern name;
    private LocalDate current;
    private BufferedWriter writer;

    public DailyLog(Path dir, String kind, int keep) throws IOException {
        this.dir = dir;
        this.kind = kind;
        this.keep = keep;
        this.name = Pattern.compile("^" + Pattern.quote(kind) + "-(\\d{4}-\\d{2}-\\d{2})\\.log$");
        Files.createDirectories(dir);
        prune();
    }

    public String kind() {
        return kind;
    }

    public Path dir() {
        return dir;
    }

    /** 1 行書く。日付が変わっていたら新しいファイルにして、古いものを消す */
    public synchronized void write(String line) {
        try {
            LocalDate today = LocalDate.now();
            if (writer == null || !today.equals(current)) {
                if (writer != null) {
                    writer.close();
                }
                writer = Files.newBufferedWriter(dir.resolve(kind + "-" + today + ".log"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                current = today;
                prune();
            }
            writer.write(line.replace('\n', ' ').replace('\r', ' '));
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
            // 書けなくても本来の処理は止めない
        }
    }

    /** 日付の新しい順にファイルを返す */
    public List<Path> files() {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> name.matcher(p.getFileName().toString()).matches()).forEach(out::add);
        } catch (IOException ignored) {
            return out;
        }
        out.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return out;
    }

    /** keep 日分より古いファイルを消す */
    public synchronized void prune() {
        List<Path> files = files();
        for (int i = keep; i < files.size(); i++) {
            try {
                Files.deleteIfExists(files.get(i));
            } catch (IOException ignored) {
                // 次の機会に消す
            }
        }
    }

    /** 新しいファイルから順に、末尾 max 行を古い順で返す（再起動後の履歴の読み戻し用） */
    public List<String> tail(int max) {
        List<String> out = new ArrayList<>();
        for (Path f : files()) {
            try {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = lines.size() - 1; i >= 0 && out.size() < max; i--) {
                    if (!lines.get(i).isBlank()) {
                        out.add(lines.get(i));
                    }
                }
            } catch (IOException ignored) {
                // 読めないファイルは飛ばす
            }
            if (out.size() >= max) {
                break;
            }
        }
        Collections.reverse(out);
        return out;
    }

    /** ファイル名から日付を取り出す（合わなければ null） */
    public String dateOf(Path file) {
        Matcher m = name.matcher(file.getFileName().toString());
        return m.matches() ? m.group(1) : null;
    }

    @Override
    public synchronized void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}
