package com.hawson.timeassistant.quota.sub2api;

import com.hawson.timeassistant.connections.AiQuotaSnapshot;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class Sub2ApiQuotaCodecTest {
    private static final long NOW = 1793491200000L;
    private AiQuotaSnapshot read(String json) throws Exception { return Sub2ApiQuotaCodec.decode(new JSONObject(json), NOW); }
    @Test public void missingWindowsAndNumbersRemainUnknown() throws Exception {
        AiQuotaSnapshot data = read("{\"rate_limit\":{\"primary_window\":null,\"secondary_window\":{}}}");
        assertEquals(1, data.limits.get(0).windows.size());
        AiQuotaSnapshot.Window window = data.limits.get(0).windows.get(0);
        assertNull(window.usedPercent); assertNull(window.remainingPercent()); assertNull(window.resetAt); assertNull(window.seconds);
    }
    @Test public void windowsAreClassifiedByDurationNotPosition() throws Exception {
        AiQuotaSnapshot data = read("{\"rate_limit\":{\"primary_window\":{\"limit_window_seconds\":604800,\"used_percent\":32.5},\"secondary_window\":{\"limit_window_seconds\":18000,\"used_percent\":80}}}");
        assertEquals(Long.valueOf(18000), data.limits.get(0).windows.get(0).seconds);
        assertEquals(20, data.limits.get(0).windows.get(0).remainingPercent(), 0);
        assertEquals(67.5, data.limits.get(0).windows.get(1).remainingPercent(), 0);
    }
    @Test public void relativeResetUsesServerObservationNotLatestFetch() throws Exception {
        AiQuotaSnapshot data = read("{\"fetched_at\":1793404800,\"rate_limit\":{\"primary_window\":{\"reset_after_seconds\":60}}}");
        assertEquals(Long.valueOf(1793404860000L), data.limits.get(0).windows.get(0).resetAt);
        assertTrue(data.stale(NOW)); assertTrue(data.limits.get(0).windows.get(0).resetPassed(NOW));
    }
    @Test public void cacheReadDoesNotRestartResetCountdown() throws Exception {
        AiQuotaSnapshot data = read("{\"rate_limit\":{\"primary_window\":{\"reset_after_seconds\":60}}}");
        JSONObject cached = Sub2ApiQuotaCodec.encode(data);
        AiQuotaSnapshot restored = Sub2ApiQuotaCodec.decode(cached, 0);
        assertEquals(data.observedAt, restored.observedAt);
        assertEquals(data.limits.get(0).windows.get(0).resetAt, restored.limits.get(0).windows.get(0).resetAt);
    }
    @Test public void invalidAndOverflowingValuesAreNotFabricated() throws Exception {
        AiQuotaSnapshot data = read("{\"fetched_at\":9223372036854775807,\"rate_limit\":{\"primary_window\":{\"used_percent\":-1,\"reset_at\":9223372036854775807,\"reset_after_seconds\":9223372036854775807,\"limit_window_seconds\":2.1}}}");
        AiQuotaSnapshot.Window window = data.limits.get(0).windows.get(0);
        assertNull(window.usedPercent); assertNull(window.resetAt); assertNull(window.seconds); assertTrue(data.stale(NOW));
    }
    @Test public void numericStringsDoNotBecomeNumbers() throws Exception {
        AiQuotaSnapshot.Window window = read("{\"rate_limit\":{\"primary_window\":{\"used_percent\":\"0\",\"reset_at\":\"1793500000\"}}}").limits.get(0).windows.get(0);
        assertNull(window.usedPercent); assertNull(window.resetAt);
    }
    @Test public void actualOverLimitDoesNotProduceNegativeRemaining() throws Exception {
        AiQuotaSnapshot.Window window = read("{\"rate_limit\":{\"allowed\":false,\"limit_reached\":true,\"primary_window\":{\"used_percent\":120}}}").limits.get(0).windows.get(0);
        assertEquals(0, window.remainingPercent(), 0); assertEquals(120, window.usedPercent, 0);
    }
    @Test public void balancesRetainDecimalPrecisionAndMissingIsNotZero() throws Exception {
        assertNull(read("{}").credits);
        assertNull(read("{\"credits\":{\"balance\":null}}").credits.balance);
        assertEquals("12.340000000000000001", read("{\"credits\":{\"balance\":\"12.340000000000000001\"}}").credits.balance);
        assertNull(read("{\"credits\":{\"balance\":\"NaN\"}}").credits.balance);
    }
    @Test public void additionalLimitsAndUnavailableFlagsArePreserved() throws Exception {
        AiQuotaSnapshot data = read("{\"additional_rate_limits\":[{\"metered_feature\":\"codex_bengalfox\",\"rate_limit\":{\"allowed\":false}}]}");
        assertEquals("codex_bengalfox", data.limits.get(0).name); assertEquals(Boolean.FALSE, data.limits.get(0).allowed);
        assertTrue(data.limits.get(0).windows.isEmpty());
    }
    @Test public void whitelistedCacheDropsArbitrarySecrets() throws Exception {
        JSONObject data = new JSONObject("{\"account_id\":\"demo\",\"access_token\":\"SECRET\",\"rate_limit\":{\"debug\":\"SECRET\"},\"credits\":{\"id\":\"SECRET\"}}");
        String cached = Sub2ApiQuotaCodec.encode(Sub2ApiQuotaCodec.decode(data, NOW)).toString();
        assertFalse(cached.contains("SECRET")); assertTrue(cached.contains("demo"));
    }
    @Test public void freshnessDoesNotMakeFutureOrMissingTimestampsCurrent() throws Exception {
        assertTrue(Sub2ApiQuotaCodec.decode(new JSONObject("{}"), 0).stale(NOW));
        assertTrue(read("{\"fetched_at\":1893491200}").stale(NOW));
        assertTrue(read("{}").stale(NOW));
        assertFalse(read("{\"fetched_at\":1793491200}").stale(NOW));
        assertTrue(read("{\"fetched_at\":1793491200}").stale(NOW + 300001));
    }
    @Test public void unknownObservationCannotRestartRelativeResetAtReceiveTime() throws Exception {
        AiQuotaSnapshot data = read("{\"rate_limit\":{\"primary_window\":{\"reset_after_seconds\":60}}}");
        assertEquals(NOW, data.receivedAt); assertEquals(0, data.observedAt);
        assertNull(data.limits.get(0).windows.get(0).resetAt);
    }
    @Test public void snapshotCollectionsCannotBeMutated() throws Exception {
        try { read("{}").limits.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }
}
