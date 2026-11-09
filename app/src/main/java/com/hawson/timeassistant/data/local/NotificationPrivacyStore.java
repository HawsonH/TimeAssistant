package com.hawson.timeassistant.data.local;

import android.content.Context;
import android.content.SharedPreferences;

/** Notification text is redacted before building any compact, expanded or fallback view. */
public final class NotificationPrivacyStore {
    private final SharedPreferences preferences;

    public NotificationPrivacyStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences("notification_privacy", Context.MODE_PRIVATE);
    }

    public boolean hideTaskContent() { return preferences.getBoolean("hide_task_content", true); }
    public void setHideTaskContent(boolean hide) {
        preferences.edit().putBoolean("hide_task_content", hide).apply();
    }
}
