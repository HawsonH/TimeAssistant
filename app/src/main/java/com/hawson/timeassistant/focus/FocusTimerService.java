/**
 * 专注前台计时服务
 *
 * 让专注或休息计时离开页面后仍由系统托管，并在通知栏与锁屏显示实时计时。
 */
package com.hawson.timeassistant.focus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.hawson.timeassistant.R;
import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.data.FocusSessionGateway;
import com.hawson.timeassistant.data.ReminderStateGateway;
import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.reminder.ReminderScheduler;
import com.hawson.timeassistant.ui.MainActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Maintains the active focus timer and its public lock-screen notification. */
public final class FocusTimerService extends Service {
    public static final String ACTION_FOCUS_UPDATED =
            "com.hawson.timeassistant.action.FOCUS_UPDATED";
    public static final String EXTRA_NOTIFICATION_ACTION = "focus_notification_action";
    public static final String NOTIFICATION_ACTION_CANCEL = "cancel";
    public static final String NOTIFICATION_ACTION_END = "end";

    private static final String ACTION_SYNC =
            "com.hawson.timeassistant.action.SYNC_FOCUS_TIMER";
    // Channel behavior is immutable after creation, so use a new id to migrate old low-priority
    // installs to a lock-screen-visible channel.
    /** System notification category used for the active timer, including its lock-screen policy. */
    // ColorOS suppresses foreground notifications explicitly marked SILENT on the lock screen.
    // A fresh high-importance channel also migrates devices that previously created v2.
    public static final String ACTIVE_NOTIFICATION_CHANNEL_ID = "active_focus_timer_v3";
    private static final int NOTIFICATION_ID = 0xF0C05;
    private static final int REQUEST_OPEN = 0xF0C10;
    private static final int REQUEST_CANCEL = 0xF0C12;
    private static final int REQUEST_END = 0xF0C13;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable finishRunnable = this::finishIfDue;
    private final ExecutorService focusIo = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "FocusTimerServiceIO");
        thread.setDaemon(true);
        return thread;
    });
    private FocusSessionGateway focusGateway;
    private ReminderStateGateway reminderGateway;
    private FocusTimerNotificationFactory notificationFactory;
    private int stateRequestVersion;

    public static void sync(Context context, FocusState state) {
        if (context == null) return;
        Context appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        Intent service = new Intent(appContext, FocusTimerService.class).setAction(ACTION_SYNC);
        if (state != null && state.getState() == FocusState.State.RUNNING) {
            ContextCompat.startForegroundService(appContext, service);
        } else {
            FocusCompletionAlarm.cancel(appContext);
            appContext.stopService(service);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        TimeAssistantApplication application = (TimeAssistantApplication) getApplication();
        focusGateway = application.getFocusSessionGateway();
        reminderGateway = application.getReminderStateGateway();
        notificationFactory = new FocusTimerNotificationFactory(this,
                ACTIVE_NOTIFICATION_CHANNEL_ID);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(finishRunnable);
        startForeground(NOTIFICATION_ID, buildPlaceholderNotification());
        int requestVersion = ++stateRequestVersion;
        focusIo.execute(() -> {
            try {
                FocusSessionGateway.FocusSnapshot snapshot = focusGateway.load();
                handler.post(() -> applyLoadedFocus(startId, requestVersion, snapshot));
            } catch (RuntimeException exception) {
                handler.post(() -> stopWithoutFocus(startId));
            }
        });
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(finishRunnable);
        focusIo.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void applyLoadedFocus(int startId, int requestVersion,
                                  FocusSessionGateway.FocusSnapshot snapshot) {
        if (requestVersion != stateRequestVersion) return;
        FocusState state = snapshot.getFocus();
        if (state.getState() != FocusState.State.RUNNING) {
            stopWithoutFocus(startId);
            return;
        }
        startForeground(NOTIFICATION_ID, buildNotification(state, snapshot.getSettings(),
                snapshot.getTask()));
        long remaining = state.remainingAt(System.currentTimeMillis());
        FocusCompletionAlarm.schedule(this, state);
        if (remaining <= 0) handler.post(finishRunnable);
        else handler.postDelayed(finishRunnable, remaining);
    }

    private void stopWithoutFocus(int startId) {
        FocusCompletionAlarm.cancel(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }

    private Notification buildPlaceholderNotification() {
        return notificationFactory.placeholder(PendingIntent.getActivity(this, REQUEST_OPEN,
                focusActivityIntent(), pendingFlags())).build();
    }

    private Notification buildNotification(FocusState state, AppSettings settings, TaskItem task) {
        Intent open = focusActivityIntent();
        PendingIntent contentIntent = PendingIntent.getActivity(this, REQUEST_OPEN, open,
                pendingFlags());
        PendingIntent cancelIntent = PendingIntent.getActivity(this, REQUEST_CANCEL,
                focusActivityIntent().putExtra(EXTRA_NOTIFICATION_ACTION,
                        NOTIFICATION_ACTION_CANCEL), pendingFlags());
        PendingIntent endIntent = PendingIntent.getActivity(this, REQUEST_END,
                focusActivityIntent().putExtra(EXTRA_NOTIFICATION_ACTION,
                        NOTIFICATION_ACTION_END), pendingFlags());

        return notificationFactory.active(state, settings, task, contentIntent, cancelIntent,
                endIntent).build();
    }

    private void finishIfDue() {
        int requestVersion = ++stateRequestVersion;
        focusIo.execute(() -> {
            try {
                FocusSessionGateway.CompletionSnapshot completion =
                        focusGateway.completeIfDue(System.currentTimeMillis());
                ReminderStateGateway.ReminderSnapshot reminder = reminderGateway.load();
                handler.post(() -> applyCompletion(requestVersion, completion, reminder));
            } catch (RuntimeException exception) {
                handler.post(() -> stopWithoutFocus(0));
            }
        });
    }

    private void applyCompletion(int requestVersion,
                                 FocusSessionGateway.CompletionSnapshot completionSnapshot,
                                 ReminderStateGateway.ReminderSnapshot reminderSnapshot) {
        if (requestVersion != stateRequestVersion) return;
        FocusSessionGateway.FocusSnapshot snapshot = completionSnapshot.getSnapshot();
        if (completionSnapshot.getCompletion().wasSettledNow()
                && completionSnapshot.getCompletion().getCompletedState() != null) {
            FocusStageSignal.play(this, snapshot.getSettings(),
                    completionSnapshot.getCompletion().getCompletedState());
        }
        ReminderScheduler.refresh(this, reminderSnapshot.getTasks(), reminderSnapshot.getSettings(),
                reminderSnapshot.getFocus());
        sendFocusUpdated();
        FocusState current = snapshot.getFocus();
        if (current.getState() == FocusState.State.RUNNING) {
            startForeground(NOTIFICATION_ID, buildNotification(current, snapshot.getSettings(),
                    snapshot.getTask()));
            FocusCompletionAlarm.schedule(this, current);
            handler.postDelayed(finishRunnable,
                    Math.max(1L, current.remainingAt(System.currentTimeMillis())));
            return;
        }
        stopWithoutFocus(0);
    }

    private Intent focusActivityIntent() {
        return new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(ReminderScheduler.EXTRA_REMINDER_KIND, ReminderScheduler.KIND_FOCUS);
    }

    private void sendFocusUpdated() {
        sendBroadcast(new Intent(ACTION_FOCUS_UPDATED).setPackage(getPackageName()));
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                ACTIVE_NOTIFICATION_CHANNEL_ID, "正在进行的专注",
                // Keep the persistent timer visible without interrupting the active planning flow.
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("在通知栏和锁屏显示当前专注或休息计时");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }

    private static int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }
}
