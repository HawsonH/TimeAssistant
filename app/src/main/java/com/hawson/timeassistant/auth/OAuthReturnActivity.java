package com.hawson.timeassistant.auth;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.connections.AiAccountPort;
import com.hawson.timeassistant.ui.MainActivity;

/** Receives the validated browser redirect and hands it to the OAuth extension without exposing it to UI state. */
public final class OAuthReturnActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Uri uri = getIntent().getData();
        ((TimeAssistantApplication) getApplication()).getAiServices().accounts.resumeSignIn();
        String callback = callbackFrom(uri);
        if (callback != null) {
            try {
                ((TimeAssistantApplication) getApplication()).getAiServices().accounts.completePendingSignIn(
                        callback, new AiAccountPort.Listener() {
                            public void finished(AiAccountPort.Account account) { }
                            public void failed(String safeMessage) { }
                        });
            } catch (Exception ignored) {
                // The settings screen reports only a safe, state-derived result.
            }
        }
        getIntent().setData(null);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(OAuthSignInService.EXTRA_OPEN_SIGN_IN, true));
        finish();
    }
    private String callbackFrom(Uri uri) {
        if (uri == null || !(getPackageName() + ".oauth").equals(uri.getScheme())
                || !"return".equals(uri.getHost()) || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())
                || uri.getQueryParameterNames().size() != 1
                || !uri.getQueryParameterNames().contains("callback")) return null;
        java.util.List<String> values = uri.getQueryParameters("callback");
        return values.size() == 1 && !values.get(0).isEmpty() ? values.get(0) : null;
    }
}
