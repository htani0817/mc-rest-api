package jp.mc.restapi.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * メソッドとパス（{name} 形式の変数つき）でハンドラーを引く。
 */
public final class Router {
    @FunctionalInterface
    public interface Handler {
        /** 戻り値は JSON の data に入る。Response を返すとそのまま書き出す */
        Object handle(Request req) throws Exception;
    }

    public record Route(String method, String template, String[] segments, Scope scope, Handler handler) {
    }

    public record Match(Route route, Map<String, String> params) {
    }

    private final List<Route> routes = new ArrayList<>();

    public void add(String method, String template, Scope scope, Handler handler) {
        String[] segments = split(template);
        for (Route r : routes) {
            if (r.method.equals(method) && r.template.equals(template)) {
                throw new IllegalStateException("ルートが重複: " + method + " " + template);
            }
        }
        routes.add(new Route(method, template, segments, scope, handler));
    }

    public void get(String template, Scope scope, Handler handler) {
        add("GET", template, scope, handler);
    }

    public void post(String template, Scope scope, Handler handler) {
        add("POST", template, scope, handler);
    }

    public void put(String template, Scope scope, Handler handler) {
        add("PUT", template, scope, handler);
    }

    public void patch(String template, Scope scope, Handler handler) {
        add("PATCH", template, scope, handler);
    }

    public void delete(String template, Scope scope, Handler handler) {
        add("DELETE", template, scope, handler);
    }

    public List<Route> routes() {
        return List.copyOf(routes);
    }

    /**
     * 一致するルートを返す。パスは合うがメソッドが違うときは 405、どれにも合わなければ 404。
     * 固定セグメントの多いルートを優先する（/players/known が /players/{player} より先）。
     */
    public Match match(String method, String path) {
        String[] parts = split(path);
        Match best = null;
        int bestScore = -1;
        Set<String> allowed = new TreeSet<>();
        for (Route r : routes) {
            Map<String, String> params = matchSegments(r.segments, parts);
            if (params == null) {
                continue;
            }
            if (!r.method.equals(method)) {
                allowed.add(r.method);
                continue;
            }
            int score = literalCount(r.segments);
            if (score > bestScore) {
                best = new Match(r, params);
                bestScore = score;
            }
        }
        if (best != null) {
            return best;
        }
        if (!allowed.isEmpty()) {
            throw new ApiException(405, "method_not_allowed", "許可されているメソッド: " + String.join(", ", allowed));
        }
        throw ApiException.notFound("not_found", "エンドポイントがありません: " + path);
    }

    private static Map<String, String> matchSegments(String[] template, String[] parts) {
        if (template.length != parts.length) {
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < template.length; i++) {
            String t = template[i];
            if (t.startsWith("{") && t.endsWith("}")) {
                if (parts[i].isEmpty()) {
                    return null;
                }
                params.put(t.substring(1, t.length() - 1), parts[i]);
            } else if (!t.equals(parts[i])) {
                return null;
            }
        }
        return params;
    }

    private static int literalCount(String[] segments) {
        int n = 0;
        for (String s : segments) {
            if (!s.startsWith("{")) {
                n++;
            }
        }
        return n;
    }

    private static String[] split(String path) {
        String p = path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? new String[0] : p.split("/", -1);
    }
}
