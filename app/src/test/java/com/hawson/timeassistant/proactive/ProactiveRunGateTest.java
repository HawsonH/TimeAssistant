package com.hawson.timeassistant.proactive;

import com.hawson.timeassistant.data.local.LocalIntegrationSettingsStore;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class ProactiveRunGateTest {
    @Test public void simultaneousManualAndScheduledChecksDoNotGenerateSameFingerprintTwice() throws Exception {
        overlap("same", "same", 3);
    }

    @Test public void simultaneousDifferentChecksCannotExceedDailyCap() throws Exception {
        overlap("manual", "scheduled", 1);
    }

    private void overlap(String firstFingerprint, String secondFingerprint, int cap) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(3);
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Set<String> published = new HashSet<>();
        AtomicInteger requests = new AtomicInteger();
        try {
            Future<?> first = workers.submit(() -> ProactiveRunGate.run(() -> {
                requests.incrementAndGet();
                generating.countDown();
                await(release);
                published.add(firstFingerprint);
                return true;
            }));
            assertTrue(generating.await(5, TimeUnit.SECONDS));
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<?> second = workers.submit(() -> {
                secondStarted.countDown();
                return ProactiveRunGate.run(() -> {
                    if (published.contains(secondFingerprint) || published.size() >= cap) return false;
                    requests.incrementAndGet();
                    published.add(secondFingerprint);
                    return true;
                });
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            // A configuration change must not wait for model generation or queued workers.
            workers.submit(() -> {
                synchronized (LocalIntegrationSettingsStore.PROACTIVE_LOCK) { return true; }
            }).get(5, TimeUnit.SECONDS);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(1, requests.get());
            assertEquals(1, published.size());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void failedGenerationReleasesGateForRetry() throws Exception {
        try {
            ProactiveRunGate.run(() -> { throw new IllegalStateException("fake generation failure"); });
            fail("failure swallowed");
        } catch (IllegalStateException expected) {
            assertEquals("fake generation failure", expected.getMessage());
        }
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            assertTrue(worker.submit(() -> ProactiveRunGate.run(() -> true)).get(5, TimeUnit.SECONDS));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void interruptedWaitNeverRunsPreflight() throws Exception {
        Thread.currentThread().interrupt();
        try {
            ProactiveRunGate.run(() -> { fail("interrupted work ran"); return false; });
            fail("interruption swallowed");
        } catch (InterruptedException expected) {
            // Worker restores the interrupt flag when this propagates.
        } finally {
            Thread.interrupted();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
