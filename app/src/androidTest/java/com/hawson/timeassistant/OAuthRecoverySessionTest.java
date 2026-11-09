package com.hawson.timeassistant;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.data.connections.EncryptedCredentialStore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.*;
import static org.junit.Assert.*;

/** Isolated generated sessions only; browser navigation tests never exchange a code. */
public class OAuthRecoverySessionTest {
    private Context context;
    private EncryptedCredentialStore store;
    private final List<AiOAuthExtension> extensions = new ArrayList<>();
    private static final String PENDING = "registration:oauth-sign-in";
    private final AiAccountPort.Listener listener = new AiAccountPort.Listener() {
        public void finished(AiAccountPort.Account account) { }
        public void failed(String safe) { }
    };

    @Before public void prepare() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(target.getPackageName().endsWith(".qa") || target.getPackageName().endsWith(".qa.callback"));
        context = new ContextWrapper(target) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("test_oauth_recovery_" + name, mode);
            }
        };
        context.getSharedPreferences(EncryptedCredentialStore.FILE, 0).edit().clear().commit();
        store = new EncryptedCredentialStore(context);
    }

    @After public void clean() throws Exception {
        for (AiOAuthExtension extension : extensions) {
            extension.cancelSignIn();
            Field executor = extension.getClass().getDeclaredField("executor");
            executor.setAccessible(true);
            ((ExecutorService) executor.get(extension)).shutdownNow();
            Field listening = extension.getClass().getDeclaredField("listenerExecutor");
            listening.setAccessible(true);
            ((ExecutorService) listening.get(extension)).shutdownNow();
            Field service = extension.getClass().getDeclaredField("service");
            service.setAccessible(true);
            Object authorization = service.get(extension);
            authorization.getClass().getMethod("dispose").invoke(authorization);
        }
        context.deleteSharedPreferences("test_oauth_recovery_" + EncryptedCredentialStore.FILE);
    }

    private AiOAuthExtension extension() {
        AiOAuthExtension result = OAuthModule.create(context, new EncryptedCredentialStore(context));
        Assume.assumeTrue(result.available());
        extensions.add(result);
        return result;
    }

    @Test public void freshEncryptedSessionSurvivesExtensionRecreation() throws Exception {
        AiOAuthExtension first = extension();
        first.beginSignIn("", listener);
        assertTrue(first.hasPendingSignIn());
        assertTrue(extension().hasPendingSignIn());
        assertNotNull(store.read(PENDING));
    }

    @Test public void authorizationCodeExchangeRetainsOidcNonceAndPkce() throws Exception {
        AiOAuthExtension first = extension();
        Uri authorization = Uri.parse(first.beginSignIn("", listener));
        JSONObject pending = new JSONObject(store.read(PENDING));
        java.lang.reflect.Method builder = first.getClass().getDeclaredMethod("authorizationCodeRequest",
                String.class, String.class, String.class, String.class, String.class);
        builder.setAccessible(true);
        Object tokenRequest = builder.invoke(first, "oaiapp_fixture", "fixture-only",
                authorization.getQueryParameter("redirect_uri"), pending.getString("verifier"),
                authorization.getQueryParameter("nonce"));
        assertEquals(pending.getString("nonce"), tokenRequest.getClass().getField("nonce").get(tokenRequest));
        assertEquals(pending.getString("verifier"), tokenRequest.getClass().getField("codeVerifier").get(tokenRequest));
        assertEquals(authorization.getQueryParameter("redirect_uri"),
                tokenRequest.getClass().getField("redirectUri").get(tokenRequest).toString());
        assertEquals("oaiapp_fixture", tokenRequest.getClass().getField("clientId").get(tokenRequest));
    }

    @Test public void wrongCallbackDoesNotDiscardFreshSession() throws Exception {
        AiOAuthExtension first = extension();
        Uri request = Uri.parse(first.beginSignIn("", listener));
        AiOAuthExtension restored = extension();
        try {
            restored.completePendingSignIn(request.getQueryParameter("redirect_uri")
                    + "?code=fixture-only&state=incorrect", listener);
            fail("Should reject wrong state before network access");
        } catch (AiSignInException expected) {
            assertEquals(AiSignInException.Reason.STATE, expected.reason);
        }
        assertTrue(restored.hasPendingSignIn());
    }

    @Test public void expiredSessionIsDifferentFromMissingSession() throws Exception {
        AiOAuthExtension first = extension();
        first.beginSignIn("", listener);
        JSONObject record = new JSONObject(store.read(PENDING));
        record.put("started", System.currentTimeMillis() - 600_001L);
        store.write(PENDING, record.toString());
        assertRejected(first, AiSignInException.Reason.EXPIRED);
        assertRejected(first, AiSignInException.Reason.MISSING);
    }

    @Test public void cancellationRemovesRestoredSession() throws Exception {
        AiOAuthExtension first = extension();
        first.beginSignIn("", listener);
        AiOAuthExtension restored = extension();
        restored.cancelSignIn();
        assertFalse(first.hasPendingSignIn());
        assertRejected(restored, AiSignInException.Reason.MISSING);
    }

    @Test public void listenerTimeoutReportsExpiredInsteadOfRecoverableCallback() throws Exception {
        AiOAuthExtension first = extension();
        first.beginSignIn("", listener);
        JSONObject record = new JSONObject(store.read(PENDING));
        record.put("started", System.currentTimeMillis() - 600_001L);
        store.write(PENDING, record.toString());
        Field callback = first.getClass().getDeclaredField("callbackListener");
        callback.setAccessible(true);
        ((java.io.Closeable) callback.get(first)).close();
        Field listening = first.getClass().getDeclaredField("listenerExecutor");
        listening.setAccessible(true);
        ((ExecutorService) listening.get(first)).submit(() -> { }).get(3, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(AiSignInStatus.EXPIRED, first.signInStatus());
        assertFalse(first.hasPendingSignIn());
    }

    @Test public void statusReadClosesExpiredCallbackListener() throws Exception {
        AiOAuthExtension first = extension();
        first.beginSignIn("", listener);
        Field callback = first.getClass().getDeclaredField("callbackListener");
        callback.setAccessible(true);
        Object listening = callback.get(first);
        java.lang.reflect.Method isOpen = listening.getClass().getDeclaredMethod("isOpen");
        isOpen.setAccessible(true);
        assertEquals(true, isOpen.invoke(listening));
        JSONObject record = new JSONObject(store.read(PENDING));
        record.put("started", System.currentTimeMillis() - 600_001L);
        store.write(PENDING, record.toString());

        assertEquals(AiSignInStatus.EXPIRED, first.signInStatus());
        assertNull(callback.get(first));
        assertEquals(false, isOpen.invoke(listening));
        assertFalse(first.hasPendingSignIn());
        Field executor = first.getClass().getDeclaredField("listenerExecutor");
        executor.setAccessible(true);
        ((ExecutorService) executor.get(first)).submit(() -> { }).get(3, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(AiSignInStatus.EXPIRED, first.signInStatus());
    }

    @Test public void lateCancelledAttemptCannotReplaceNewSignIn() throws Exception {
        AiOAuthExtension first = extension();
        Uri request = Uri.parse(first.beginSignIn("", listener));
        CountDownLatch mainBlocked = new CountDownLatch(1);
        CountDownLatch releaseMain = new CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            mainBlocked.countDown();
            try { releaseMain.await(4, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(mainBlocked.await(2, TimeUnit.SECONDS));
            first.completePendingSignIn(request.getQueryParameter("redirect_uri")
                    + "?error=access_denied&state=" + request.getQueryParameter("state"), listener);
            Field executor = first.getClass().getDeclaredField("executor");
            executor.setAccessible(true);
            ((ExecutorService) executor.get(first)).submit(() -> { }).get(2, TimeUnit.SECONDS);
            first.beginSignIn("", listener);
        } finally { releaseMain.countDown(); }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(AiSignInStatus.WAITING, first.signInStatus());
        assertTrue(first.hasPendingSignIn());
    }

    @Test public void authorizationUsesLoopbackAndNavigationLinkBelongsToQa() throws Exception {
        AiOAuthExtension first = extension();
        Uri request = Uri.parse(first.beginSignIn("", listener));
        String redirect = request.getQueryParameter("redirect_uri");
        assertEquals("http", Uri.parse(redirect).getScheme());
        assertEquals("127.0.0.1", Uri.parse(redirect).getHost());
        assertEquals("/auth/callback", Uri.parse(redirect).getPath());
        Uri callback = Uri.parse(context.getPackageName() + ".oauth://return");
        android.content.pm.ResolveInfo target = context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_VIEW, callback).addCategory(Intent.CATEGORY_BROWSABLE),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        assertNotNull("QA manifest must own the authorization app link", target);
        assertEquals(context.getPackageName(), target.activityInfo.packageName);
    }
    @Test public void loopbackCallbackCompletesWithoutReturnIntent() throws Exception {
        AiOAuthExtension first = extension();
        Uri request = Uri.parse(first.beginSignIn("", listener));
        String callback = request.getQueryParameter("redirect_uri") + "?error=access_denied&state="
                + request.getQueryParameter("state");
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                new java.net.URL(callback).openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
        try { assertEquals(200, connection.getResponseCode()); }
        finally { connection.disconnect(); }
        Field executor = first.getClass().getDeclaredField("executor"); executor.setAccessible(true);
        ((ExecutorService) executor.get(first)).submit(() -> { }).get(3, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(AiSignInStatus.CANCELLED, first.signInStatus());
        assertFalse(first.hasPendingSignIn());
    }

    private void assertRejected(AiOAuthExtension extension, AiSignInException.Reason reason) throws Exception {
        try {
            extension.completePendingSignIn("fixture-only", listener);
            fail("Should reject unavailable session before reading callback");
        } catch (AiSignInException expected) { assertEquals(reason, expected.reason); }
    }
}
