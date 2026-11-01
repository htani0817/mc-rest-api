package jp.mc.restapi.http;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 認証失敗の多い IP を一時的に締め出す。
 */
public final class AuthLimiter {
    private static final long WINDOW_MILLIS = 60_000L;

    private record State(int failures, long windowStart, long blockedUntil) {
    }

    private final int limit;
    private final long blockMillis;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public AuthLimiter(int limit, long blockSeconds) {
        this.limit = limit;
        this.blockMillis = blockSeconds * 1000L;
    }

    public boolean isBlocked(String ip, long now) {
        State s = states.get(ip);
        return s != null && s.blockedUntil > now;
    }

    public void recordFailure(String ip, long now) {
        states.compute(ip, (k, s) -> {
            if (s == null || now - s.windowStart > WINDOW_MILLIS) {
                s = new State(0, now, 0);
            }
            int failures = s.failures + 1;
            long blockedUntil = failures >= limit ? now + blockMillis : s.blockedUntil;
            return new State(failures, s.windowStart, blockedUntil);
        });
        if (states.size() > 10_000) {
            states.entrySet().removeIf(e -> e.getValue().blockedUntil < now && now - e.getValue().windowStart > WINDOW_MILLIS);
        }
    }

    public void recordSuccess(String ip) {
        State s = states.get(ip);
        if (s != null && s.blockedUntil == 0) {
            states.remove(ip);
        }
    }
}
