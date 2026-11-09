package com.hawson.timeassistant.settings;

import androidx.lifecycle.*;
import com.hawson.timeassistant.connections.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Secret drafts survive configuration changes in memory, never through a saved Bundle. */
public final class AiSettingsViewModel extends ViewModel {
    final AiProfilesPort profiles;
    final AiAccountPort accounts;
    final AiSettingsPort settings;
    final MutableLiveData<String> notice = new MutableLiveData<>();
    final MutableLiveData<AiAccountPort.Account> signedIn = new MutableLiveData<>();
    AiProfile editing;
    String key = "", name = "", url = "", model = "";
    AiProfile.Protocol protocol = AiProfile.Protocol.CHAT_COMPLETIONS;
    boolean dirty, busy;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private AiCancellation cancellation = new AiCancellation();
    private long version;

    public AiSettingsViewModel(AiProfilesPort profiles, AiAccountPort accounts, AiSettingsPort settings) {
        this.profiles = profiles; this.accounts = accounts; this.settings = settings;
    }
    void edit(AiProfile value) {
        cancel(); editing = value; key = ""; name = value.name; url = value.baseUrl;
        model = value.model; protocol = value.protocol; dirty = false;
        notice.setValue(value.credentialRef.isEmpty() ? "尚未保存凭据" : "已配置，尚未测试");
    }
    void changed() { dirty = true; cancel(); notice.setValue("有未保存的修改"); }
    AiProfile draft() {
        if (editing == null) throw new IllegalStateException("未选择连接");
        String normalized = url.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        for (String suffix : new String[]{"/chat/completions", "/responses", "/models"}) {
            if (normalized.endsWith(suffix)) normalized = normalized.substring(0, normalized.length() - suffix.length());
        }
        return new AiProfile(editing.id, name, editing.kind, protocol, normalized, model, editing.credentialRef);
    }
    <T> void run(Callable<T> work, Consumer<T> success) {
        runCancellable(ignored -> work.call(), success);
    }
    interface Work<T> { T call(AiCancellation cancellation) throws Exception; }
    <T> void runCancellable(Work<T> work, Consumer<T> success) {
        cancel(); busy = true; notice.setValue("处理中…");
        long operation = version;
        AiCancellation current = cancellation;
        executor.execute(() -> {
            try {
                current.check();
                T result = work.call(current);
                main.post(() -> {
                    if (version != operation) return;
                    busy = false; success.accept(result);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (version != operation) return;
                    busy = false;
                    notice.setValue(error instanceof java.io.IOException ? error.getMessage()
                            : error instanceof IllegalArgumentException ? error.getMessage()
                            : "操作未完成，请检查配置、授权与网络后重试");
                });
            }
        });
    }
    AiCancellation cancellation() { return cancellation; }
    void cancel() { version++; cancellation.cancel(); cancellation = new AiCancellation(); busy = false; }
    boolean hasPendingSignIn() { return accounts.hasPendingSignIn(); }
    String login(String reference) throws Exception {
        return accounts.beginSignIn(reference, loginListener());
    }
    void completePendingSignIn(String callbackUri) throws Exception {
        accounts.completePendingSignIn(callbackUri, loginListener());
    }
    private AiAccountPort.Listener loginListener() {
        return new AiAccountPort.Listener() {
            public void finished(AiAccountPort.Account account) {
                if (editing == null)
                    notice.setValue(account.authorized ? "官方授权完成" : "已登录，但未获得计划使用权限");
                signedIn.setValue(account);
            }
            public void failed(String safe) { if (editing == null) notice.setValue(safe); }
        };
    }
    @Override protected void onCleared() { cancel(); executor.shutdownNow(); }
}
