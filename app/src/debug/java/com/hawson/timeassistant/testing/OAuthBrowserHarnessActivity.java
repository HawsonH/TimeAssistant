package com.hawson.timeassistant.testing;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.auth.AuthTabIntent;
import androidx.browser.customtabs.CustomTabsClient;
import com.hawson.timeassistant.auth.OAuthBrowser;
import java.io.Closeable;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Debug-only, generated loopback fixture. Never reads or exchanges an account grant. */
public final class OAuthBrowserHarnessActivity extends AppCompatActivity {
    public volatile boolean received, returned;
    public volatile String failure = "";
    private Closeable listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<Intent> launcher = AuthTabIntent.registerActivityResultLauncher(this, result -> {
        returned = result.resultCode == AuthTabIntent.RESULT_OK
                && OAuthBrowser.isReturnNavigation(result.resultUri, getPackageName());
        if (!returned) failure = "BROWSER_RESULT_" + result.resultCode;
    });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView status = new TextView(this); status.setText("本地浏览器返回验证"); setContentView(status);
        if (state != null || !getIntent().getBooleanExtra("localFixture", false)) return;
        try {
            if (!CustomTabsClient.isAuthTabSupported(this, "com.android.chrome")) {
                failure = "AUTH_TAB_UNAVAILABLE"; return;
            }
            Class<?> type = Class.forName("com.hawson.timeassistant.oauth.LoopbackAuthorization");
            Constructor<?> constructor = type.getDeclaredConstructor(String.class); constructor.setAccessible(true);
            listener = (Closeable) constructor.newInstance(getPackageName());
            Field redirect = type.getDeclaredField("redirectUri"); redirect.setAccessible(true);
            String target = (String) redirect.get(listener);
            Class<?> callbackType = Class.forName(type.getName() + "$Callback");
            Method await = type.getDeclaredMethod("await", String.class, callbackType); await.setAccessible(true);
            Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                    (proxy, method, args) -> {
                        if ("received".equals(method.getName())) { received = true; listener.close(); }
                        return null;
                    });
            worker.execute(() -> {
                try { await.invoke(listener, "fixture-state", callback); }
                catch (Exception failed) { failure = "LISTENER_" + failed.getClass().getSimpleName(); }
            });
            AuthTabIntent tab = new AuthTabIntent.Builder().build(); tab.intent.setPackage("com.android.chrome");
            tab.launch(launcher, Uri.parse(target + "?code=fixture-only&state=fixture-state"), getPackageName() + ".oauth");
        } catch (Exception failed) { failure = "SETUP_" + failed.getClass().getSimpleName(); }
    }
    @Override protected void onDestroy() {
        try { if (listener != null) listener.close(); } catch (Exception ignored) { }
        worker.shutdownNow(); super.onDestroy();
    }
}
