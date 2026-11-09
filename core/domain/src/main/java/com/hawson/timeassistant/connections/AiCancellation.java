package com.hawson.timeassistant.connections;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

public final class AiCancellation {
    private boolean cancelled;
    private final List<Runnable> listeners = new ArrayList<>();
    public synchronized void onCancel(Runnable action) {
        if (cancelled) action.run(); else listeners.add(action);
    }
    public synchronized void cancel() {
        if (cancelled) return;
        cancelled = true;
        for (Runnable listener : listeners) listener.run();
        listeners.clear();
    }
    public synchronized void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("请求已取消");
        }
    }
}
