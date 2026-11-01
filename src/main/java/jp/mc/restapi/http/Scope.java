package jp.mc.restapi.http;

import java.util.Locale;

/**
 * トークンの権限。ADMIN は WRITE と READ を、WRITE は READ を含む。
 */
public enum Scope {
    /** 認証不要 */
    NONE,
    READ,
    WRITE,
    ADMIN;

    public boolean covers(Scope required) {
        return this.ordinal() >= required.ordinal();
    }

    public static Scope parse(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "read" -> READ;
            case "write" -> WRITE;
            case "admin" -> ADMIN;
            default -> throw new IllegalArgumentException("不明な scope: " + value);
        };
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
