package jp.mc.restapi.capture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jp.mc.restapi.http.Json;
import jp.mc.restapi.util.DailyLog;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * チャットの履歴。プレイヤーの発言と API からの送信の両方を入れる。
 * メモリに直近の分を持ち、ファイル（1 日 1 ファイル）にも書く。起動時はファイルから読み戻す。
 */
public final class ChatHistory {
    public record Message(long time, String sender, String uuid, String message, String source) {
        public Map<String, Object> toMap() {
            // ファイルを読んだときに分かりやすいよう、サーバーの時間帯（+09:00 など）つきで書く
            return Json.obj("time", OffsetDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault()).toString(), "sender", sender, "uuid", uuid,
                    "message", message, "source", source);
        }
    }

    private final RingBuffer<Message> buffer;
    private final EventHub hub;
    private final DailyLog file;

    public ChatHistory(int capacity, EventHub hub, DailyLog file) {
        this.buffer = new RingBuffer<>(capacity);
        this.hub = hub;
        this.file = file;
        restore(capacity);
    }

    /** source は "player"（ゲーム内の発言）か "api"（API から送信） */
    public void add(String sender, String uuid, String message, String source) {
        Message m = new Message(System.currentTimeMillis(), sender, uuid, message, source);
        buffer.add(m);
        Map<String, Object> map = m.toMap();
        if (file != null) {
            file.write(Json.GSON.toJson(map));
        }
        hub.publish("chat", map);
    }

    public RingBuffer<Message> buffer() {
        return buffer;
    }

    private void restore(int capacity) {
        if (file == null) {
            return;
        }
        for (String line : file.tail(capacity)) {
            try {
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                buffer.add(new Message(
                        OffsetDateTime.parse(o.get("time").getAsString()).toInstant().toEpochMilli(),
                        Json.str(o, "sender", ""),
                        Json.str(o, "uuid", null),
                        Json.str(o, "message", ""),
                        Json.str(o, "source", "player")));
            } catch (RuntimeException ignored) {
                // 壊れた行は飛ばす
            }
        }
    }
}
