package com.hawson.timeassistant.proactive;

import com.hawson.timeassistant.domain.ChatMessage;

import java.lang.ref.WeakReference;

/** Lets an active activity persist through its live repository instead of a stale second snapshot. */
public final class ProactiveMessageBus {
    public interface Listener { void onProactiveMessage(ChatMessage message); }
    private static WeakReference<Listener> listener = new WeakReference<>(null);
    private static volatile boolean userAiBusy;

    private ProactiveMessageBus() { }

    public static synchronized void register(Listener value) {
        listener = new WeakReference<>(value);
    }

    public static synchronized void unregister(Listener value) {
        if (listener.get() == value) listener.clear();
    }

    public static synchronized boolean publish(ChatMessage message) {
        Listener value = listener.get();
        if (value == null) return false;
        try {
            value.onProactiveMessage(message);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    public static void setUserAiBusy(boolean busy) { userAiBusy = busy; }
    public static boolean isUserAiBusy() { return userAiBusy; }
}
