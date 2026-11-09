package com.hawson.timeassistant;

import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.connections.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Opt-in QA diagnostics. Credentials remain inside the app; reports contain public state only. */
public class OAuthRealFlowTest {
    private AiServices services() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".qa") || context.getPackageName().endsWith(".qa.callback"));
        return ((TimeAssistantApplication) context.getApplicationContext()).getAiServices();
    }

    @Test public void reportPublicSignInState() {
        Assume.assumeTrue("status".equals(InstrumentationRegistry.getArguments().getString("oauthMode")));
        AiServices services = services();
        Bundle state = new Bundle();
        state.putString("sign_in_phase", services.accounts.signInStatus().name());
        state.putBoolean("pending_authorization", services.accounts.hasPendingSignIn());
        int authorized = 0;
        for (AiAccountPort.Account account : services.accounts.accounts()) if (account.authorized) authorized++;
        state.putInt("authorized_accounts", authorized);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, state);
    }

    @Test public void reportPublicNetworkConnectivity() {
        Assume.assumeTrue("network".equals(InstrumentationRegistry.getArguments().getString("oauthMode")));
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".qa"));
        Bundle result = new Bundle();
        android.os.PowerManager power = context.getSystemService(android.os.PowerManager.class);
        result.putBoolean("battery_exempt", power.isIgnoringBatteryOptimizations(context.getPackageName()));
        java.net.URI official = java.net.URI.create("https://auth.openai.com/.well-known/openid-configuration");
        java.net.ProxySelector selector = java.net.ProxySelector.getDefault();
        result.putString("official_proxy_type", selector.select(official).get(0).type().name());
        result.putString("loopback_proxy_type", selector.select(java.net.URI.create("http://127.0.0.1:41991/health"))
                .get(0).type().name());
        result.putString("direct_metadata", publicMetadataProbe(official, true));
        result.putString("system_metadata", publicMetadataProbe(official, false));
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
    }

    private String publicMetadataProbe(java.net.URI official, boolean direct) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) (direct
                    ? official.toURL().openConnection(java.net.Proxy.NO_PROXY) : official.toURL().openConnection());
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
            return "HTTP_" + connection.getResponseCode();
        } catch (Exception failure) {
            if (failure instanceof java.net.UnknownHostException) return "DNS_FAILED";
            if (failure instanceof java.net.SocketTimeoutException) return "TIMEOUT";
            if (failure instanceof javax.net.ssl.SSLException) return "TLS_FAILED";
            if (failure instanceof java.net.ConnectException) return "CONNECT_FAILED";
            return "NETWORK_FAILED";
        } finally { if (connection != null) connection.disconnect(); }
    }

    @Test public void discoverModelsAndCompleteRealInference() {
        Assume.assumeTrue("inference".equals(InstrumentationRegistry.getArguments().getString("oauthMode")));
        AiServices services = services();
        String stage = "ACCOUNT";
        try {
            List<AiAccountPort.Account> authorized = new ArrayList<>();
            for (AiAccountPort.Account account : services.accounts.accounts()) if (account.authorized) authorized.add(account);
            assertEquals("Exactly one authorized QA account is required for this probe", 1, authorized.size());
            AiProfile profile = new AiProfile("", "ChatGPT", AiProfile.Kind.CHATGPT,
                    AiProfile.Protocol.RESPONSES, "https://api.openai.com/v1", "", authorized.get(0).reference);
            stage = "MODEL_DISCOVERY";
            List<String> models = services.models(profile, new AiCancellation());
            assertFalse("Official model catalog is empty", models.isEmpty());
            String selected = InstrumentationRegistry.getArguments().getString("model", models.get(0));
            assertTrue("Requested model is not in the account catalog", models.contains(selected));
            Bundle catalog = new Bundle();
            catalog.putString("model_discovery", "PASS"); catalog.putInt("model_count", models.size());
            catalog.putString("model_slug", selected);
            InstrumentationRegistry.getInstrumentation().sendStatus(0, catalog);
            profile = new AiProfile("", "ChatGPT", AiProfile.Kind.CHATGPT,
                    AiProfile.Protocol.RESPONSES, "https://api.openai.com/v1", selected, authorized.get(0).reference);
            stage = "INFERENCE";
            String response = services.complete(profile, new AiRequest("Reply briefly to this connection test.",
                    Collections.singletonList(new AiRequest.Message("user", "Reply exactly: TimeAssistant connected.",
                            Collections.emptyList()))), new AiCancellation());
            assertFalse("Completed inference returned empty text", response.trim().isEmpty());
            if ("true".equals(InstrumentationRegistry.getArguments().getString("activateVerifiedProfile"))) {
                stage = "PROFILE_SAVE";
                AiProfile saved = services.profiles.save(profile, null);
                services.profiles.activate(saved.id);
            }
            Bundle result = new Bundle();
            result.putString("model_discovery", "PASS"); result.putInt("model_count", models.size());
            result.putString("inference", "PASS"); result.putString("model_slug", selected);
            result.putBoolean("completed_nonempty", true);
            InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
        } catch (Exception failure) {
            Bundle diagnostic = new Bundle();
            StringBuilder locations = new StringBuilder();
            for (StackTraceElement frame : failure.getStackTrace()) {
                if (!frame.getClassName().startsWith("com.hawson.timeassistant.compatible.")) continue;
                if (locations.length() > 0) locations.append(';');
                locations.append(frame.getFileName()).append(':').append(frame.getLineNumber());
            }
            diagnostic.putString("parser_locations", locations.toString());
            if (failure instanceof org.json.JSONException) {
                diagnostic.putString("parser_reason", "模型返回空内容".equals(failure.getMessage()) ? "EMPTY_TEXT"
                        : "响应未完整完成".equals(failure.getMessage()) ? "NOT_COMPLETED" : "JSON_FORMAT");
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, diagnostic);
            String reason = failure instanceof AiConnectionException
                    ? ((AiConnectionException) failure).status.name() : failure.getClass().getSimpleName();
            fail(stage + " failed: " + reason);
        }
    }
    @Test public void completeInferenceUsingRestoredActiveConnection() throws Exception {
        Assume.assumeTrue("restart-inference".equals(InstrumentationRegistry.getArguments().getString("oauthMode")));
        AiServices services = services();
        AiProfile active = services.profiles.active();
        assertNotNull("Cold start must retain the active connection", active);
        assertEquals(AiProfile.Kind.CHATGPT, active.kind);
        assertTrue("Selected connection must remain configured", services.configured());
        String boundary = services.profiles.contextBoundary();
        String answer = services.complete(active, new AiRequest("Reply briefly to this connection test.",
                Collections.singletonList(new AiRequest.Message("user", "Reply exactly: TimeAssistant reconnected.",
                        Collections.emptyList()))), new AiCancellation());
        assertFalse(answer.trim().isEmpty());
        assertEquals("Inference must not rewrite profile selection", boundary, services.profiles.contextBoundary());
        Bundle report = new Bundle();
        report.putBoolean("restored_active_connection", true); report.putBoolean("completed_nonempty", true);
        report.putString("inference", "PASS");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
    }
}
