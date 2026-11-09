/**
 * 专注阶段结束声振
 *
 * 在页面计时器与前台服务同时到点时去重，避免重复播放结束提示。
 */
package com.hawson.timeassistant.focus;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.hawson.timeassistant.R;
import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.reminder.ReminderScheduler;
import com.hawson.timeassistant.ui.MainActivity;

/** Plays the user-selected signal once when a focus or rest segment reaches its end. */
public final class FocusStageSignal {
    private static final String PREFERENCES = "focus_stage_signal";
    private static final String KEY_LAST_SEGMENT = "last_segment";
    private static final String KEY_LAST_SIGNAL_AT = "last_signal_at";
    private static final long DUPLICATE_WINDOW_MILLIS = 5_000L;
    private static final String COMPLETION_CHANNEL_PREFIX = "focus_completion_v2_";
    private static final int REQUEST_OPEN = 0xF0C30;
    private static final long[] VIBRATION_PATTERN = {0L, 280L, 140L, 420L};
    private static String lastSegmentInProcess = "";
    private static long lastSignalAtInProcess;

    private FocusStageSignal() {}

    public static void play(Context context, AppSettings settings, FocusState runningState) {
        if (context == null || settings == null || runningState == null) return;
        Context appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        long now = System.currentTimeMillis();
        String segment = segmentKey(runningState, now);
        SharedPreferences preferences = appContext.getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
        synchronized (FocusStageSignal.class) {
            String previousSegment = lastSegmentInProcess.isEmpty()
                    ? preferences.getString(KEY_LAST_SEGMENT, "") : lastSegmentInProcess;
            long previousAt = lastSignalAtInProcess == 0L
                    ? preferences.getLong(KEY_LAST_SIGNAL_AT, 0L) : lastSignalAtInProcess;
            if (segment.equals(previousSegment)
                    || now - previousAt < DUPLICATE_WINDOW_MILLIS) return;
            lastSegmentInProcess = segment;
            lastSignalAtInProcess = now;
            preferences.edit()
                    .putString(KEY_LAST_SEGMENT, segment)
                    .putLong(KEY_LAST_SIGNAL_AT, now)
                    .apply();
        }

        boolean deliveredByNotification = showCompletionNotification(
                appContext, settings, runningState);
        if (deliveredByNotification) return;
        if (settings.isFocusEndVibrationEnabled()) vibrate(appContext);
        if (settings.isFocusEndSoundEnabled()) ring(appContext);
    }

    static String segmentKey(FocusState state, long now) {
        long expectedFinishAt = state.getState() == FocusState.State.RUNNING
                ? now + state.remainingAt(now)
                : state.getUpdatedAt();
        return state.getMode().storageValue() + ':' + state.getPomodoroIndex() + ':'
                + Math.max(0L, expectedFinishAt / 1_000L);
    }

    private static void vibrate(Context context) {
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) context.getSystemService(
                    Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = manager == null ? null : manager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (vibrator == null || !vibrator.hasVibrator()) return;
        AudioAttributes attributes = alarmAudioAttributes();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, -1), attributes);
        } else {
            vibrator.vibrate(VIBRATION_PATTERN, -1, attributes);
        }
    }

    private static void ring(Context context) {
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (uri == null) return;
        try {
            Ringtone ringtone = RingtoneManager.getRingtone(context, uri);
            if (ringtone == null) return;
            ringtone.setAudioAttributes(alarmAudioAttributes());
            ringtone.play();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (ringtone.isPlaying()) ringtone.stop();
                } catch (RuntimeException ignored) { }
            }, 3_000L);
        } catch (RuntimeException ignored) { }
    }

    @SuppressLint("MissingPermission")
    private static boolean showCompletionNotification(Context context, AppSettings settings,
                                                      FocusState state) {
        if (!settings.isFocusReminderEnabled()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManagerCompat managerCompat = NotificationManagerCompat.from(context);
        if (!managerCompat.areNotificationsEnabled()) return false;

        boolean sound = settings.isFocusEndSoundEnabled();
        boolean vibration = settings.isFocusEndVibrationEnabled();
        String channelId = completionChannelId(sound, vibration);
        ensureCompletionChannel(context, channelId, sound, vibration);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = manager == null
                    ? null : manager.getNotificationChannel(channelId);
            if (channel == null) return false;
            // A blocked channel is an explicit system-level user choice; do not bypass it with
            // direct sound or vibration.
            if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return true;
        }

        String mode = state.getMode() == FocusState.Mode.FOCUS ? "专注"
                : state.getMode() == FocusState.Mode.LONG_BREAK ? "长休" : "短休";
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(ReminderScheduler.EXTRA_REMINDER_KIND, ReminderScheduler.KIND_FOCUS);
        PendingIntent contentIntent = PendingIntent.getActivity(context, REQUEST_OPEN, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification_time_isle)
                .setContentTitle(mode + "结束")
                .setContentText(state.getMode() == FocusState.Mode.FOCUS
                        ? "本轮专注已完成，点击填写完成反馈" : "休息结束，准备下一轮专注")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(30L * 60L * 1_000L)
                .setContentIntent(contentIntent);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (sound) builder.setSound(defaultAlarmUri());
            if (vibration) builder.setVibrate(VIBRATION_PATTERN);
        }
        int notificationId = 0xF0C30 ^ state.getSessionId().hashCode();
        try {
            managerCompat.notify(notificationId, builder.build());
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private static void ensureCompletionChannel(Context context, String channelId,
                                                boolean sound, boolean vibration) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(channelId) != null) return;
        NotificationChannel channel = new NotificationChannel(channelId,
                "专注结束提醒", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("专注或休息结束时显示提醒");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setSound(sound ? defaultAlarmUri() : null,
                sound ? alarmAudioAttributes() : null);
        channel.enableVibration(vibration);
        if (vibration) channel.setVibrationPattern(VIBRATION_PATTERN);
        manager.createNotificationChannel(channel);
    }

    private static String completionChannelId(boolean sound, boolean vibration) {
        if (sound && vibration) return COMPLETION_CHANNEL_PREFIX + "sound_vibration";
        if (sound) return COMPLETION_CHANNEL_PREFIX + "sound";
        if (vibration) return COMPLETION_CHANNEL_PREFIX + "vibration";
        return COMPLETION_CHANNEL_PREFIX + "silent";
    }

    private static Uri defaultAlarmUri() {
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        return uri == null ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) : uri;
    }

    private static AudioAttributes alarmAudioAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }
}
