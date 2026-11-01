package jp.mc.restapi.util;

import jp.mc.restapi.http.ApiException;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * ゲームの状態に触る処理をサーバースレッドで実行し、結果を待つ。
 */
public final class ServerExec {
    private final MinecraftServer server;
    private final long timeoutSeconds;

    public ServerExec(MinecraftServer server, long timeoutSeconds) {
        this.server = server;
        this.timeoutSeconds = timeoutSeconds;
    }

    public MinecraftServer server() {
        return server;
    }

    public <T> T call(Supplier<T> task) {
        return call(task, timeoutSeconds);
    }

    /** 保存など時間のかかる処理は待ち時間を指定する */
    public <T> T call(Supplier<T> task, long timeout) {
        if (server.isSameThread()) {
            return task.get();
        }
        CompletableFuture<T> future = server.submit(task);
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ApiException(504, "server_timeout", "サーバースレッドが " + timeout + " 秒以内に応答しませんでした");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "interrupted", "処理が中断されました");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    public void run(Runnable task) {
        call(() -> {
            task.run();
            return null;
        });
    }
}
