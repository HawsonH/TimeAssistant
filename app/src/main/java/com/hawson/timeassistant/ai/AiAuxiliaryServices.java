package com.hawson.timeassistant.ai;

import android.content.Context;
import android.content.SharedPreferences;
import com.hawson.timeassistant.AiServices;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.data.connections.EncryptedCredentialStore;
import com.hawson.timeassistant.data.local.AiEndpointTrustStore;
import com.hawson.timeassistant.network.AiHttp;
import com.hawson.timeassistant.compatible.AiUsageCodec;
import com.hawson.timeassistant.quota.sub2api.Sub2ApiQuotaCodec;
import com.hawson.timeassistant.quota.sub2api.Sub2ApiQuotaPort;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Separate speech and quota credentials never receive the ChatGPT grant. */
public final class AiAuxiliaryServices {
    private final SharedPreferences prefs;
    private final EncryptedCredentialStore secrets;
    private final AiEndpointTrustStore trust;
    private final AiServices ai;
    public AiAuxiliaryServices(Context context, AiServices ai) {
        this.ai = ai; prefs = context.getSharedPreferences("ai_auxiliary_v1", 0);
        secrets = new EncryptedCredentialStore(context); trust = new AiEndpointTrustStore(context);
    }
    public String get(String name, String fallback) { return prefs.getString(name, fallback); }
    public void put(String name, String value) { prefs.edit().putString(name, value).apply(); }
    public boolean hasSecret(String reference) { try { String s = secrets.read(reference); return s != null && !s.isEmpty(); } catch (Exception e) { return false; } }
    public void secret(String reference, String replacement) {
        if (!replacement.isEmpty()) {
            if (replacement.contains("\r") || replacement.contains("\n")) throw new IllegalArgumentException("凭据不能含换行");
            try { secrets.write(reference, replacement); }
            catch (Exception failed) { throw new IllegalStateException("无法保存凭据", failed); }
        }
    }
    public void clearSecret(String reference) { secrets.remove(reference); }
    public void allow(String url) { trust.policy().requireAllowed(url); }
    public void approve(String url) { trust.approve(url); }
    public static String base(String value) {
        String result = value.trim(); while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        URI parsed = URI.create(result);
        if (parsed.getHost() == null || parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null)
            throw new IllegalArgumentException("服务地址无效");
        return result;
    }
    public String transcribe(File audio, AiCancellation cancel) throws Exception {
        String root = base(get("speech.url", "https://api.openai.com/v1"));
        String model = get("speech.model", "gpt-transcribe").trim();
        if (model.isEmpty() || model.contains("\r") || model.contains("\n")) throw new IllegalArgumentException("请填写有效模型");
        String key = secrets.read("aux:speech");
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("请先配置语音 API Key");
        AiProfile profile = new AiProfile("speech", "语音", AiProfile.Kind.RELAY, AiProfile.Protocol.RESPONSES, root, model, "aux:speech");
        AiCallObserver.Call call = ai.usage.begin(profile, "speech");
        HttpURLConnection connection = null;
        try {
            cancel.check();
            connection = trust.policy().open(new URL(root + "/audio/transcriptions"));
            final HttpURLConnection active = connection; cancel.onCancel(active::disconnect);
            connection.setConnectTimeout(15000); connection.setReadTimeout(60000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            String boundary = "TimeAssistant" + java.util.UUID.randomUUID().toString().replace("-", "");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setChunkedStreamingMode(8192);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n--" + boundary
                    + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                try (InputStream input = new FileInputStream(audio)) {
                    byte[] bytes = new byte[8192]; int count;
                    while ((count = input.read(bytes)) != -1) { cancel.check(); out.write(bytes, 0, count); }
                }
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException(status == 401 || status == 403 ? "语音凭据或权限未被接受" : status == 429 ? "语音服务额度受限，请稍后重试" : "语音服务暂不可用（" + status + "）");
            JSONObject body = new JSONObject(AiHttp.read(connection.getInputStream(), AiHttp.LIMIT));
            call.usage(AiUsageCodec.read(body.optJSONObject("usage")));
            String text = body.optString("text", "").trim();
            if (text.isEmpty()) throw new IOException("未识别到文字，请重试");
            cancel.check(); call.finish("success"); return text;
        } catch (Exception failure) {
            boolean cancelled = false; try { cancel.check(); } catch (InterruptedIOException ignored) { cancelled = true; }
            call.finish(cancelled ? "cancelled" : "failed"); throw failure;
        } finally { if (connection != null) connection.disconnect(); }
    }
    public JSONObject quota(String account, AiCancellation cancel) throws Exception {
        AiQuotaPort provider = new Sub2ApiQuotaPort(trust.policy(), reference -> {
            String prefix = "quota." + reference + ".";
            java.util.Map<String, Object> config = new java.util.HashMap<>(prefs.getAll());
            return new Sub2ApiQuotaPort.Source((String) config.getOrDefault(prefix + "url", ""),
                    (String) config.getOrDefault(prefix + "id", ""),
                    "bearer".equals(config.get(prefix + "auth")),
                    (String) config.getOrDefault(prefix + "credential", "aux:quota:" + reference));
        }, secrets::read);
        return Sub2ApiQuotaCodec.encode(provider.read(account, cancel));
    }
    public boolean hasQuotaSecret(String account) {
        return hasSecret(get("quota." + account + ".credential", "aux:quota:" + account));
    }
    public synchronized void configureQuota(String account, String root, String id, String auth, String replacement) {
        String prefix = "quota." + account + ".";
        String previous = get(prefix + "credential", "aux:quota:" + account);
        String next = replacement.isEmpty() ? previous : "aux:quota:" + account + ":" + java.util.UUID.randomUUID();
        if (!replacement.isEmpty()) secret(next, replacement);
        if (!prefs.edit().putString(prefix + "url", root).putString(prefix + "id", id)
                .putString(prefix + "auth", auth).putString(prefix + "credential", next)
                .putString(prefix + "revision", java.util.UUID.randomUUID().toString())
                .putString(prefix + "identity", "").commit()) {
            if (!next.equals(previous)) secrets.remove(next);
            throw new IllegalStateException("无法保存额度来源");
        }
        if (!next.equals(previous)) secrets.remove(previous);
        clearSecret("aux:quota-cache:" + account);
    }
    public synchronized void removeQuota(String account) {
        String prefix = "quota." + account + ".";
        String credential = get(prefix + "credential", "aux:quota:" + account);
        if (!prefs.edit().putString(prefix + "url", "").remove(prefix + "credential")
                .putString(prefix + "revision", java.util.UUID.randomUUID().toString()).commit())
            throw new IllegalStateException("无法移除额度来源");
        clearSecret(credential); clearSecret("aux:quota-cache:" + account);
    }
    public void cacheQuota(String account, JSONObject data) {
        try { secrets.write("aux:quota-cache:" + account, data.toString()); }
        catch (Exception failed) { throw new IllegalStateException("无法缓存额度", failed); }
    }
    public JSONObject cachedQuota(String account) {
        try { String value = secrets.read("aux:quota-cache:" + account); return value == null ? null : new JSONObject(value); }
        catch (Exception ignored) { return null; }
    }
}
