package com.hawson.timeassistant.proactive;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Serializes preflight through publication across manual and scheduled workers. */
final class ProactiveRunGate {
    private static final ReentrantLock LOCK = new ReentrantLock();

    private ProactiveRunGate() { }

    static <T> T run(Supplier<T> check) throws InterruptedException {
        // Never hold the consent lock while waiting for another worker or its model response.
        LOCK.lockInterruptibly();
        try {
            return check.get();
        } finally {
            LOCK.unlock();
        }
    }
}
