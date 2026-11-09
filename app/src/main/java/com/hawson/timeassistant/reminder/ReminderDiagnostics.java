package com.hawson.timeassistant.reminder;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.hawson.timeassistant.design.UiKit;
import com.hawson.timeassistant.domain.ReminderEvent;

public final class ReminderDiagnostics {
    private ReminderDiagnostics() { }

    public static void show(Activity activity, Runnable requestPermission) {
        ReminderNotifications.ensureChannel(activity);
        NotificationManager manager = activity.getSystemService(NotificationManager.class);
        NotificationChannel channel = Build.VERSION.SDK_INT >= 26 && manager != null
                ? manager.getNotificationChannel(ReminderNotifications.CHANNEL_ID) : null;
        String channelState = "系统默认", sound = "按系统设置", vibration = "按系统设置";
        if (Build.VERSION.SDK_INT >= 26 && channel != null) {
            channelState = channel.getImportance() == 0 ? "已关闭"
                    : channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH ? "允许横幅" : "未允许横幅";
            sound = channel.getSound() == null ? "已静音" : "按系统设置";
            vibration = channel.shouldVibrate() ? "按系统设置" : "已关闭";
        }
        String summary = "应用通知：" + (manager != null && manager.areNotificationsEnabled() ? "已开启" : "未开启")
                + "\n提醒渠道：" + channelState + "\n声音：" + sound + "\n震动：" + vibration
                + "\n精确闹钟：" + (ReminderScheduler.canScheduleExact(activity) ? "可用" : "未授权，提醒可能延迟")
                + "\n勿扰：" + (manager != null && manager.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL
                ? "开启或受系统限制" : "关闭")
                + "\n后台限制：" + (Build.VERSION.SDK_INT >= 28 && ((android.app.ActivityManager) activity.getSystemService(Activity.ACTIVITY_SERVICE)).isBackgroundRestricted()
                ? "受限" : "未报告限制");
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(activity, 20); body.setPadding(pad, 0, pad, 0);
        TextView status = new TextView(activity); status.setText(summary); status.setTextSize(14);
        status.setTextColor(UiKit.text(activity)); body.addView(status);
        body.addView(UiKit.row(activity, "通知权限", null, 0, requestPermission));
        body.addView(UiKit.row(activity, "提醒渠道设置", null, 0, () -> openNotificationSettings(activity, true)));
        if (Build.VERSION.SDK_INT >= 31 && !ReminderScheduler.canScheduleExact(activity))
            body.addView(UiKit.row(activity, "精确闹钟设置", null, 0, () -> open(activity,
                    new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + activity.getPackageName())))));
        body.addView(UiKit.row(activity, "测试提醒", null, 0, () -> sendTest(activity)));
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity); scroll.addView(body);
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(activity).setTitle("任务提醒诊断")
                .setView(scroll).setPositiveButton("关闭", null).show();
    }

    public static void sendTest(Activity activity) {
        ReminderEvent event = ReminderEvent.triggered(ReminderEvent.TargetType.REVIEW, "test", "test",
                "时屿测试提醒", System.currentTimeMillis());
        String result = ReminderNotifications.post(activity, event);
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(activity)
                .setTitle("posted".equals(result) ? "已提交系统通知" : "系统通知未发布")
                .setMessage("posted".equals(result) ? "请检查横幅、声音和震动；最终效果由应用总开关、通知渠道与勿扰设置决定。"
                        : "请检查通知权限和提醒渠道，任务数据未改变。")
                .setPositiveButton("关闭", null).show();
    }

    public static void openNotificationSettings(Activity activity, boolean channel) {
        if (Build.VERSION.SDK_INT >= 26) {
            Intent intent = new Intent(channel ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS : Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            if (channel) intent.putExtra(Settings.EXTRA_CHANNEL_ID, ReminderNotifications.CHANNEL_ID);
            open(activity, intent);
        } else open(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName())));
    }

    private static void open(Activity activity, Intent intent) {
        try { activity.startActivity(intent); }
        catch (android.content.ActivityNotFoundException missing) {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName())));
        }
    }
}
