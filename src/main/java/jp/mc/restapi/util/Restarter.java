package jp.mc.restapi.util;

import jp.mc.restapi.McRestApi;
import jp.mc.restapi.http.Json;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dedicated.DedicatedServer;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 再起動。予告してから普通に停止し、止まりきったら決まった終了コードで Java を終わらせる。
 * 立ち上げ直すのは起動した側（systemd の Restart=on-failure など）。
 * <p>
 * 停止イベントの中（サーバースレッド）で System.exit を呼ぶと、Minecraft の終了フックが
 * サーバースレッドの終わりを待つので固まる。そのため別スレッドでサーバースレッドの終わりを待ってから終わらせる。
 * サーバースレッドへの依頼は待たずに投げる（ロックを持ったまま待つと、状態の取得と互いに待ち合って固まるため）。
 */
public final class Restarter {
    /** 予告する残り秒数 */
    private static final int[] ANNOUNCE_AT = {300, 120, 60, 30, 10, 5, 4, 3, 2, 1};

    private final DedicatedServer server;
    private final int exitCode;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mc-rest-api-restart-timer");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> ticking;
    private Instant at;
    private String message;
    /** 停止まで進んだら true。停止イベントでこれを見て終了コードを変える */
    private volatile boolean exitForRestart;

    public Restarter(DedicatedServer server, int exitCode) {
        this.server = server;
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }

    /** 予告中なら再起動する時刻、なければ null */
    public synchronized Instant pendingAt() {
        return ticking == null ? null : at;
    }

    /** 予告中なら再起動する時刻の文字列、なければ null */
    public String pendingAtText() {
        Instant p = pendingAt();
        return p == null ? null : p.toString();
    }

    /** delaySeconds 秒後に再起動する。予告中ならそのまま false */
    public synchronized boolean schedule(int delaySeconds, String message) {
        if (ticking != null || exitForRestart) {
            return false;
        }
        this.at = Instant.now().plusSeconds(delaySeconds);
        this.message = message;
        if (delaySeconds > 0) {
            broadcast(delaySeconds);
        }
        ticking = timer.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        if (delaySeconds == 0) {
            tick();
        }
        return true;
    }

    /** 予告中の再起動を取り消す。予告中でなければ false */
    public synchronized boolean cancel() {
        if (ticking == null) {
            return false;
        }
        ticking.cancel(false);
        ticking = null;
        if (!exitForRestart) {
            server.execute(() -> server.getPlayerList().broadcastSystemMessage(Component.literal("再起動は取りやめになりました"), false));
        }
        return true;
    }

    private synchronized void tick() {
        if (ticking == null || exitForRestart) {
            return;
        }
        long left = Math.max(0, (at.toEpochMilli() - System.currentTimeMillis() + 500) / 1000);
        if (left > 0) {
            for (int s : ANNOUNCE_AT) {
                if (s == left) {
                    broadcast(left);
                }
            }
            return;
        }
        ticking.cancel(false);
        ticking = null;
        exitForRestart = true;
        McRestApi.LOGGER.info("[MC REST API] 再起動のためサーバーを停止します（終了コード {}）", exitCode);
        server.execute(() -> server.halt(false));
    }

    private void broadcast(long seconds) {
        String text = "サーバーを " + seconds + " 秒後に再起動します" + (message == null || message.isBlank() ? "" : "（" + message + "）");
        server.execute(() -> server.getPlayerList().broadcastSystemMessage(Component.literal(text), false));
    }

    /** 停止が始まったら予告を止める（停止ボタンが先に押されたときは再起動しない） */
    public synchronized void onStopping() {
        if (ticking != null) {
            ticking.cancel(false);
            ticking = null;
        }
    }

    /** 停止イベント（サーバースレッド）から呼ぶ。再起動のための停止なら、止まりきったあとで Java を終わらせる */
    public void onStopped() {
        timer.shutdownNow();
        if (!exitForRestart) {
            return;
        }
        Thread serverThread = Thread.currentThread();
        // デーモンにしないことで、このスレッドが終わらせるまで Java が先に普通終了しない
        Thread t = new Thread(() -> {
            try {
                serverThread.join();
            } catch (InterruptedException ignored) {
                // そのまま終わらせる
            }
            McRestApi.LOGGER.info("[MC REST API] 停止しました。終了コード {} で終わります（起動した側が立ち上げ直します）", exitCode);
            System.exit(exitCode);
        }, "mc-rest-api-restart");
        t.setDaemon(false);
        t.start();
    }

    public Map<String, Object> state() {
        String p = pendingAtText();
        return Json.obj(
                "pending", p != null,
                "at", p,
                "exitCode", exitCode);
    }
}
