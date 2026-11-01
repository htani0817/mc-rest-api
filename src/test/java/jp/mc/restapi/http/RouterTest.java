package jp.mc.restapi.http;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouterTest {
    private static Router sample() {
        Router r = new Router();
        r.get("/players", Scope.READ, req -> "list");
        r.get("/players/known", Scope.READ, req -> "known");
        r.get("/players/{player}", Scope.READ, req -> "one");
        r.post("/players/{player}/kick", Scope.WRITE, req -> "kick");
        return r;
    }

    @Test
    void 固定セグメントが変数より優先される() {
        Router.Match m = sample().match("GET", "/players/known");
        assertEquals("/players/known", m.route().template());
    }

    @Test
    void パス変数を取り出せる() {
        Router.Match m = sample().match("POST", "/players/TANI0817/kick");
        assertEquals(Map.of("player", "TANI0817"), m.params());
        assertEquals(Scope.WRITE, m.route().scope());
    }

    @Test
    void 末尾スラッシュは無視する() {
        assertEquals("/players", sample().match("GET", "/players/").route().template());
    }

    @Test
    void メソッド違いは405() {
        ApiException e = assertThrows(ApiException.class, () -> sample().match("DELETE", "/players"));
        assertEquals(405, e.status());
    }

    @Test
    void 無いパスは404() {
        ApiException e = assertThrows(ApiException.class, () -> sample().match("GET", "/nothing"));
        assertEquals(404, e.status());
    }

    @Test
    void 重複登録は例外() {
        Router r = sample();
        assertThrows(IllegalStateException.class, () -> r.get("/players", Scope.READ, req -> null));
    }

    @Test
    void 権限の包含関係() {
        assertEquals(true, Scope.ADMIN.covers(Scope.WRITE));
        assertEquals(true, Scope.WRITE.covers(Scope.READ));
        assertEquals(false, Scope.READ.covers(Scope.WRITE));
        assertEquals(false, Scope.WRITE.covers(Scope.ADMIN));
    }

    @Test
    void クエリを分解できる() {
        Map<String, String> q = Request.parseQuery("path=world%2Fregion&lines=10&flag&plus=a+b");
        assertEquals("world/region", q.get("path"));
        assertEquals("10", q.get("lines"));
        assertEquals("", q.get("flag"));
        assertEquals("a+b", q.get("plus"));
    }
}
