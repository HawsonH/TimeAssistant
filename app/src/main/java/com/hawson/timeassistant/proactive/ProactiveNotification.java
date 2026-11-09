package com.hawson.timeassistant.proactive;

import android.Manifest;
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
import com.hawson.timeassistant.ui.MainActivity;

public final class ProactiveNotification {
    public static final String EXTRA_OPEN_PROACTIVE_CHAT = "open_proactive_chat";
    private static final String CHANNEL = "proactive_ai_checkins";

    private ProactiveNotification() { }

    static long show(Context context, ProactiveRoutine routine, String body, String runId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return 0;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "主动 AI 整理",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("在有待处理事项时提供整理建议");
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_PROACTIVE_CHAT, true);
        PendingIntent pending = PendingIntent.getActivity(context, runId.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (new com.hawson.timeassistant.data.local.NotificationPrivacyStore(context)
                .hideTaskContent()) {
            body = "有一条整理建议，打开时屿查看";
        }
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_time_isle).setContentTitle(routine.label)
                .setContentText(body).setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true);
        manager.notify(runId.hashCode(), notification.build());
        return System.currentTimeMillis();
    }
}
