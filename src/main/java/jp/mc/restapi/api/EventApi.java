package jp.mc.restapi.api;

import jp.mc.restapi.capture.EventHub;
import jp.mc.restapi.config.TokenStore;
import jp.mc.restapi.http.ApiException;
import jp.mc.restapi.http.Json;
import jp.mc.restapi.http.Request;
import jp.mc.restapi.http.Response;
import jp.mc.restapi.http.Router;
import jp.mc.restapi.http.Scope;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * SSE（Server-Sent Events）でログ・チャット・参加/退出などを流す。
 */
public final class EventApi {
    private static final long KEEP_ALIVE_SECONDS = 15;

    private final EventHub hub;
    private final TokenStore tokens;
    private final int maxClients;

    public EventApi(EventHub hub, TokenStore tokens, int maxClients) {
        this.hub = hub;
        this.tokens = tokens;
        this.maxClients = maxClients;
    }

    public void register(Router r) {
        r.post("/events/ticket", Scope.READ, req -> Json.obj(
                "ticket", tokens.issueTicket(req.token()),
                "expiresInSeconds", TokenStore.ticketTtlSeconds()));
        r.get("/events", Scope.READ, this::stream);
    }

    private Object stream(Request req) {
        Set<String> types = parseTypes(req.query("types"));
        // ログは admin だけ（コマンドの中身や IP が出るため）
        if (types.contains("log") && !req.token().scope().covers(Scope.ADMIN)) {
            if (req.query("types") != null) {
                throw new ApiException(403, "forbidden", "log イベントには admin 権限が必要です");
            }
            types.remove("log");
        }
        if (hub.subscriberCount() >= maxClients) {
            throw new ApiException(503, "too_many_clients", "SSE の同時接続数が上限（" + maxClients + "）です");
        }
        return (Response) exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0);
            try (EventHub.Subscription sub = hub.subscribe(types); OutputStream out = exchange.getResponseBody()) {
                write(out, "retry: 3000\n: connected types=" + String.join(",", types) + "\n\n");
                while (!Thread.currentThread().isInterrupted()) {
                    EventHub.Event e = sub.poll(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
                    if (sub.overflowed()) {
                        write(out, "event: overflow\ndata: {}\n\n");
                        return;
                    }
                    if (e == null) {
                        write(out, ": keep-alive\n\n");
                    } else {
                        write(out, "id: " + e.id() + "\nevent: " + e.type() + "\ndata: " + e.json() + "\n\n");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // クライアントが切断した
            }
        };
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    static Set<String> parseTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashSet<>(EventHub.TYPES);
        }
        Set<String> set = new LinkedHashSet<>();
        for (String t : Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            if (!EventHub.TYPES.contains(t)) {
                throw ApiException.badRequest("不明なイベント種別: " + t + "（使えるもの: " + String.join(",", EventHub.TYPES) + "）");
            }
            set.add(t);
        }
        return set;
    }
}
