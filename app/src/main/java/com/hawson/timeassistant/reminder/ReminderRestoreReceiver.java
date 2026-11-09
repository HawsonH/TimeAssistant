/**
 * 开机恢复提醒
 *
 * 设备重启后 Android 会清除闹钟，本接收器在 BOOT_COMPLETED 时重新注册全部提醒。
 */
package com.hawson.timeassistant.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.data.ReminderStateGateway;
import com.hawson.timeassistant.focus.FocusCompletionAlarm;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Restores persisted reminders after Android clears alarms during a device reboot. */
public final class ReminderRestoreReceiver extends BroadcastReceiver {
    private static final ExecutorService RECEIVER_IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ReminderRestoreReceiverIO");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_TIME_CHANGED.equals(action) && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                && !"android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action)) return;

        PendingResult pendingResult = goAsync();
        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;
        Context finalAppContext = appContext;
        RECEIVER_IO.execute(() -> {
            try {
                TimeAssistantApplication application =
                        (TimeAssistantApplication) finalAppContext.getApplicationContext();
                ReminderScheduler.invalidate(finalAppContext);
                application.getReminderCoordinator().requestRefresh();
                ReminderStateGateway.ReminderSnapshot reminder =
                        application.getReminderStateGateway().load();
                ReminderScheduler.refresh(finalAppContext, reminder.getTasks(),
                        reminder.getSettings(), reminder.getFocus());
                FocusCompletionAlarm.schedule(finalAppContext, reminder.getFocus());
            } finally {
                pendingResult.finish();
            }
        });
    }
}
