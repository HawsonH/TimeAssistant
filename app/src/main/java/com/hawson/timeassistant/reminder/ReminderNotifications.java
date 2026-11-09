package com.hawson.timeassistant.reminder;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.domain.ReminderEvent;
import com.hawson.timeassistant.ui.MainActivity;

/** System delivery is independent of the persisted business event. */
public final class ReminderNotifications {
    public static final String CHANNEL_ID = "task_reminders";
    private ReminderNotifications() { }

    public static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "任务与日程提醒", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("待办定时、日程、截止与每日复盘提醒"); channel.enableVibration(true);
        channel.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build());
        manager.createNotificationChannel(channel);
    }

    public static String post(Context context, ReminderEvent event) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ensureChannel(context);
        if (manager == null || !manager.areNotificationsEnabled() || Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return "blocked";
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID).getImportance() == NotificationManager.IMPORTANCE_NONE)
            return "blocked";
        Intent open = new Intent(context, MainActivity.class)
                .setData(android.net.Uri.parse("timeassistant-reminder://event/" + android.net.Uri.encode(event.getId())))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(ReminderScheduler.EXTRA_TASK_ID, event.getTargetId())
                .putExtra(ReminderScheduler.EXTRA_REMINDER_KIND, event.getReason());
        PendingIntent content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean hide = new com.hawson.timeassistant.data.local.NotificationPrivacyStore(context).hideTaskContent();
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_time_isle)
                .setContentTitle(hide ? "时屿提醒" : event.getTitle()).setContentText(body(event.getReason()))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body(event.getReason())))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                .setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(content).build();
        try { manager.notify(event.getId(), 0, notification); return "posted"; }
        catch (RuntimeException denied) { return "failed"; }
    }

    public static String body(String reason) {
        switch (reason) {
            case "schedule": return "日程将在 10 分钟后开始";
            case "schedule_end": return "日程已结束，请确认是否完成";
            case "deadline": return "已到截止时间";
            case "overdue": return "任务已逾期，请处理或重新安排";
            case "daily_review": return "回顾今天，整理明天的计划";
            case "test": return "这是一条测试提醒，不会创建或修改任务";
            default: return "已到提醒时间";
        }
    }
}
