package com.hawson.timeassistant.ui.settings;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import com.hawson.timeassistant.design.SoftAlertDialogBuilder;
import com.hawson.timeassistant.focus.FocusTimerService;
import com.hawson.timeassistant.reminder.ReminderDiagnostics;
import com.hawson.timeassistant.reminder.ReminderNotifications;
import com.hawson.timeassistant.reminder.ReminderScheduler;
import com.hawson.timeassistant.settings.PermissionGuideView;

/** Activity-result/system-settings bridge. Registration happens during Activity.onCreate. */
public final class SystemPermissionGuideController implements DefaultLifecycleObserver {
    private final AppCompatActivity activity;
    private final ActivityResultLauncher<String> permission;
    private final android.content.SharedPreferences requests;
    private PermissionGuideView view;

    public SystemPermissionGuideController(AppCompatActivity activity) {
        this.activity = activity;
        requests = activity.getSharedPreferences("permission_guide", 0);
        permission = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            refresh();
            if (!granted) new SoftAlertDialogBuilder(activity).setTitle("权限尚未开启")
                    .setMessage("相关功能仍不可用。可以稍后开启，其他功能不受影响。")
                    .setNegativeButton("稍后", null).setPositiveButton("应用详情", (d, w) -> details()).show();
        });
        activity.getLifecycle().addObserver(this);
    }

    public PermissionGuideView createView() {
        view = new PermissionGuideView(activity, this::open);
        refresh(); return view;
    }

    public void refresh() { if (view != null) view.render(SystemPermissionStatus.read(activity)); }
    @Override public void onResume(LifecycleOwner owner) { refresh(); }
    @Override public void onDestroy(LifecycleOwner owner) { view = null; }

    private void open(String id) {
        switch (id) {
            case "notifications":
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
                    request(Manifest.permission.POST_NOTIFICATIONS, "允许提醒与专注状态显示在通知栏。不会修改声音或勿扰设置。");
                else if (Build.VERSION.SDK_INT >= 26) launch(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
                else details();
                break;
            case "reminders": channel(ReminderNotifications.CHANNEL_ID); break;
            case "focus": channel(FocusTimerService.ACTIVE_NOTIFICATION_CHANNEL_ID); break;
            case "exact":
                if (Build.VERSION.SDK_INT >= 31 && !ReminderScheduler.canScheduleExact(activity))
                    launch(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()));
                else new SoftAlertDialogBuilder(activity).setTitle("精确闹钟")
                        .setMessage("当前系统能力可用，无需重复授权。实际提醒仍受通知与后台设置影响。")
                        .setPositiveButton("关闭", null).show();
                break;
            case "background": details(); break;
            case "dnd": launch(new Intent(Settings.ACTION_SOUND_SETTINGS)); break;
            case "microphone": request(Manifest.permission.RECORD_AUDIO, "麦克风仅用于你主动开始的语音输入，不会自动录音。"); break;
            case "calendar": request(Manifest.permission.READ_CALENDAR, "日历权限用于忙碌区间。授权不会开启日历连接，仍需你在日历设置中选择。"); break;
            case "test": test(); break;
            default: break;
        }
    }

    private void request(String name, String explanation) {
        if (granted(name)) { details(); return; }
        boolean asked = requests.getBoolean(name, false);
        boolean canRequest = !asked || ActivityCompat.shouldShowRequestPermissionRationale(activity, name);
        new SoftAlertDialogBuilder(activity).setTitle("权限用途").setMessage(explanation)
                .setNegativeButton("暂不", null)
                .setPositiveButton(canRequest ? "继续授权" : "打开应用详情", (d, w) -> {
                    if (!canRequest) { details(); return; }
                    requests.edit().putBoolean(name, true).apply(); permission.launch(name);
                }).show();
    }

    private void channel(String id) {
        if (Build.VERSION.SDK_INT >= 26) launch(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID, id));
        else details();
    }

    private void test() {
        ReminderDiagnostics.sendTest(activity);
        refresh();
    }

    private boolean granted(String name) { return ContextCompat.checkSelfPermission(activity, name) == PackageManager.PERMISSION_GRANTED; }
    private Uri packageUri() { return Uri.parse("package:" + activity.getPackageName()); }
    private void details() { launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())); }
    private void launch(Intent intent) {
        try { activity.startActivity(intent); }
        catch (android.content.ActivityNotFoundException | SecurityException unsupported) {
            try { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())); }
            catch (android.content.ActivityNotFoundException | SecurityException missing) {
                Toast.makeText(activity, "无法打开系统页面，请在系统设置中查找本应用", Toast.LENGTH_LONG).show();
            }
        }
    }
}
