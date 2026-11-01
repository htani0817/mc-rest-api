package jp.mc.restapi.config;

import jp.mc.restapi.capture.RingBuffer;
import jp.mc.restapi.http.AuthLimiter;
import jp.mc.restapi.http.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenStoreTest {
    @TempDir
    Path tmp;

    @Test
    void 初回は3本生成し二回目は読み込む() throws IOException {
        Path file = tmp.resolve("tokens.json");
        TokenStore.LoadResult first = TokenStore.loadOrCreate(file);
        assertTrue(first.created());
        TokenStore.LoadResult second = TokenStore.loadOrCreate(file);
        assertFalse(second.created());
    }

    @Test
    void トークンを照合できる() {
        TokenStore store = new TokenStore(List.of(
                new TokenStore.Token("admin", "a".repeat(32), Scope.ADMIN),
                new TokenStore.Token("read", "b".repeat(32), Scope.READ)));
        assertEquals("read", store.find("b".repeat(32)).name());
        assertNull(store.find("c".repeat(32)));
        assertNull(store.find(""));
        assertNull(store.find(null));
    }

    @Test
    void チケットは一回だけ使える() {
        TokenStore.Token t = new TokenStore.Token("read", "b".repeat(32), Scope.READ);
        TokenStore store = new TokenStore(List.of(t));
        String ticket = store.issueTicket(t);
        assertNotNull(store.redeemTicket(ticket));
        assertNull(store.redeemTicket(ticket));
        assertNull(store.redeemTicket("unknown"));
    }

    @Test
    void 生成トークンは64桁の16進() {
        String v = TokenStore.generate();
        assertEquals(64, v.length());
        assertTrue(v.matches("[0-9a-f]+"));
    }

    @Test
    void 認証失敗が続くと締め出す() {
        AuthLimiter limiter = new AuthLimiter(3, 60);
        long now = 1_000_000L;
        limiter.recordFailure("1.2.3.4", now);
        limiter.recordFailure("1.2.3.4", now);
        assertFalse(limiter.isBlocked("1.2.3.4", now));
        limiter.recordFailure("1.2.3.4", now);
        assertTrue(limiter.isBlocked("1.2.3.4", now + 1));
        assertFalse(limiter.isBlocked("1.2.3.4", now + 61_000));
        assertFalse(limiter.isBlocked("5.6.7.8", now));
    }

    @Test
    void リングバッファは古いものから捨てる() {
        RingBuffer<Integer> b = new RingBuffer<>(3);
        for (int i = 1; i <= 5; i++) {
            b.add(i);
        }
        assertEquals(List.of(3, 4, 5), b.last(10));
        assertEquals(List.of(4, 5), b.last(2));
        assertEquals(List.of(), b.last(0));
    }
}
