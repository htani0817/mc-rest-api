package jp.mc.restapi.capture;

import jp.mc.restapi.http.Json;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.time.Instant;
import java.util.Map;

/**
 * Log4j2 のルートロガーに付けて、サーバーログを保持・配信する。
 */
public final class LogCapture extends AbstractAppender {
    public record Line(long time, String level, String thread, String logger, String message) {
        public Map<String, Object> toMap() {
            return Json.obj("time", Instant.ofEpochMilli(time).toString(), "level", level,
                    "thread", thread, "logger", logger, "message", message);
        }
    }

    private final RingBuffer<Line> buffer;
    private volatile EventHub hub;

    private LogCapture(int capacity) {
        super("McRestApiLogCapture", null, null, true, Property.EMPTY_ARRAY);
        this.buffer = new RingBuffer<>(capacity);
    }

    public static LogCapture install(int capacity) {
        LogCapture appender = new LogCapture(capacity);
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        LoggerConfig root = config.getRootLogger();
        root.addAppender(appender, null, null);
        ctx.updateLoggers();
        return appender;
    }

    public void uninstall() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().getRootLogger().removeAppender(getName());
        ctx.updateLoggers();
        stop();
    }

    public void hub(EventHub hub) {
        this.hub = hub;
    }

    public RingBuffer<Line> buffer() {
        return buffer;
    }

    @Override
    public void append(LogEvent event) {
        // ここでログを出すと再帰するので、何も出力しない
        String msg = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        if (event.getThrown() != null) {
            msg = msg + " | " + event.getThrown();
        }
        Line line = new Line(event.getTimeMillis(), event.getLevel().name(), event.getThreadName(),
                event.getLoggerName(), msg);
        buffer.add(line);
        EventHub h = hub;
        if (h != null) {
            h.publish("log", line.toMap());
        }
    }
}
