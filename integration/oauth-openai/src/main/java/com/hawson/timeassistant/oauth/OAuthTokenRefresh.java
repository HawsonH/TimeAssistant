package com.hawson.timeassistant.oauth;

import com.hawson.timeassistant.connections.*;
import java.util.*;
import org.json.JSONObject;

/** Serializes rotating grants and keeps successful replacements staged if a disk write fails. */
final class OAuthTokenRefresh {
    interface Exchange { JSONObject refresh(JSONObject saved, AiCancellation cancel) throws Exception; }
    interface Verify { void check(JSONObject replacement) throws Exception; }
    static final class Failure extends Exception {
        final String code;
        Failure(String code) { this.code = code; }
    }
    private final AiCredentialsPort store;
    private final Object lock;
    private final Exchange exchange;
    private final Verify verify;
    private final Map<String, JSONObject> staged = new HashMap<>();
    OAuthTokenRefresh(AiCredentialsPort store, Object lock, Exchange exchange) {
        this(store, lock, exchange, replacement -> { });
    }
    OAuthTokenRefresh(AiCredentialsPort store, Object lock, Exchange exchange, Verify verify) {
        this.store = store; this.lock = lock; this.exchange = exchange; this.verify = verify;
    }
    String resolve(AiProfile profile, String rejected, AiCancellation cancel) throws Exception {
        synchronized (lock) {
            cancel.check();
            if (profile.kind != AiProfile.Kind.CHATGPT || !"https://api.openai.com/v1".equals(profile.baseUrl)) {
                throw new SecurityException("官方令牌禁止用于非官方连接");
            }
            String ref = profile.credentialRef;
            String raw = store.read(ref);
            if (raw == null) throw loginRequired();
            JSONObject saved = new JSONObject(raw);
            if (saved.optString("access").isEmpty()) throw loginRequired();
            String pending = store.read(pendingKey(ref));
            JSONObject replacement = staged.get(ref);
            if (replacement == null && pending != null) replacement = new JSONObject(pending);
            if (replacement != null) {
                if (!saved.optString("access").equals(replacement.optString("access"))) commit(ref, replacement);
                else { staged.remove(ref); store.remove(pendingKey(ref)); }
                saved = new JSONObject(store.read(ref));
            }
            if (!permitted(saved)) throw new AiConnectionException(AiConnectionException.Status.PERMISSION_DENIED,
                    "尚未授予 ChatGPT 计划使用权限，请补充授权");
            boolean expired = saved.optLong("expires") <= System.currentTimeMillis() + 60_000;
            boolean rejectedCurrent = rejected != null && rejected.equals(saved.optString("access"));
            if (!expired && !rejectedCurrent) return saved.getString("access");
            if (saved.optString("refresh").isEmpty()) throw loginRequired();
            JSONObject next;
            try { next = exchange.refresh(new JSONObject(saved.toString()), cancel); }
            catch (Failure failed) {
                if (terminal(failed.code)) {
                    saved.remove("access"); saved.remove("refresh"); saved.remove("scope");
                    store.write(ref, saved.toString());
                    throw loginRequired();
                }
                throw new AiConnectionException("invalid_client".equals(failed.code)
                        ? AiConnectionException.Status.FAILED : AiConnectionException.Status.UNAVAILABLE,
                        "invalid_client".equals(failed.code) ? "授权客户端配置未被接受，请检查连接"
                                : "暂时无法续期，登录信息已保留，请稍后重试");
            }
            commit(ref, next);
            cancel.check();
            if (!permitted(next)) throw new AiConnectionException(AiConnectionException.Status.PERMISSION_DENIED,
                    "ChatGPT 计划权限已撤销，请补充授权");
            return next.getString("access");
        }
    }
    private void commit(String ref, JSONObject replacement) throws Exception {
        staged.put(ref, replacement);
        store.write(pendingKey(ref), replacement.toString());
        verify.check(replacement);
        replacement.remove("verifyRefreshIdentity");
        store.write(ref, replacement.toString());
        staged.remove(ref);
        store.remove(pendingKey(ref));
    }
    private static String pendingKey(String ref) { return "registration:refresh:" + ref; }
    void forget(String reference) throws Exception {
        synchronized (lock) { staged.remove(reference); store.remove(pendingKey(reference)); }
    }
    static boolean permitted(JSONObject record) {
        return Arrays.asList(record.optString("scope").split(" ")).contains("chatgpt.tokens.use.direct");
    }
    private static boolean terminal(String code) {
        return Arrays.asList("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
                "refresh_token_invalidated", "refresh_token_reused").contains(code);
    }
    private static AiConnectionException loginRequired() {
        return new AiConnectionException(AiConnectionException.Status.LOGIN_REQUIRED,
                "登录已失效，请重新授权 ChatGPT");
    }
}
