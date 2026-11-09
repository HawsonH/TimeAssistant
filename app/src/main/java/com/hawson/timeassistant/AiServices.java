package com.hawson.timeassistant;

import android.content.Context;
import com.hawson.timeassistant.ai.AiClient;
import com.hawson.timeassistant.compatible.CompatibleAiPort;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.data.SecureApiKeyStore;
import com.hawson.timeassistant.data.connections.*;
import com.hawson.timeassistant.data.local.AiEndpointTrustStore;
import com.hawson.timeassistant.network.AiHttp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Composition root for AI. Secrets and transport implementations never reach page constructors. */
public final class AiServices implements AiPort, AiSettingsPort {
    public final AiProfilesPort profiles;
    public final AiOAuthExtension accounts;
    public final AiUsageStore usage;
    private final AiCredentialsPort credentials;
    private final AiEndpointTrustStore trust;
    private final AiPort compatible;
    private final Set<AiCancellation> requests = ConcurrentHashMap.newKeySet();
    private final Object requestLock = new Object();
    private long requestEpoch;
    private final java.util.concurrent.ExecutorService recoveryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Map<String, AiCancellation> recoveries = new ConcurrentHashMap<>();
    private final Set<String> useAfterRecovery = ConcurrentHashMap.newKeySet();
    private final Set<Runnable> connectionListeners = new java.util.concurrent.CopyOnWriteArraySet<>();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile String recoveryNotice = "";
    public final String migrationError;
    private final Context context;

