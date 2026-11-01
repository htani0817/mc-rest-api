package jp.mc.restapi.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * JSON 以外（ファイル、SSE、201 など）を返したいときのハンドラーの戻り値。
 */
public interface Response {
    /** ヘッダーと本文を書き出す。exchange は呼び出し側が閉じる */
    void write(HttpExchange exchange) throws IOException;

    /** data を包んで任意のステータスで返す（201 Created・202 Accepted 用） */
    static Response json(int status, Object data) {
        return exchange -> HttpServerHost.writeJson(exchange, status, Json.obj("data", data));
    }
}
