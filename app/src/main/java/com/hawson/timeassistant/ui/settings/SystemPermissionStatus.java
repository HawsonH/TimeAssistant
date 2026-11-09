package com.hawson.timeassistant.ui.settings;

import android.Manifest;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import androidx.core.content.ContextCompat;
import com.hawson.timeassistant.focus.FocusTimerService;
import com.hawson.timeassistant.reminder.ReminderNotifications;
import com.hawson.timeassistant.reminder.ReminderScheduler;
import com.hawson.timeassistant.settings.PermissionGuideView;
import com.hawson.timeassistant.settings.PermissionGuideView.Item;
import java.util.ArrayList;
import java.util.List;

/** Read-only inspection. Unknown vendor controls must never be reported as authorized. */
final class SystemPermissionStatus {
    private SystemPermissionStatus() { }
    static List<Item> read(Context context) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        List<Item> items = new ArrayList<>();
        boolean notificationsOn = notifications != null && notifications.areNotificationsEnabled();
        items.add(new Item("notifications", "通知与提醒", "应用通知",
                notificationsOn ? "已开启" : "未开启",
                "提醒与专注通知需要此权限；点击申请或打开应用通知设置。", tone(notificationsOn)));
        String reminders = channel(notifications, ReminderNotifications.CHANNEL_ID);
        items.add(new Item("reminders", "通知与提醒", "任务与日程提醒",
                reminders,
                "应用通知总开关与本渠道需分别开启。ColorOS 请同时检查两层声音、震动和横幅设置。",
                channelTone(reminders)));
        String focus = channel(notifications, FocusTimerService.ACTIVE_NOTIFICATION_CHANNEL_ID);
        items.add(new Item("focus", "通知与提醒", "正在进行的专注",
                focus,
                "持续计时通知可保持静音；其设置独立于任务提醒。锁屏显示需系统内确认。",
                channelTone(focus)));
        boolean exact = ReminderScheduler.canScheduleExact(context);
        items.add(new Item("exact", "通知与提醒", "精确闹钟", exact ? "可用，无需重复授权" : "未授权，提醒可能延迟",
                "点击查看能力说明；需要且系统支持时，可打开闹钟权限设置。", tone(exact)));
        String dnd = notifications == null ? "无法检测" : notifications.getCurrentInterruptionFilter()
                == NotificationManager.INTERRUPTION_FILTER_ALL ? "未开启" : "开启或受系统限制";
        items.add(new Item("dnd", "通知与提醒", "勿扰与最终提醒效果", dnd,
                "即使渠道允许，静音、勿扰和厂商限制仍可能影响声音、震动或横幅。"));
        items.add(new Item("test", "通知与提醒", "测试提醒", "仅点击时发送，不创建任务",
                "请亲自检查横幅、声音和震动；提交通知不等于已感知提醒。"));
        ActivityManager activity = context.getSystemService(ActivityManager.class);
        PowerManager power = context.getSystemService(PowerManager.class);
        boolean restricted = Build.VERSION.SDK_INT >= 28 && activity != null && activity.isBackgroundRestricted();
        String battery = power == null ? "无法检测电池优化状态"
                : power.isIgnoringBatteryOptimizations(context.getPackageName()) ? "未应用标准电池优化" : "受标准电池优化管理";
        items.add(new Item("background", "后台与锁屏", "后台与电池限制",
                (restricted ? "系统报告后台受限；" : "系统未报告后台限制；") + battery,
                "厂商自启动、后台运行和锁屏选项需系统内确认。点击应用详情检查，不自动申请白名单。",
                restricted ? PermissionGuideView.TONE_WARNING : PermissionGuideView.TONE_NEUTRAL));
        boolean microphone = isGranted(context, Manifest.permission.RECORD_AUDIO);
        items.add(new Item("microphone", "按需权限", "麦克风", granted(microphone),
                "仅用于语音输入；授权不会自动录音。", optionalTone(microphone)));
        boolean calendar = isGranted(context, Manifest.permission.READ_CALENDAR);
        items.add(new Item("calendar", "按需权限", "系统日历", granted(calendar),
                "仅用于读取忙碌时间；授权不代表开启日历连接或允许 AI 读取。", optionalTone(calendar)));
        return items;
    }

    private static boolean isGranted(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static String granted(boolean granted) {
        return granted ? "已授权" : "未授权，按需开启";
    }

    private static int tone(boolean ok) {
        return ok ? PermissionGuideView.TONE_OK : PermissionGuideView.TONE_WARNING;
    }

    /** On-demand permissions are optional, so a missing grant is not a warning. */
    private static int optionalTone(boolean ok) {
        return ok ? PermissionGuideView.TONE_OK : PermissionGuideView.TONE_NEUTRAL;
    }

    private static int channelTone(String status) {
        if (status.startsWith("已开启")) return PermissionGuideView.TONE_OK;
        if (status.startsWith("已关闭")) return PermissionGuideView.TONE_WARNING;
        return PermissionGuideView.TONE_NEUTRAL;
    }

    private static String channel(NotificationManager manager, String id) {
        if (Build.VERSION.SDK_INT < 26) return "由应用通知与系统设置控制";
        NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(id);
        if (channel == null) return "渠道尚未创建，使用相应功能后可设置";
        return (channel.getImportance() == 0 ? "已关闭" : "已开启")
                + "；声音：" + (channel.getSound() == null ? "静音" : "已配置")
                + "；震动：" + (channel.shouldVibrate() ? "允许" : "关闭")
                + "；横幅：" + (channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH ? "渠道允许" : "未允许");
    }
}