    public AiServices(Context context, String legacyUrl, String legacyModel, String legacyPlainKey) {
        this.context = context.getApplicationContext();
        credentials = new EncryptedCredentialStore(context);
        usage = new AiUsageStore(context);
        trust = new AiEndpointTrustStore(context);
        LocalAiProfiles local = new LocalAiProfiles(context, credentials);
        profiles = local;
        String error = null;
        try {
            SecureApiKeyStore legacy = new SecureApiKeyStore(context);
            String key = legacy.load();
            if ((key == null || key.isEmpty()) && legacyPlainKey != null && !legacyPlainKey.isEmpty()) {
                legacy.save(legacyPlainKey); key = legacyPlainKey;
            }
            local.migrate(legacyUrl, legacyModel, key);
        }
        catch (Exception failed) { error = "旧连接迁移未完成，原配置与密钥已保留"; }
        migrationError = error;
        accounts = OAuthModule.create(context, credentials);
        compatible = new CompatibleAiPort(new AiHttp(trust.policy()), new AiBearerPort() {
            public String resolve(AiProfile profile) throws Exception { return bearer(profile, new AiCancellation()); }
            public String resolve(AiProfile profile, AiCancellation cancel) throws Exception { return bearer(profile, cancel); }
            public String refreshAfterRejection(AiProfile profile, String rejected, AiCancellation cancel) throws Exception {
                return profile.kind == AiProfile.Kind.CHATGPT ? accounts.refreshAfterRejection(profile, rejected, cancel) : null;
            }
        }, usage);
        accounts.setAccountListener(account -> {
            if (account.authorized) prepareAccount(account.reference, false);
            else {
                AiCancellation pending = recoveries.get(account.reference);
                if (pending != null) pending.cancel();
                recoveryNotice = "账号需要重新登录或补充授权";
                notifyConnectionsChanged();
            }
        });
        recoverSavedAccount();
    }
    private String bearer(AiProfile profile, AiCancellation cancel) throws Exception {
        if (profile.kind == AiProfile.Kind.CHATGPT) {
            try { return accounts.resolve(profile, cancel); }
            catch (AiConnectionException failure) { reportConnectionFailure(profile, failure); throw failure; }
        }
        if (!profile.credentialRef.startsWith("key:")) throw new SecurityException("此连接缺少独立 API Key");
        return credentials.read(profile.credentialRef);
    }
    public boolean configured() {
        AiProfile profile = profiles.active();
        if (profile == null || !profile.isComplete()) return false;
        if (profile.kind != AiProfile.Kind.CHATGPT) return true;
        for (AiAccountPort.Account account : accounts.accounts()) {
            if (account.reference.equals(profile.credentialRef)) return account.authorized;
        }
        return false;
    }
    public AiClient client() {
        AiProfile profile = profiles.active();
        if (profile == null) throw new IllegalStateException("请先选择 AI 连接");
        final long epoch;
        synchronized (requestLock) { epoch = requestEpoch; }
        AiPort scoped = new AiPort() {
            public String complete(AiProfile target, AiRequest input, AiCancellation cancel) throws Exception {
                synchronized (requestLock) {
                    if (epoch != requestEpoch) throw new IllegalStateException("连接已切换，请重新发送");
                    requests.add(cancel);
                }
                try {
                    String result = callCompatible(target, input, cancel);
                    cancel.check(); return result;
                } finally { requests.remove(cancel); }
            }
            public List<String> models(AiProfile target, AiCancellation cancel) throws Exception {
                synchronized (requestLock) {
                    if (epoch != requestEpoch) throw new IllegalStateException("连接已切换，请重新获取");
                    requests.add(cancel);
                }
                try { return compatible.models(target, cancel); }
                finally { requests.remove(cancel); }
            }
        };
        return new AiClient(scoped, profile);
    }
    public void cancelAll() {
        for (AiCancellation recovery : recoveries.values()) recovery.cancel();
        cancelInference();
    }
    private void cancelInference() {
        synchronized (requestLock) {
            requestEpoch++;
            for (AiCancellation request : requests) request.cancel();
            requests.clear();
        }
    }
    private void recoverSavedAccount() {
        List<AiAccountPort.Account> saved = new ArrayList<>();
        AiProfile active = profiles.active();
        for (AiAccountPort.Account account : accounts.accounts()) {
            if (!account.authorized) continue;
            if (active != null && account.reference.equals(active.credentialRef)) {
                prepareAccount(account.reference, false); return;
            }
            saved.add(account);
        }
        if (saved.size() == 1) prepareAccount(saved.get(0).reference, false);
        else if (saved.size() > 1 && (active == null || !active.isComplete())) {
            recoveryNotice = "请选择要使用的 ChatGPT 账号";
        }
    }
    @Override public void recoverAccount(String reference) {
        AiCancellation pending = recoveries.get(reference);
        if (pending != null) pending.cancel();
        // Queue after an interrupted startup recovery has released its account slot.
        recoveryExecutor.execute(() -> prepareAccount(reference, true));
    }
    private void prepareAccount(String reference, boolean use) {
        if (use) useAfterRecovery.add(reference);
        AiCancellation cancel = new AiCancellation();
        if (recoveries.putIfAbsent(reference, cancel) != null) return;
        recoveryNotice = "正在准备 ChatGPT 连接…"; notifyConnectionsChanged();
        recoveryExecutor.execute(() -> {
            try {
                AiAccountPort.Account selected = null;
                for (AiAccountPort.Account account : accounts.accounts()) {
                    if (account.reference.equals(reference) && account.authorized) selected = account;
                }
                if (selected == null) throw new AiConnectionException(AiConnectionException.Status.LOGIN_REQUIRED,
                        "请先授权此 ChatGPT 账号");
                AiProfile ready = new AiConnectionRecovery(profiles, compatible, this::cancelInference).recover(selected, cancel);
                cancel.check();
                AiProfile active = profiles.active();
                if (useAfterRecovery.remove(reference) && (active == null || !ready.id.equals(active.id))) {
                    cancelInference(); profiles.activate(ready.id);
                }
                active = profiles.active();
                recoveryNotice = active != null && active.id.equals(ready.id)
                        ? "ChatGPT 连接已就绪" : "ChatGPT 连接已准备好，可选择使用此账号";
            } catch (Exception failure) {
                try {
                    cancel.check();
                    recoveryNotice = failure instanceof AiConnectionException ? failure.getMessage()
                            : "账号已保留，连接准备未完成，请重试获取模型";
                } catch (java.io.InterruptedIOException cancelled) { recoveryNotice = "连接准备已取消，可重试"; }
            } finally {
                useAfterRecovery.remove(reference);
                recoveries.remove(reference, cancel); notifyConnectionsChanged();
            }
        });
    }
    @Override public String connectionNotice() { return recoveryNotice; }
    @Override public void addConnectionListener(Runnable listener) { connectionListeners.add(listener); }
    @Override public void removeConnectionListener(Runnable listener) { connectionListeners.remove(listener); }
    private void notifyConnectionsChanged() {
        main.post(() -> { for (Runnable listener : connectionListeners) listener.run(); });
    }
    public void clearAllLocal() throws Exception {
        cancelAll(); accounts.clearLocal();
        for (AiProfile profile : new ArrayList<>(profiles.list())) profiles.delete(profile.id);
        for (String reference : new HashSet<>(credentials.references())) credentials.remove(reference);
        trust.revokeAll();
        usage.clear();
        context.getSharedPreferences("ai_auxiliary_v1", 0).edit().clear().commit();
        context.getSharedPreferences("ai_model_names", 0).edit().clear().commit();
    }
    @Override public String complete(AiProfile profile, AiRequest request, AiCancellation cancel) throws Exception {
        requests.add(cancel);
        try {
            String result = callCompatible(profile, request, cancel);
            cancel.check(); return result;
        } finally { requests.remove(cancel); }
    }
    private String callCompatible(AiProfile profile, AiRequest request, AiCancellation cancel) throws Exception {
        try { return compatible.complete(profile, request, cancel); }
        catch (AiConnectionException failure) { reportConnectionFailure(profile, failure); throw failure; }
    }
    private void reportConnectionFailure(AiProfile profile, AiConnectionException failure) {
        if (profile.kind != AiProfile.Kind.CHATGPT) return;
        recoveryNotice = failure.getMessage(); notifyConnectionsChanged();
    }
    @Override public List<String> models(AiProfile profile, AiCancellation cancel) throws Exception {
        return compatible.models(profile, cancel);
    }
    @Override public List<AiModelOption> modelOptions(AiProfile profile, AiCancellation cancel) throws Exception {
        return compatible.modelOptions(profile, cancel);
    }
    @Override public List<AiModelOption> modelOptions(AiProfile draft, String key, AiCancellation cancel) throws Exception {
        return draftPort(draft, key).modelOptions(draft, cancel);
    }
    private AiPort draftPort(AiProfile profile, String key) {
        if (profile.kind == AiProfile.Kind.CHATGPT || key == null || key.isEmpty()) return this;
        return new CompatibleAiPort(new AiHttp(trust.policy()), ignored -> key);
    }
    @Override public String test(AiProfile draft, String key, AiCancellation cancellation) throws Exception {
        long start = System.nanoTime();
        String text = draftPort(draft, key).complete(draft,
                new AiRequest("Connection test. Reply exactly OK.", Collections.singletonList(
                        new AiRequest.Message("user", "OK", Collections.emptyList()))), cancellation);
        if (text.trim().isEmpty()) throw new IllegalStateException("模型返回空内容");
        return "连接可用 · " + (System.nanoTime() - start) / 1_000_000 + " ms";
    }
    @Override public List<String> models(AiProfile draft, String key, AiCancellation cancellation) throws Exception {
        return draftPort(draft, key).models(draft, cancellation);
    }
    @Override public void requireAllowed(String url) { trust.policy().requireAllowed(url); }
    @Override public void approvePrivateHttp(String url) { trust.approve(url); }
    @Override public void revokePrivateHttp() { cancelAll(); trust.revokeAll(); }
}
