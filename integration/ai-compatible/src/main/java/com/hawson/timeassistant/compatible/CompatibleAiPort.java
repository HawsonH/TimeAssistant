package com.hawson.timeassistant.compatible;

import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.network.AiHttp;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class CompatibleAiPort implements AiPort {
    private final AiHttp http;
    private final AiBearerPort bearer;
    private AiCallObserver observer;
    public CompatibleAiPort(AiHttp http, AiBearerPort bearer) { this.http = http; this.bearer = bearer; }
    public CompatibleAiPort(AiHttp http, AiBearerPort bearer, AiCallObserver observer) {
        this(http, bearer); this.observer = observer;
    }
    private void validate(AiProfile profile) {
        if (profile.kind == AiProfile.Kind.CHATGPT
                && (!"https://api.openai.com/v1".equals(profile.baseUrl)
                || profile.protocol != AiProfile.Protocol.RESPONSES)) {
            throw new IllegalArgumentException("官方令牌禁止用于中转");
        }
    }
    @Override public String complete(AiProfile profile, AiRequest request, AiCancellation cancel) throws Exception {
        AiCallObserver.Call call = observer == null ? null : observer.begin(profile, "chat");
        try {
            String result = completeObserved(profile, request, cancel, call);
            if (call != null) call.finish("success");
            return result;
        } catch (Exception failed) {
            boolean cancelled = false;
            try { cancel.check(); } catch (java.io.InterruptedIOException ignored) { cancelled = true; }
            if (call != null) call.finish(cancelled ? "cancelled" : "failed");
            throw failed;
        }
    }
    private String completeObserved(AiProfile profile, AiRequest request, AiCancellation cancel,
                                    AiCallObserver.Call call) throws Exception {
        validate(profile);
        String endpoint = profile.baseUrl + (profile.protocol == AiProfile.Protocol.RESPONSES
                ? "/responses" : "/chat/completions");
        HttpURLConnection connection = open(profile, endpoint, "POST",
                AiWireCodec.encode(profile, request).toString(), cancel);
        try {
            if (profile.protocol == AiProfile.Protocol.RESPONSES) return ResponsesStream.read(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8), cancel,
                    usage -> { if (call != null) call.usage(usage); });
            String raw = AiHttp.read(connection.getInputStream(), AiHttp.LIMIT);
            if (call != null) call.usage(AiUsageCodec.read(new JSONObject(raw).optJSONObject("usage")));
            return AiWireCodec.chatText(raw);
        } finally { connection.disconnect(); }
    }
    @Override public List<String> models(AiProfile profile, AiCancellation cancel) throws Exception {
        List<String> ids = new ArrayList<>();
        for (AiModelOption option : modelOptions(profile, cancel)) ids.add(option.id);
        return ids;
    }
    @Override public List<AiModelOption> modelOptions(AiProfile profile, AiCancellation cancel) throws Exception {
        validate(profile);
        HttpURLConnection connection = open(profile, profile.baseUrl + "/models", "GET", null, cancel);
        JSONObject root;
        try { root = new JSONObject(AiHttp.read(connection.getInputStream(), AiHttp.LIMIT)); }
        finally { connection.disconnect(); }
        JSONArray array = root.optJSONArray(profile.kind == AiProfile.Kind.CHATGPT ? "models" : "data");
        if (array == null) throw new IllegalArgumentException("模型列表格式无效，可手动填写模型");
        List<AiModelOption> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (profile.kind == AiProfile.Kind.CHATGPT && !"list".equals(item.optString("visibility"))) continue;
            String id = item.optString(profile.kind == AiProfile.Kind.CHATGPT ? "slug" : "id");
            if (!id.isEmpty()) result.add(new AiModelOption(id, item.optString("display_name", item.optString("name", id))));
        }
        return result;
    }
    private HttpURLConnection open(AiProfile profile, String endpoint, String method, String body,
                                   AiCancellation cancel) throws Exception {
        String key = bearer.resolve(profile, cancel);
        if (key == null || key.isEmpty()) throw new AiConnectionException(AiConnectionException.Status.LOGIN_REQUIRED,
                "请先配置凭据或重新登录");
        for (int attempt = 0; ; attempt++) {
            cancel.check();
            try {
                return http.open(endpoint, method, key, body, "application/json; charset=utf-8", cancel,
                        AiWireCodec::httpFailure);
            } catch (AiConnectionException failure) {
                if (attempt != 0 || profile.kind != AiProfile.Kind.CHATGPT || !failure.refreshable) throw failure;
                String renewed = bearer.refreshAfterRejection(profile, key, cancel);
                if (renewed == null || renewed.isEmpty()) throw failure;
                key = renewed;
            }
        }
    }
}
