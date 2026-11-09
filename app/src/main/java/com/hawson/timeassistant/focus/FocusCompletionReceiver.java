package com.hawson.timeassistant.focus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.data.FocusSessionGateway;
import com.hawson.timeassistant.data.ReminderStateGateway;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.reminder.ReminderScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Settles an elapsed focus segment after AlarmManager wakes the sleeping device. */
public final class FocusCompletionReceiver extends BroadcastReceiver {
    static final String ACTION_COMPLETE_FOCUS =
            "com.hawson.timeassistant.action.COMPLETE_FOCUS_TIMER";
    private static final ExecutorService RECEIVER_IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "FocusCompletionReceiverIO");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_COMPLETE_FOCUS.equals(intent.getAction())) return;
        PendingResult pendingResult = goAsync();
        Context appContext = context.getApplicationContext() == null ? context
                : context.getApplicationContext();
        RECEIVER_IO.execute(() -> {
            try {
                handleDue(appContext);
            } finally {
                pendingResult.finish();
            }
        });
    }

    /** Must be called from a receiver, worker, or service background executor. */
    public static void handleDue(Context context) {
        if (context == null) return;
        TimeAssistantApplication application =
                (TimeAssistantApplication) context.getApplicationContext();
        FocusSessionGateway focusGateway = application.getFocusSessionGateway();
        ReminderStateGateway reminderGateway = application.getReminderStateGateway();
        FocusSessionGateway.CompletionSnapshot settled =
                focusGateway.completeIfDue(System.currentTimeMillis());
        AppRepository.FocusCompletion completion = settled.getCompletion();
        if (completion.wasSettledNow() && completion.getCompletedState() != null) {
            FocusStageSignal.play(context, settled.getSnapshot().getSettings(),
                    completion.getCompletedState());
        }
        ReminderStateGateway.ReminderSnapshot reminder = reminderGateway.load();
        FocusState current = reminder.getFocus();
        ReminderScheduler.refresh(context, reminder.getTasks(), reminder.getSettings(), current);
        FocusTimerService.sync(context, current);
        context.sendBroadcast(new Intent(FocusTimerService.ACTION_FOCUS_UPDATED)
                .setPackage(context.getPackageName()));
    }
}
