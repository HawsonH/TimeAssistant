package com.hawson.timeassistant.focus;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.hawson.timeassistant.domain.FocusState;

/** Wakes the app when a running focus segment is due, even while the device is asleep. */
public final class FocusCompletionAlarm {
    private static final int REQUEST_COMPLETE = 0xF0C20;

    private FocusCompletionAlarm() {}

    public static void schedule(Context context, FocusState state) {
        if (context == null) return;
        if (state == null || state.getState() != FocusState.State.RUNNING) {
            cancel(context);
            return;
        }
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        long now = System.currentTimeMillis();
        long triggerAt = triggerAtMillis(state, now);
        PendingIntent completion = pendingIntent(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !manager.canScheduleExactAlarms()) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, completion);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, completion);
        } else {
            manager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, completion);
        }
    }

    public static void cancel(Context context) {
        if (context == null) return;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) manager.cancel(pendingIntent(context));
    }

    static long triggerAtMillis(FocusState state, long now) {
        return now + Math.max(1L, state.remainingAt(now));
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, FocusCompletionReceiver.class)
                .setAction(FocusCompletionReceiver.ACTION_COMPLETE_FOCUS);
        return PendingIntent.getBroadcast(context, REQUEST_COMPLETE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
