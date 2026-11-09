package com.hawson.timeassistant.auth;

import android.content.Intent;
import android.net.Uri;
import androidx.activity.ComponentActivity;

/** Uses a separate browser task so a failed loopback page remains available for local recovery. */
public final class OAuthBrowser {
    private static volatile long launchedAt;
    private final ComponentActivity activity;

    public OAuthBrowser(ComponentActivity activity) {
        this.activity = activity;
    }

    public boolean launch(Uri authorization) {
        Intent browser = externalAuthorizationIntent(authorization);
        if (browser == null) return false;
        activity.startActivity(browser);
        launchedAt = android.os.SystemClock.elapsedRealtime();
        return true;
    }

    public static Intent externalAuthorizationIntent(Uri authorization) {
        if (authorization == null || !"https".equals(authorization.getScheme())
                || !"auth.openai.com".equals(authorization.getHost())
                || authorization.getUserInfo() != null || authorization.getPort() != -1
                || !"/api/accounts/authorize".equals(authorization.getPath())
                || authorization.getFragment() != null) return null;
        return new Intent(Intent.ACTION_VIEW, authorization).addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Public state only: never includes the authorization URI or result URI. */
    public static String returnDiagnostics() {
        return "browser_transport=external;browser_launched=" + (launchedAt > 0);
    }

    public static boolean isReturnNavigation(Uri uri, String packageName) {
        return uri != null && (packageName + ".oauth").equals(uri.getScheme())
                && "return".equals(uri.getHost()) && uri.getPort() == -1 && uri.getUserInfo() == null
                && (uri.getPath() == null || uri.getPath().isEmpty())
                && uri.getQuery() == null && uri.getFragment() == null;
    }
}
