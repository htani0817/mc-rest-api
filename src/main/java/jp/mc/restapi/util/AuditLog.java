package jp.mc.restapi.util;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 書き込み系リクエストの記録。logs/mc-rest-api/audit/audit-YYYY-MM-DD.log に 1 日 1 ファイルで書く。
 */
public final class AuditLog {
    private final DailyLog log;

    public AuditLog(DailyLog log) {
        this.log = log;
    }

    public void write(String ip, String tokenName, String method, String path, int status, String note) {
        if (log == null) {
            return;
        }
        StringBuilder sb = new StringBuilder()
                .append(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .append(" ip=").append(ip)
                .append(" token=").append(tokenName == null ? "-" : tokenName)
                .append(' ').append(method).append(' ').append(path)
                .append(" status=").append(status);
        if (note != null && !note.isEmpty()) {
            sb.append(" note=").append(note);
        }
        log.write(sb.toString());
    }
}
