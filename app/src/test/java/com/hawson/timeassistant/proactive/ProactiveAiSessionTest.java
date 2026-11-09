package com.hawson.timeassistant.proactive;

import com.hawson.timeassistant.ai.AiPlan;
import com.hawson.timeassistant.data.local.LocalIntegrationSettingsStore;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public final class ProactiveAiSessionTest {
    @Test public void generationChangedBeforeDispatchCannotReachClient() throws Exception {
        AtomicReference<String> generation = new AtomicReference<>("old");
        AtomicInteger errors = new AtomicInteger();
        // Null client/broker deliberately fail the test if stale work reaches either dependency.
        ProactiveAiSession session = new ProactiveAiSession(null, null, () -> {
            assertTrue(Thread.holdsLock(LocalIntegrationSettingsStore.PROACTIVE_LOCK));
            return generation.get().equals("old");
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        Future<?> result;
        try {
            synchronized (LocalIntegrationSettingsStore.PROACTIVE_LOCK) {
                result = worker.submit(() -> {
                    started.countDown();
                    session.run("test", Collections.emptyList(), new ProactiveAiSession.Callback() {
                        @Override public void onSuccess(AiPlan plan, List<String> sources) {
                            fail("stale generation published");
                        }
                        @Override public void onError() { errors.incrementAndGet(); }
                    });
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                generation.set("new");
            }
            result.get(5, TimeUnit.SECONDS);
            assertEquals(1, errors.get());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
