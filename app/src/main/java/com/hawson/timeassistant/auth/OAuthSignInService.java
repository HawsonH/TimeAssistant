package com.hawson.timeassistant.auth;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.content.ContextCompat;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.ui.MainActivity;

/** Keeps the local callback listener alive only during a user-started sign-in. */
public final class OAuthSignInService extends Service {
    public static final String EXTRA_OPEN_SIGN_IN = "open_ai_sign_in";
    private static final String CHANNEL = "oauth_sign_in";
    private static final String CANCEL = "cancel_sign_in";
    private static final int NOTIFICATION = 0x0A071;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AiAccountPort accounts;
    private long deadline;
    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (!accounts.signInStatus().active || SystemClock.elapsedRealtime() >= deadline) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }
            handler.postDelayed(this, 1000);
        }
    };

    public static void start(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, OAuthSignInService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        accounts = ((TimeAssistantApplication) getApplication()).getAiServices().accounts;
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, "账号登录", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_OPEN_SIGN_IN, true);
        PendingIntent returnToApp = PendingIntent.getActivity(this, NOTIFICATION, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, NOTIFICATION + 1,
                new Intent(this, OAuthSignInService.class).setAction(CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent backgroundSettings = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()));
        PendingIntent settings = PendingIntent.getActivity(this, NOTIFICATION + 2, backgroundSettings,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        startForeground(NOTIFICATION, builder.setSmallIcon(R.drawable.ic_notification_time_isle)
                .setContentTitle("正在完成账号登录").setContentText("浏览器授权后将自动完成，可返回时屿查看结果")
                .setContentIntent(returnToApp).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, "后台运行设置", settings).build())
                .addAction(new Notification.Action.Builder(null, "取消授权", cancel).build()).build());
        handler.removeCallbacks(check);
        if (intent != null && CANCEL.equals(intent.getAction())) accounts.cancelSignIn();
        deadline = SystemClock.elapsedRealtime() + 660_000L;
        // The UI starts this service immediately before creating a waiting session.
        // Delay the first status check so startup cannot race that session creation.
        handler.postDelayed(check, 1000);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { handler.removeCallbacks(check); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
