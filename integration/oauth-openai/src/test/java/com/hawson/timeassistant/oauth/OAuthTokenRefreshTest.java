package com.hawson.timeassistant.oauth;

import com.hawson.timeassistant.connections.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class OAuthTokenRefreshTest {
    private final Memory store = new Memory();
    private final AiProfile profile = new AiProfile("p", "Fixture", AiProfile.Kind.CHATGPT,
            AiProfile.Protocol.RESPONSES, "https://api.openai.com/v1", "fixture", "oauth:fixture");
    private JSONObject grant(long expiry) throws Exception {
        return new JSONObject().put("access", "old-access").put("refresh", "old-refresh")
                .put("expires", expiry).put("scope", "openid chatgpt.tokens.use.direct")
                .put("client", "oaiapp_fixture").put("subject", "fixture-subject");
    }
    private void save(JSONObject value) { store.value = value.toString(); }
    private JSONObject rotated(JSONObject saved) throws Exception {
        return saved.put("access", "new-access").put("refresh", "new-refresh")
                .put("expires", System.currentTimeMillis() + 3600_000);
    }
    @Test public void freshAccessSurvivesManagerRecreationWithoutNetwork() throws Exception {
        save(grant(System.currentTimeMillis() + 3600_000));
        OAuthTokenRefresh tokens = new OAuthTokenRefresh(store, new Object(), (saved, cancel) -> { throw new AssertionError(); });
        assertEquals("old-access", tokens.resolve(profile, null, new AiCancellation()));
    }
    @Test public void concurrentExpiredRequestsShareOneRotation() throws Exception {
        save(grant(1)); AtomicInteger calls = new AtomicInteger();
        OAuthTokenRefresh tokens = new OAuthTokenRefresh(store, new Object(), (saved, cancel) -> { calls.incrementAndGet(); return rotated(saved); });
        ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) results.add(workers.submit(() -> tokens.resolve(profile, null, new AiCancellation())));
            for (Future<String> result : results) assertEquals("new-access", result.get(3, TimeUnit.SECONDS));
        } finally { workers.shutdownNow(); }
        assertEquals(1, calls.get()); assertEquals("new-refresh", new JSONObject(store.value).getString("refresh"));
    }
    @Test public void lateRejectionReusesAlreadyRotatedToken() throws Exception {
        save(grant(System.currentTimeMillis() + 3600_000)); AtomicInteger calls = new AtomicInteger();
        OAuthTokenRefresh tokens = new OAuthTokenRefresh(store, new Object(), (saved, cancel) -> { calls.incrementAndGet(); return rotated(saved); });
        assertEquals("new-access", tokens.resolve(profile, "old-access", new AiCancellation()));
        assertEquals("new-access", tokens.resolve(profile, "old-access", new AiCancellation()));
        assertEquals(1, calls.get());
    }
    @Test public void networkFailureRetainsRenewableCredentials() throws Exception {
        save(grant(1)); String before = store.value;
        OAuthTokenRefresh tokens = new OAuthTokenRefresh(store, new Object(), (saved, cancel) -> { throw new java.net.SocketTimeoutException(); });
        try { tokens.resolve(profile, null, new AiCancellation()); fail(); }
        catch (java.net.SocketTimeoutException expected) { assertEquals(before, store.value); }
    }
    @Test public void terminalRefreshClearsOnlyUnusableTokens() throws Exception {
        save(grant(1));
        OAuthTokenRefresh tokens = new OAuthTokenRefresh(store, new Object(), (saved, cancel) -> { throw new OAuthTokenRefresh.Failure("invalid_grant"); });
        try { tokens.resolve(profile, null, new AiCancellation()); fail(); }
        catch (AiConnectionException expected) { assertEquals(AiConnectionException.Status.LOGIN_REQUIRED, expected.status); }
        JSONObject saved = new JSONObject(store.value);
        assertFalse(saved.has("access")); assertFalse(saved.has("refresh")); assertEquals("oaiapp_fixture", saved.getString("client"));
    }
    @Test public void failedPersistenceDoesNotReuseConsumedRefreshToken() throws Exception {
        save(grant(1)); AtomicInteger calls = new AtomicInteger();
        OAuthTokenRefresh tokens = new OAuthTokenRefresh(store, new Object(), (saved, cancel) -> { calls.incrementAndGet(); return rotated(saved); });
        store.fail = true;
        try { tokens.resolve(profile, null, new AiCancellation()); fail(); }
        catch (java.io.IOException expected) { }
        store.fail = false;
        assertEquals("new-access", tokens.resolve(profile, null, new AiCancellation())); assertEquals(1, calls.get());
    }
    @Test public void cancellationStopsBeforeGrant() throws Exception {
        save(grant(1)); AiCancellation cancel = new AiCancellation(); cancel.cancel();
        OAuthTokenRefresh tokens = new OAuthTokenRefresh(store, new Object(), (saved, ignored) -> { throw new AssertionError(); });
        try { tokens.resolve(profile, null, cancel); fail(); } catch (java.io.InterruptedIOException expected) { }
    }
    @Test public void revokedScopeIsSavedAndReportedAsPermissionFailure() throws Exception {
        save(grant(1));
        OAuthTokenRefresh tokens = new OAuthTokenRefresh(store, new Object(), (saved, cancel) -> rotated(saved).put("scope", "openid"));
        try { tokens.resolve(profile, null, new AiCancellation()); fail(); }
        catch (AiConnectionException expected) { assertEquals(AiConnectionException.Status.PERMISSION_DENIED, expected.status); }
        assertEquals("new-refresh", new JSONObject(store.value).getString("refresh"));
    }
    @Test public void interruptedIdentityCheckResumesRotatedSetAfterRestart() throws Exception {
        save(grant(1)); AtomicInteger calls = new AtomicInteger();
        OAuthTokenRefresh first = new OAuthTokenRefresh(store, new Object(), (saved, cancel) -> {
            calls.incrementAndGet(); return rotated(saved);
        }, replacement -> { throw new java.io.IOException("JWKS fixture unavailable"); });
        try { first.resolve(profile, null, new AiCancellation()); fail(); } catch (java.io.IOException expected) { }
        OAuthTokenRefresh restored = new OAuthTokenRefresh(store, new Object(), (saved, cancel) -> {
            throw new AssertionError("Must finish stored replacement before another refresh");
        });
        assertEquals("new-access", restored.resolve(profile, null, new AiCancellation()));
        assertEquals(1, calls.get()); assertNull(store.pending);
    }
    private static final class Memory implements AiCredentialsPort {
        String value, pending; boolean fail;
        public String read(String reference) { return reference.startsWith("registration:") ? pending : value; }
        public void write(String reference, String replacement) throws Exception {
            if (fail) throw new java.io.IOException("Fixture disk failure");
            if (reference.startsWith("registration:")) pending = replacement; else value = replacement;
        }
        public void remove(String reference) { if (reference.startsWith("registration:")) pending = null; else value = null; }
        public Set<String> references() { return Collections.singleton("oauth:fixture"); }
    }
}
