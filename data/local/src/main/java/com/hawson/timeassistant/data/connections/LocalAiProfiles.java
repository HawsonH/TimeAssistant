package com.hawson.timeassistant.data.connections;

import android.content.Context;
import android.content.SharedPreferences;
import com.hawson.timeassistant.ai.AiTransportPolicy;
import com.hawson.timeassistant.connections.*;
import java.net.URI;
import java.util.*;
import org.json.*;

/** One atomic metadata document; credentials are staged before the metadata commit. */
public final class LocalAiProfiles implements AiProfilesPort {
    public static final String FILE = "ai_connection_profiles";
    private final SharedPreferences prefs;
    private final AiCredentialsPort credentials;
    private JSONObject document;
    public LocalAiProfiles(Context context, AiCredentialsPort credentials) {
        this.credentials = credentials;
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        try {
            document = new JSONObject(prefs.getString("document", "{\"version\":1,\"profiles\":[]}"));
            if (document.getInt("version") != 1) throw new IllegalStateException("不支持的连接配置版本");
        } catch (JSONException error) { throw new IllegalStateException("连接配置损坏，未覆盖原文件", error); }
    }
    @Override public synchronized List<AiProfile> list() {
        List<AiProfile> result = new ArrayList<>();
        JSONArray array = document.optJSONArray("profiles");
        try {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                result.add(new AiProfile(item.getString("id"), item.getString("name"),
                        AiProfile.Kind.valueOf(item.getString("kind")),
                        AiProfile.Protocol.valueOf(item.getString("protocol")), item.getString("url"),
                        item.getString("model"), item.optString("credential", "")));
            }
        } catch (JSONException error) { throw new IllegalStateException("连接配置格式无效", error); }
        return Collections.unmodifiableList(result);
    }
    @Override public synchronized AiProfile active() {
        for (AiProfile profile : list()) {
            if (profile.id.equals(document.optString("active"))) return profile;
        }
        return null;
    }
    @Override public synchronized String contextBoundary() { return document.optString("boundary", ""); }
    @Override public synchronized long contextStartedAt() { return document.optLong("contextStart", 0); }
    @Override public synchronized AiProfile save(AiProfile draft, String replacementKey) throws Exception {
        URI uri = URI.create(draft.baseUrl);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("服务地址无效");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) AiTransportPolicy.privateHttpOrigin(draft.baseUrl);
        String id = draft.id.isEmpty() ? UUID.randomUUID().toString() : draft.id;
        String ref = draft.credentialRef;
        String oldRef = "";
        for (AiProfile value : list()) if (value.id.equals(id)) oldRef = value.credentialRef;
        if (!ref.isEmpty() && draft.kind != AiProfile.Kind.CHATGPT && !ref.startsWith("key:")) {
            throw new SecurityException("连接凭据类型不匹配");
        }
        if (draft.kind == AiProfile.Kind.CHATGPT && !ref.isEmpty() && !ref.startsWith("oauth:")) {
            throw new SecurityException("官方账号引用无效");
        }
        if (draft.kind != AiProfile.Kind.CHATGPT && replacementKey != null && !replacementKey.isEmpty()) {
            if (replacementKey.contains("\n") || replacementKey.contains("\r")) {
                throw new IllegalArgumentException("Key 包含非法换行");
            }
            ref = "key:" + UUID.randomUUID();
            credentials.write(ref, replacementKey);
        }
        AiProfile saved = draft.withIdentity(id, ref);
        List<AiProfile> values = new ArrayList<>(list());
        values.removeIf(value -> value.id.equals(id)); values.add(saved);
        JSONObject next = new JSONObject(document.toString());
        next.put("profiles", encode(values));
        if (id.equals(next.optString("active"))) next.put("boundary", UUID.randomUUID().toString())
                .put("contextStart", System.currentTimeMillis());
        try { commit(next); }
        catch (RuntimeException failure) {
            if (!ref.equals(draft.credentialRef)) releaseUnusedKey(ref);
            throw failure;
        }
        if (!oldRef.equals(ref)) releaseUnusedKey(oldRef);
        return saved;
    }
    @Override public synchronized void activate(String id) throws Exception {
        AiProfile target = find(id);
        if (!target.isComplete()) throw new IllegalArgumentException("请先选择模型并保存凭据");
        JSONObject next = new JSONObject(document.toString());
        next.put("active", id).put("boundary", UUID.randomUUID().toString())
                .put("contextStart", System.currentTimeMillis());
        commit(next);
    }
    @Override public synchronized void delete(String id) throws Exception {
        AiProfile target = find(id);
        List<AiProfile> values = new ArrayList<>(list()); values.removeIf(value -> value.id.equals(id));
        JSONObject next = new JSONObject(document.toString()).put("profiles", encode(values));
        if (id.equals(next.optString("active"))) {
            next.remove("active"); next.put("boundary", UUID.randomUUID().toString());
        }
        commit(next);
        releaseUnusedKey(target.credentialRef);
    }
    @Override public synchronized AiProfile duplicate(String id) throws Exception {
        AiProfile original = find(id);
        return save(new AiProfile("", original.name + " 副本", original.kind, original.protocol,
                original.baseUrl, original.model, ""), null);
    }
    public synchronized void migrate(String url, String model, String legacyKey) throws Exception {
        if (document.optBoolean("migrated")) return;
        if (active() == null && !list().isEmpty() && "legacy-default".equals(list().get(0).id)) {
            activate("legacy-default");
            commit(new JSONObject(document.toString()).put("contextStart", 0));
        }
        if (list().isEmpty() && legacyKey != null && !legacyKey.isEmpty()
                && url != null && !url.isEmpty() && model != null && !model.isEmpty()) {
            AiProfile legacy = save(new AiProfile("legacy-default", "原有连接",
                    "https://api.openai.com/v1".equals(url) ? AiProfile.Kind.OPENAI_KEY : AiProfile.Kind.RELAY,
                    AiProfile.Protocol.CHAT_COMPLETIONS, url, model, ""), legacyKey);
            activate(legacy.id);
            commit(new JSONObject(document.toString()).put("contextStart", 0));
        }
        commit(new JSONObject(document.toString()).put("migrated", true));
    }
    private AiProfile find(String id) {
        for (AiProfile value : list()) if (value.id.equals(id)) return value;
        throw new IllegalArgumentException("连接不存在");
    }
    private void releaseUnusedKey(String reference) {
        if (!reference.startsWith("key:")) return;
        for (AiProfile value : list()) if (reference.equals(value.credentialRef)) return;
        // Metadata is already committed. A cleanup failure must not report a failed save.
        try { credentials.remove(reference); } catch (Exception ignored) { }
    }
    private JSONArray encode(List<AiProfile> values) throws JSONException {
        JSONArray result = new JSONArray();
        for (AiProfile profile : values) result.put(new JSONObject().put("id", profile.id)
                .put("name", profile.name).put("kind", profile.kind.name())
                .put("protocol", profile.protocol.name()).put("url", profile.baseUrl)
                .put("model", profile.model).put("credential", profile.credentialRef));
        return result;
    }
    private void commit(JSONObject next) {
        if (!prefs.edit().putString("document", next.toString()).commit()) {
            throw new IllegalStateException("无法保存连接配置，原配置保持不变");
        }
        document = next;
    }
}
