package com.hawson.timeassistant.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.focus.FocusCompletionReceiver;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** System envelope only; the coordinator owns validation and delivery. */
public final class ReminderReceiver extends BroadcastReceiver {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ReminderScheduler.ACTION_TASK_REMINDER.equals(intent.getAction())) return;
        String kind = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_KIND);
        boolean focus = ReminderScheduler.KIND_FOCUS.equals(kind);
        long target = intent.getLongExtra(ReminderScheduler.EXTRA_TRIGGER_AT, 0);
        if (!focus && target <= 0) return;
        PendingResult pending = goAsync();
        TimeAssistantApplication app = (TimeAssistantApplication) context.getApplicationContext();
        IO.execute(() -> {
            try {
                if (focus) FocusCompletionReceiver.handleDue(context);
                else app.getReminderCoordinator().receive(intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID), kind, target);
            }
            finally { pending.finish(); }
        });
    }
}
