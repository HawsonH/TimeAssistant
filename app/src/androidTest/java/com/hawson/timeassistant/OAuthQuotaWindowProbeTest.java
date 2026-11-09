package com.hawson.timeassistant;

import android.content.Context;
import android.os.Bundle;
import android.util.Base64;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.ai.AiTransportPolicy;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.network.AiHttp;
import com.hawson.timeassistant.quota.sub2api.Sub2ApiQuotaCodec;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in compatibility probe. No raw tokens, identity claims or response bodies leave the app. */
public class OAuthQuotaWindowProbeTest {
    private static final String ENDPOINT = "https://chatgpt.com/backend-api/wham/usage";
    private static final Set<String> ERROR_CODES = new HashSet<>(Arrays.asList(
            "invalid_token", "invalid_api_key", "token_expired", "invalid_audience", "insufficient_scope",
            "unauthorized", "access_denied", "authentication_error", "account_not_found",
            "missing_account_id", "unsupported_token", "invalid_authentication"));

    @Test public void reportOfficialQuotaCompatibility() {
        Assume.assumeTrue("official-read-once".equals(
                InstrumentationRegistry.getArguments().getString("quotaProbe")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Probe must not access the original QA or release app",
                "com.hawson.timeassistant.device.qa", context.getPackageName());
        DeviceFixtureSafety.requireIsolatedTarget(context);
        assertFalse("Unlock the phone normally before this probe",
                context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());
        assertTrue("Keep the phone awake during the probe",
                context.getSystemService(android.os.PowerManager.class).isInteractive());
        Bundle report = new Bundle();
        report.putString("probe_result", "NOT_SENT");
        report.putInt("quota_request_count", 0);
        report.putBoolean("five_hour_window_present", false);
        report.putBoolean("seven_day_window_present", false);
        String stage = "CREDENTIAL_RESOLUTION";
        HttpURLConnection connection = null;
        try {
            AiServices services = ((TimeAssistantApplication) context.getApplicationContext()).getAiServices();
            List<AiAccountPort.Account> accounts = new ArrayList<>();
            for (AiAccountPort.Account account : services.accounts.accounts()) if (account.authorized) accounts.add(account);
            if (accounts.size() != 1) {
                report.putString("probe_result", "REQUIRES_ONE_AUTHORIZED_ACCOUNT");
                return;
            }
            AiProfile profile = new AiProfile("", "ChatGPT", AiProfile.Kind.CHATGPT,
                    AiProfile.Protocol.RESPONSES, "https://api.openai.com/v1", "", accounts.get(0).reference);
            // Use the existing serialized refresh path; never copy or rotate grants in test code.
            String token = services.accounts.resolve(profile, new AiCancellation());
            if (token == null || token.isEmpty() || token.contains("\r") || token.contains("\n")) {
                report.putString("probe_result", "NO_USABLE_ACCESS_TOKEN");
                return;
            }
            JSONObject claims = diagnosticClaims(token);
            report.putString("token_audience", audience(claims));
            String accountId = accountClaim(claims);
            report.putBoolean("account_header_available", !accountId.isEmpty());
            report.putBoolean("direct_scope_present", Arrays.asList(claims.optString("scope", "").split(" "))
                    .contains("chatgpt.tokens.use.direct"));
            stage = "OFFICIAL_QUOTA_GET";
            connection = AiTransportPolicy.httpsOnly().open(new URL(ENDPOINT));
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "TimeAssistant-DeviceQA/1.0");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (!accountId.isEmpty()) connection.setRequestProperty("ChatGPT-Account-Id", accountId);
            report.putInt("quota_request_count", 1);
            int status = connection.getResponseCode();
            report.putInt("quota_http_status", status);
            boolean challenge = "challenge".equalsIgnoreCase(connection.getHeaderField("cf-mitigated"));
            report.putBoolean("web_challenge", challenge);
            String type = connection.getContentType();
            boolean json = type != null && type.toLowerCase(Locale.ROOT).contains("json");
            report.putBoolean("json_response", json);
            if (challenge || !json) {
                report.putString("probe_result", challenge ? "WEB_CHALLENGE" : "NON_JSON_RESPONSE");
                return;
            }
            JSONObject body;
            try (InputStream input = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream()) {
                body = input == null ? new JSONObject() : new JSONObject(AiHttp.read(input, 256 * 1024));
            }
            if (status < 200 || status >= 300) {
                report.putString("probe_result", status >= 300 && status < 400 ? "REDIRECT_REJECTED" : "HTTP_REJECTED");
                report.putString("recognized_error_code", errorCode(body));
                return;
            }
            stage = "QUOTA_WINDOW_PROJECTION";
            AiQuotaSnapshot snapshot = Sub2ApiQuotaCodec.decode(body, System.currentTimeMillis());
            JSONArray windows = new JSONArray();
            for (AiQuotaSnapshot.Limit limit : snapshot.limits) for (AiQuotaSnapshot.Window window : limit.windows) {
                if (window.seconds == null || (window.seconds != 18000 && window.seconds != 604800)) continue;
                boolean fiveHours = window.seconds == 18000;
                report.putBoolean(fiveHours ? "five_hour_window_present" : "seven_day_window_present", true);
                windows.put(new JSONObject().put("duration_seconds", window.seconds)
                        .put("used_percent", window.usedPercent == null ? JSONObject.NULL : window.usedPercent)
                        .put("remaining_percent", window.remainingPercent() == null ? JSONObject.NULL : window.remainingPercent())
                        .put("reset_at_millis", window.resetAt == null ? JSONObject.NULL : window.resetAt)
                        .put("allowed", limit.allowed == null ? JSONObject.NULL : limit.allowed)
                        .put("reached", limit.reached == null ? JSONObject.NULL : limit.reached));
            }
            report.putString("quota_windows", windows.toString());
            report.putString("probe_result", windows.length() > 0 ? "WINDOWS_RETURNED" : "NO_MATCHING_WINDOWS");
        } catch (Exception failure) {
            report.putString("probe_result", "PROBE_FAILED");
            report.putString("failure_stage", stage);
            report.putString("failure_category", failure instanceof AiConnectionException
                    ? ((AiConnectionException) failure).status.name()
                    : failure instanceof java.net.UnknownHostException ? "DNS_FAILED"
                    : failure instanceof java.net.SocketTimeoutException ? "TIMEOUT"
                    : failure instanceof javax.net.ssl.SSLException ? "TLS_FAILED"
                    : failure instanceof org.json.JSONException ? "UNSUPPORTED_JSON"
                    : "UNAVAILABLE");
        } finally {
            if (connection != null) connection.disconnect();
            InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
        }
    }

