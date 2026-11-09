package com.hawson.timeassistant.integration.calendar;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

/** Single source of truth for whether calendar tools may be exposed. */
public final class CalendarAccessPolicy {
    private CalendarAccessPolicy() {}

    public static boolean canRead(Context context, boolean userEnabled) {
        return userEnabled
                && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean canCompose(Context context, boolean userEnabled) {
        return canRead(context, userEnabled);
    }
}
