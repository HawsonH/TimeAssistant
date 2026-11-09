package com.hawson.timeassistant;

import android.content.Context;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.ai.AiTransportPolicy;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.data.connections.EncryptedCredentialStore;
import com.hawson.timeassistant.network.AiHttp;
import com.hawson.timeassistant.quota.sub2api.Sub2ApiQuotaCodec;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import org.json.*;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Read-only comparison against Wei-Shaw/sub2api d851a384. Never changes OAuth registration. */
public class Sub2ApiQuotaCompatibilityTest {
    private static final String ENDPOINT = "https://chatgpt.com/backend-api/wham/usage";

    @Test public void referencePreflightRequiresAnActualAccountId() throws Exception {
        assertEquals("", accountId(new JSONObject(), new JSONObject()));
        JSONObject id = claimsWithAccount("fixture-account");
        assertEquals("fixture-account", accountId(new JSONObject(), id));
        assertEquals("access-account", accountId(claimsWithAccount("access-account"), id));
        assertEquals("", accountId(claimsWithAccount("bad\r\nheader"), new JSONObject()));
        assertFalse(headers("fixture-token", "", true).containsKey("ChatGPT-Account-Id"));
    }

    @Test public void referenceHeadersPreserveBearerAndAccountIsolation() {
        Map<String, String> baseline = headers("fixture-token", "fixture-account", false);
        Map<String, String> reference = headers("fixture-token", "fixture-account", true);
        assertEquals(baseline.get("Authorization"), reference.get("Authorization"));
        assertEquals(baseline.get("ChatGPT-Account-Id"), reference.get("ChatGPT-Account-Id"));
        assertFalse(baseline.containsKey("originator"));
        assertEquals("Codex Desktop", reference.get("originator"));
        assertEquals("codex-1", reference.get("openai-beta"));
        assertFalse(reference.containsKey("Cookie"));
        assertFalse(reference.containsKey("client_id"));
    }

    @Test public void responseStatusTakesPrecedenceOverContentType() {
        assertEquals("HTTP_REJECTED", result(401, false, false));
        assertEquals("HTTP_REJECTED", result(403, false, false));
        assertEquals("WEB_CHALLENGE", result(403, false, true));
        assertEquals("REDIRECT_REJECTED", result(302, false, false));
        assertEquals("NON_JSON_RESPONSE", result(200, false, false));
        assertEquals("JSON_SUCCESS", result(200, true, false));
    }

