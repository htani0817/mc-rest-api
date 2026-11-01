package jp.mc.restapi.capture;

import jp.mc.restapi.http.Json;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 接続へイベントを配る。種類で絞り込める。
 */
public final class EventHub {
    public static final Set<String> TYPES = Set.of(
            "log", "chat", "join", "leave", "death", "advancement", "command", "server");

    public record Event(long id, String type, String json) {
    }

    /** 1 接続分の受け口。溢れたら切断扱いにする */
    public final class Subscription implements AutoCloseable {
        private final Set<String> types;
        private final BlockingQueue<Event> queue = new ArrayBlockingQueue<>(2000);
        private volatile boolean overflowed;

        private Subscription(Set<String> types) {
            this.types = types;
        }

        void offer(Event e) {
            if (types.contains(e.type) && !queue.offer(e)) {
                overflowed = true;
            }
        }

        public Event poll(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }

        public boolean overflowed() {
            return overflowed;
        }

        @Override
        public void close() {
            subscribers.remove(this);
        }
    }

    private final Set<Subscription> subscribers = new CopyOnWriteArraySet<>();
    private final AtomicLong seq = new AtomicLong();

    public Subscription subscribe(Set<String> types) {
        Subscription s = new Subscription(types);
        subscribers.add(s);
        return s;
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    public void publish(String type, Map<String, Object> data) {
        if (subscribers.isEmpty()) {
            return;
        }
        Event e = new Event(seq.incrementAndGet(), type, Json.GSON.toJson(data));
        for (Subscription s : subscribers) {
            s.offer(e);
        }
    }
}
