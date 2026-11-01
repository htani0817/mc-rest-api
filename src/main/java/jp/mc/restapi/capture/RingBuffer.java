package jp.mc.restapi.capture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 直近 N 件だけ持つバッファ。ログとチャット履歴で使う。
 */
public final class RingBuffer<T> {
    private final int capacity;
    private final ArrayDeque<T> items;

    public RingBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.items = new ArrayDeque<>(this.capacity);
    }

    public synchronized void add(T item) {
        if (items.size() == capacity) {
            items.removeFirst();
        }
        items.addLast(item);
    }

    /** 新しいほうから最大 n 件を、古い順で返す */
    public synchronized List<T> last(int n) {
        int count = Math.max(0, Math.min(n, items.size()));
        List<T> all = new ArrayList<>(items);
        return all.subList(all.size() - count, all.size());
    }

    public synchronized int size() {
        return items.size();
    }
}