    @Test public void compareExistingGrantWithReferenceHeaders() {
        Assume.assumeTrue("sub2api-header-compare".equals(
                InstrumentationRegistry.getArguments().getString("quotaProbe")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Only the isolated account-preserving QA package may run this probe",
                "com.hawson.timeassistant.device.qa", context.getPackageName());
        DeviceFixtureSafety.requireIsolatedTarget(context);
        assertFalse("Unlock the phone normally before the probe",
                context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());
        assertTrue("Keep the phone awake during the probe",
                context.getSystemService(android.os.PowerManager.class).isInteractive());
        Bundle report = new Bundle();
        report.putString("source_revision", "d851a38477597157e78450d30af2d8504764d085");
        report.putString("probe_result", "NOT_SENT");
        report.putInt("quota_request_count", 0);
        try {
            AiServices services = ((TimeAssistantApplication) context.getApplicationContext()).getAiServices();
            List<AiAccountPort.Account> accounts = new ArrayList<>();
            for (AiAccountPort.Account account : services.accounts.accounts())
                if (account.authorized) accounts.add(account);
            if (accounts.size() != 1) {
                report.putString("probe_result", "REQUIRES_ONE_AUTHORIZED_ACCOUNT");
                return;
            }
            String reference = accounts.get(0).reference;
            AiProfile profile = new AiProfile("", "ChatGPT", AiProfile.Kind.CHATGPT,
                    AiProfile.Protocol.RESPONSES, "https://api.openai.com/v1", "", reference);
            String token = services.accounts.resolve(profile, new AiCancellation());
            if (token == null || token.isEmpty() || token.contains("\r") || token.contains("\n")) {
                report.putString("probe_result", "NO_USABLE_ACCESS_TOKEN");
                return;
            }
            JSONObject access = OAuthQuotaWindowProbeTest.diagnosticClaims(token);
            // Inspect only this account's encrypted record, in-process. No raw claims are reported.
            JSONObject record = new JSONObject(new EncryptedCredentialStore(context).read(reference));
            JSONObject identity = OAuthQuotaWindowProbeTest.diagnosticClaims(record.optString("idToken", ""));
            String account = accountId(access, identity);
            report.putString("token_audience", OAuthQuotaWindowProbeTest.audience(access));
            report.putBoolean("access_account_claim_present", !OAuthQuotaWindowProbeTest.accountClaim(access).isEmpty());
            report.putBoolean("identity_account_claim_present", !OAuthQuotaWindowProbeTest.accountClaim(identity).isEmpty());
            report.putBoolean("account_header_available", !account.isEmpty());
            report.putString("sub2api_preflight", account.isEmpty() ? "MISSING_ACCOUNT_ID" : "ACCOUNT_ID_AVAILABLE");
            report.putString("oauth_client_type", record.optString("client").startsWith("oaiapp_")
                    ? "DYNAMIC_REGISTRATION" : "OTHER_OR_UNKNOWN");
            report.putBoolean("oauth_client_changed", false);
            // Missing account ID is intentionally not guessed. This is a header-only diagnostic,
            // not a claim to reproduce sub2api's preflight, TLS client, or full authentication flow.
            probe(token, account, false, "baseline_", report);
            probe(token, account, true, "reference_", report);
            report.putString("probe_result", "COMPARISON_FINISHED");
            boolean retained = false;
            for (AiAccountPort.Account saved : services.accounts.accounts())
                if (reference.equals(saved.reference) && saved.authorized) retained = true;
            report.putBoolean("authorized_account_retained", retained);
            assertTrue("The probe must retain the authorized account", retained);
        } catch (Exception failure) {
            report.putString("probe_result", "CREDENTIAL_DIAGNOSTIC_FAILED");
            report.putString("failure_category", category(failure));
        } finally {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
        }
    }

    private static Map<String, String> headers(String token, String account, boolean reference) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "TimeAssistant-DeviceQA/1.0");
        headers.put("Authorization", "Bearer " + token);
        if (!account.isEmpty()) headers.put("ChatGPT-Account-Id", account);
        if (reference) {
            headers.put("openai-beta", "codex-1");
            headers.put("oai-language", "zh-CN");
            headers.put("originator", "Codex Desktop");
            headers.put("sec-fetch-site", "none");
            headers.put("sec-fetch-mode", "no-cors");
            headers.put("sec-fetch-dest", "empty");
            headers.put("priority", "u=4, i");
        }
        return headers;
    }

    private static void probe(String token, String account, boolean reference, String prefix, Bundle report) {
        HttpURLConnection connection = null;
        report.putBoolean(prefix + "five_hour_window_present", false);
        report.putBoolean(prefix + "seven_day_window_present", false);
        try {
            connection = AiTransportPolicy.httpsOnly().open(new URL(ENDPOINT));
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            for (Map.Entry<String, String> header : headers(token, account, reference).entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
            report.putInt("quota_request_count", report.getInt("quota_request_count") + 1);
            int status = connection.getResponseCode();
            boolean challenge = "challenge".equalsIgnoreCase(connection.getHeaderField("cf-mitigated"));
            String type = connection.getContentType();
            boolean json = type != null && type.toLowerCase(Locale.ROOT).contains("json");
            report.putInt(prefix + "http_status", status);
            report.putBoolean(prefix + "json_response", json);
            report.putBoolean(prefix + "web_challenge", challenge);
            report.putString(prefix + "result", result(status, json, challenge));
            if (!json || challenge || status >= 300 && status < 400) return;
            JSONObject body;
            try (InputStream input = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream()) {
                body = input == null ? new JSONObject() : new JSONObject(AiHttp.read(input, 256 * 1024));
            }
            if (status < 200 || status >= 300) {
                report.putString(prefix + "recognized_error_code", OAuthQuotaWindowProbeTest.errorCode(body));
                return;
            }
            AiQuotaSnapshot snapshot = Sub2ApiQuotaCodec.decode(body, System.currentTimeMillis());
            for (AiQuotaSnapshot.Limit limit : snapshot.limits) for (AiQuotaSnapshot.Window window : limit.windows) {
                if (Long.valueOf(18000).equals(window.seconds)) report.putBoolean(prefix + "five_hour_window_present", true);
                if (Long.valueOf(604800).equals(window.seconds)) report.putBoolean(prefix + "seven_day_window_present", true);
            }
        } catch (Exception failure) {
            report.putString(prefix + "result", "REQUEST_FAILED");
            report.putString(prefix + "failure_category", category(failure));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String result(int status, boolean json, boolean challenge) {
        if (challenge) return "WEB_CHALLENGE";
        if (status >= 300 && status < 400) return "REDIRECT_REJECTED";
        if (status < 200 || status >= 300) return "HTTP_REJECTED";
        return json ? "JSON_SUCCESS" : "NON_JSON_RESPONSE";
    }
    private static String category(Exception failure) {
        if (failure instanceof AiConnectionException) return ((AiConnectionException) failure).status.name();
        if (failure instanceof java.net.SocketTimeoutException) return "TIMEOUT";
        if (failure instanceof java.net.UnknownHostException) return "DNS_FAILED";
        if (failure instanceof javax.net.ssl.SSLException) return "TLS_FAILED";
        if (failure instanceof JSONException) return "UNSUPPORTED_JSON";
        return "UNAVAILABLE";
    }
    private static String accountId(JSONObject access, JSONObject identity) {
        String account = OAuthQuotaWindowProbeTest.accountClaim(access);
        return account.isEmpty() ? OAuthQuotaWindowProbeTest.accountClaim(identity) : account;
    }
    private static JSONObject claimsWithAccount(String account) throws JSONException {
        return new JSONObject().put("https://api.openai.com/auth", new JSONObject().put("chatgpt_account_id", account));
    }
}
