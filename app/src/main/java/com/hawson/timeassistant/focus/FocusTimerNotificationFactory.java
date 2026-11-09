package com.hawson.timeassistant.focus;

import android.app.PendingIntent;
import android.content.Context;
import android.os.SystemClock;
import android.widget.RemoteViews;

import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;

import com.hawson.timeassistant.R;
import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.data.local.NotificationPrivacyStore;

/** Creates the time-first foreground notification used while a focus session is active. */
final class FocusTimerNotificationFactory {
    static final class TimerSpec {
        private final long baseElapsedRealtime;
        private final boolean countDown;

        TimerSpec(long baseElapsedRealtime, boolean countDown) {
            this.baseElapsedRealtime = baseElapsedRealtime;
            this.countDown = countDown;
        }

        long getBaseElapsedRealtime() {
            return baseElapsedRealtime;
        }

        boolean isCountDown() {
            return countDown;
        }
    }

    private final Context context;
    private final String channelId;

    FocusTimerNotificationFactory(Context context, String channelId) {
        this.context = context;
        this.channelId = channelId;
    }

    NotificationCompat.Builder placeholder(PendingIntent contentIntent) {
        return baseBuilder(contentIntent)
                .setContentTitle("专注计时中")
                .setContentText("正在恢复计时状态");
    }

    NotificationCompat.Builder active(FocusState state, AppSettings settings, TaskItem task,
                                      PendingIntent contentIntent, PendingIntent cancelIntent,
                                      PendingIntent endIntent) {
        long remaining = state.remainingAt(System.currentTimeMillis());
        String mode = modeLabel(state.getMode());
        boolean hideContent = new NotificationPrivacyStore(context).hideTaskContent();
        String taskTitle = visibleTaskTitle(hideContent,
                task == null ? "" : task.getTitle());
        TimerSpec timer = settings.isFocusCountUpEnabled()
                ? countUpTimer(SystemClock.elapsedRealtime(), state.getTotalMillis() - remaining)
                : countDownTimer(SystemClock.elapsedRealtime(), remaining);

        RemoteViews compact = timerView(R.layout.notification_focus_timer_compact, timer);
        compact.setTextViewText(R.id.focus_notification_state, mode + "中");

        RemoteViews expanded = timerView(R.layout.notification_focus_timer_expanded, timer);
        expanded.setTextViewText(R.id.focus_notification_state, mode + "进行中");
        expanded.setTextViewText(R.id.focus_notification_task,
                hideContent ? "任务内容已隐藏" : taskTitle.isEmpty() ? "未关联任务" : taskTitle);

        NotificationCompat.Builder builder = baseBuilder(contentIntent)
                // This remains the fallback when a vendor re-templates custom notification views.
                .setContentTitle(taskTitle.isEmpty() ? mode + "计时中" : taskTitle)
                .setContentText(mode + "进行中")
                // ColorOS places subtext in its decorated notification header. It replaces the
                // redundant end-time shown beside the app name while leaving the Chronometer live.
                .setSubText(taskTitle.isEmpty() ? null : taskTitle)
                .setCustomContentView(compact)
                .setCustomBigContentView(expanded)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .addAction(0, "取消", cancelIntent)
                .addAction(0, "结束", endIntent);
        // ColorOS renders a builder-level Chronometer in the decorated header, which duplicates
        // the large RemoteViews timer. The RemoteViews Chronometer remains the only live timer.
        return builder;
    }

    private NotificationCompat.Builder baseBuilder(PendingIntent contentIntent) {
        return new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification_time_isle)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(contentIntent);
    }

    private RemoteViews timerView(int layoutId, TimerSpec timer) {
        RemoteViews views = new RemoteViews(context.getPackageName(), layoutId);
        views.setChronometer(R.id.focus_notification_timer, timer.getBaseElapsedRealtime(), null,
                true);
        views.setChronometerCountDown(R.id.focus_notification_timer, timer.isCountDown());
        return views;
    }

    @VisibleForTesting
    static String visibleTaskTitle(boolean hideContent, String title) {
        return hideContent || title == null ? "" : title.trim();
    }

    @VisibleForTesting
    static TimerSpec countDownTimer(long elapsedRealtime, long remainingMillis) {
        return new TimerSpec(elapsedRealtime + Math.max(0L, remainingMillis), true);
    }

    @VisibleForTesting
    static TimerSpec countUpTimer(long elapsedRealtime, long elapsedMillis) {
        return new TimerSpec(elapsedRealtime - Math.max(0L, elapsedMillis), false);
    }

    private static String modeLabel(FocusState.Mode mode) {
        if (mode == FocusState.Mode.SHORT_BREAK) return "短休";
        if (mode == FocusState.Mode.LONG_BREAK) return "长休";
        return "专注";
    }
}
