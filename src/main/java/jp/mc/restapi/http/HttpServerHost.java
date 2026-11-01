package jp.mc.restapi.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jp.mc.restapi.McRestApi;
import jp.mc.restapi.config.ApiConfig;
import jp.mc.restapi.config.TokenStore;
import jp.mc.restapi.util.AuditLog;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JDK 標準の HttpServer で /api/v1 を待ち受ける。1 リクエスト 1 仮想スレッド。
 */
public final class HttpServerHost {
    public static final String PREFIX = "/api/v1";

    /** Authorization ヘッダーを付けられない用途（EventSource・ダウンロードリンク）でチケットを受け付けるルート */
    private static final Set<String> TICKET_ROUTES = Set.of("/events", "/files/content", "/backups/{name}", "/history/{kind}/{name}");

    private final ApiConfig config;
    private final TokenStore tokens;
    private final Router router;
    private final AuditLog audit;
    private final AuthLimiter limiter;
    private HttpServer server;
    private ExecutorService executor;

    public HttpServerHost(ApiConfig config, TokenStore tokens, Router router, AuditLog audit) {
        this.config = config;
        this.tokens = tokens;
        this.router = router;
        this.audit = audit;
        this.limiter = new AuthLimiter(config.authFailureLimit, config.authBlockSeconds);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 64);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void handle(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String rawPath = exchange.getRequestURI().getRawPath();
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        TokenStore.Token token = null;
        int status = 500;
        String note = null;
        // try-with-resources の catch は close の後に走るので、exchange は finally で閉じる
        try {
            applyCors(exchange);
            if (method.equals("OPTIONS")) {
                status = 204;
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!rawPath.equals(PREFIX) && !rawPath.startsWith(PREFIX + "/")) {
                throw ApiException.notFound("not_found", "API は " + PREFIX + " の下にあります");
            }
            Router.Match match = router.match(method, rawPath.substring(PREFIX.length()));
            Request req = new Request(exchange, match.params(), config.maxJsonBytes);

            Scope required = match.route().scope();
            if (required != Scope.NONE) {
                long now = System.currentTimeMillis();
                if (limiter.isBlocked(ip, now)) {
                    throw new ApiException(429, "too_many_auth_failures", "認証の失敗が多いため一時的に拒否しています");
                }
                token = authenticate(exchange, req, match.route());
                if (token == null) {
                    limiter.recordFailure(ip, now);
                    throw new ApiException(401, "unauthorized", "Authorization: Bearer <token> が必要です");
                }
                limiter.recordSuccess(ip);
                if (!token.scope().covers(required)) {
                    throw new ApiException(403, "forbidden", "この操作には " + required.id() + " 権限が必要です");
                }
                req.token(token);
            }

            Object result = match.route().handler().handle(req);
            if (result instanceof Response r) {
                status = 200;
                r.write(exchange);
                status = exchange.getResponseCode();
            } else {
                status = 200;
                writeJson(exchange, 200, Json.obj("data", result));
            }
        } catch (ApiException e) {
            status = e.status();
            note = e.code();
            safeWriteError(exchange, e.status(), e.code(), e.getMessage());
        } catch (Throwable t) {
            status = 500;
            note = t.getClass().getSimpleName();
            McRestApi.LOGGER.error("API の処理中にエラー: {} {}", method, rawPath, t);
            safeWriteError(exchange, 500, "internal_error", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            exchange.close();
            if (!method.equals("GET") && !method.equals("OPTIONS") && !method.equals("HEAD")) {
                audit.write(ip, token == null ? null : token.name(), method, rawPath, status, note);
            }
        }
    }

    private TokenStore.Token authenticate(HttpExchange exchange, Request req, Router.Route route) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return tokens.find(auth.substring(7).trim());
        }
        if (route.method().equals("GET") && TICKET_ROUTES.contains(route.template())) {
            return tokens.redeemTicket(req.query("ticket"));
        }
        return null;
    }

    private void applyCors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || !config.corsAllowedOrigins.contains(origin)) {
            return;
        }
        Headers h = exchange.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", origin);
        h.set("Vary", "Origin");
        h.set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        h.set("Access-Control-Max-Age", "600");
    }

    private static void safeWriteError(HttpExchange exchange, int status, String code, String message) {
        try {
            if (exchange.getResponseCode() == -1) {
                writeJson(exchange, status, Json.obj("error", Json.obj("code", code, "message", message)));
            }
        } catch (IOException ignored) {
            // すでに切断されている
        }
    }

    public static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
