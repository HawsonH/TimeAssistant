package com.hawson.timeassistant;

import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.data.connections.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import static org.junit.Assert.*;

/** Generated credentials in isolated preferences; never reads or resets a user's account. */
public class OAuthConnectionPersistenceTest {
    private Context context;
    private final List<AiServices> instances = new ArrayList<>();
    @Before public void prepare() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context = new ContextWrapper(target) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("test_oauth_persistence_" + name, mode);
            }
        };
        context.getSharedPreferences(EncryptedCredentialStore.FILE, 0).edit().clear().commit();
        context.getSharedPreferences(LocalAiProfiles.FILE, 0).edit().clear().commit();
    }
    private void account(String reference) throws Exception {
        new EncryptedCredentialStore(context).write(reference, new JSONObject()
                .put("access", "fixture-only-access").put("refresh", "fixture-only-refresh")
                .put("expires", System.currentTimeMillis() + 3600_000).put("client", "oaiapp_fixture")
                .put("subject", "fixture-subject").put("label", "Fixture account")
                .put("scope", "openid chatgpt.tokens.use.direct").toString());
    }
    private AiServices services() throws Exception {
        AiServices services = new AiServices(context, "", "", ""); instances.add(services);
        Assume.assumeTrue(services.accounts.available());
        java.lang.reflect.Field field = AiServices.class.getDeclaredField("recoveryExecutor"); field.setAccessible(true);
        ((ExecutorService) field.get(services)).submit(() -> { }).get(5, TimeUnit.SECONDS);
        return services;
    }
    @Test public void accountAndSelectionSurviveServiceRecreationWithoutChangingContext() throws Exception {
        account("oauth:fixture");
        LocalAiProfiles profiles = new LocalAiProfiles(context, new EncryptedCredentialStore(context));
        AiProfile saved = profiles.save(new AiProfile("", "Fixture", AiProfile.Kind.CHATGPT,
                AiProfile.Protocol.RESPONSES, "https://api.openai.com/v1", "fixture-model", "oauth:fixture"), null);
        profiles.activate(saved.id);
        String boundary = profiles.contextBoundary(); long startedAt = profiles.contextStartedAt();
        AiServices first = services(), restarted = services();
        assertTrue(first.configured()); assertTrue(restarted.configured());
        assertEquals(saved.id, restarted.profiles.active().id);
        assertEquals("fixture-model", restarted.profiles.active().model);
        assertEquals(boundary, restarted.profiles.contextBoundary());
        assertEquals(startedAt, restarted.profiles.contextStartedAt());
        assertEquals(1, restarted.profiles.list().size());
    }
    @Test public void ambiguousAccountsDoNotSilentlyChooseOne() throws Exception {
        account("oauth:one"); account("oauth:two");
        AiServices services = services();
        assertNull(services.profiles.active()); assertFalse(services.configured());
        assertEquals("请选择要使用的 ChatGPT 账号", services.connectionNotice());
    }
    @Test public void explicitSelectionRetriesCancelledStartupRecovery() throws Exception {
        account("oauth:fixture");
        LocalAiProfiles profiles = new LocalAiProfiles(context, new EncryptedCredentialStore(context));
        AiProfile saved = profiles.save(new AiProfile("", "Fixture", AiProfile.Kind.CHATGPT,
                AiProfile.Protocol.RESPONSES, "https://api.openai.com/v1", "fixture-model", "oauth:fixture"), null);
        profiles.activate(saved.id);
        AiServices services = services();
        java.lang.reflect.Field field = AiServices.class.getDeclaredField("recoveryExecutor");
        field.setAccessible(true);
        ExecutorService executor = (ExecutorService) field.get(services);
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        executor.execute(() -> {
            blocked.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        try {
            java.lang.reflect.Method prepare = AiServices.class.getDeclaredMethod("prepareAccount", String.class, boolean.class);
            prepare.setAccessible(true); prepare.invoke(services, "oauth:fixture", false);
            services.cancelAll();
            services.recoverAccount("oauth:fixture");
        } finally { release.countDown(); }
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        assertEquals("ChatGPT 连接已就绪", services.connectionNotice());
        assertEquals(saved.id, services.profiles.active().id);
    }
    @After public void clean() throws Exception {
        for (AiServices services : instances) {
            services.cancelAll(); services.accounts.cancelSignIn();
            java.lang.reflect.Field field = AiServices.class.getDeclaredField("recoveryExecutor"); field.setAccessible(true);
            ((ExecutorService) field.get(services)).shutdownNow();
            if (!services.accounts.available()) continue;
            for (String name : Arrays.asList("executor", "listenerExecutor")) {
                java.lang.reflect.Field executor = services.accounts.getClass().getDeclaredField(name); executor.setAccessible(true);
                ((ExecutorService) executor.get(services.accounts)).shutdownNow();
            }
            java.lang.reflect.Field auth = services.accounts.getClass().getDeclaredField("service"); auth.setAccessible(true);
            Object service = auth.get(services.accounts); service.getClass().getMethod("dispose").invoke(service);
        }
        context.getSharedPreferences(EncryptedCredentialStore.FILE, 0).edit().clear().commit();
        context.getSharedPreferences(LocalAiProfiles.FILE, 0).edit().clear().commit();
    }
}