    // Inspection only, not a JWT verifier or an authorization decision. Stored grants are resolved above.
    static JSONObject diagnosticClaims(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3 || parts[1].length() > 32768) return new JSONObject();
            return new JSONObject(new String(Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                    StandardCharsets.UTF_8));
        } catch (Exception opaque) { return new JSONObject(); }
    }
    static String audience(JSONObject claims) {
        Object value = claims.opt("aud");
        if ("https://api.openai.com/v1".equals(value)) return "PUBLIC_API";
        if (value instanceof JSONArray) for (int i = 0; i < ((JSONArray) value).length(); i++)
            if ("https://api.openai.com/v1".equals(((JSONArray) value).opt(i))) return "PUBLIC_API";
        return "OTHER_OR_OPAQUE";
    }
    static String accountClaim(JSONObject claims) {
        JSONObject auth = claims.optJSONObject("https://api.openai.com/auth");
        String account = auth == null ? "" : auth.optString("chatgpt_account_id", "");
        return account.matches("[A-Za-z0-9_-]{1,128}") ? account : "";
    }
    static String errorCode(JSONObject body) {
        for (String key : new String[]{"error", "detail"}) {
            JSONObject error = body.optJSONObject(key);
            if (error != null && ERROR_CODES.contains(error.optString("code"))) return error.optString("code");
        }
        return ERROR_CODES.contains(body.optString("code")) ? body.optString("code") : "UNRECOGNIZED";
    }
}
