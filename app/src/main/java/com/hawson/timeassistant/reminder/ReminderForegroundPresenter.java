package com.hawson.timeassistant.reminder;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AlertDialog;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.ReminderEvent;
import com.hawson.timeassistant.domain.TaskItem;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** One foreground dialog, merged arrivals, no extra audio and no task mutation. */
public final class ReminderForegroundPresenter {
    private final Activity activity;
    private final AppRepository repository;
    private final ReminderCoordinator coordinator;
    private final Consumer<String> openTask;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed = this::schedule;
    private final Runnable display = this::showPending;
    private final List<String> shown = new ArrayList<>();
    private final java.util.Set<String> dismissed = new java.util.HashSet<>();
    private AlertDialog dialog;
    private boolean started;
    private String firstTask;

    public ReminderForegroundPresenter(Activity activity, AppRepository repository,
                                       ReminderCoordinator coordinator, Consumer<String> openTask) {
        this.activity = activity; this.repository = repository; this.coordinator = coordinator; this.openTask = openTask;
    }
    public void start() { started = true; coordinator.addListener(changed); schedule(); }
    public void stop() {
        started = false; coordinator.removeListener(changed); main.removeCallbacks(display);
        if (dialog != null) { dialog.dismiss(); dialog = null; }
    }
    private void schedule() { main.removeCallbacks(display); main.postDelayed(display, 250); }

    private void showPending() {
        if (!started || activity.isFinishing() || activity.isDestroyed()) return;
        StringBuilder message = new StringBuilder();
        firstTask = null;
        boolean hide = new com.hawson.timeassistant.data.local.NotificationPrivacyStore(activity).hideTaskContent();
        for (ReminderEvent event : repository.getAllReminderEvents()) {
            if (event.getPresentedAt() > 0 || dismissed.contains(event.getId())
                    || event.getState() != ReminderEvent.State.ACTION_REQUIRED) continue;
            TaskItem task = repository.getTask(event.getTargetId());
            if (event.getTargetType() == ReminderEvent.TargetType.TASK && (task == null || task.getStatus().isTerminal())) continue;
            if (!shown.contains(event.getId())) shown.add(event.getId());
            if (firstTask == null && task != null) firstTask = task.getId();
            if (message.length() > 0) message.append("\n\n");
            message.append(hide ? "时屿提醒" : event.getTitle()).append("\n")
                    .append(ReminderNotifications.body(event.getReason()));
            if ("blocked".equals(event.getDeliveryState())) message.append("\n系统通知未开启，提醒已保留在应用内");
            else if ("failed".equals(event.getDeliveryState())) message.append("\n系统通知未能发布");
        }
        if (message.length() == 0) return;
        if (dialog != null && dialog.isShowing()) { dialog.setMessage(message); return; }
        dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(activity)
                .setTitle("到时间了").setMessage(message)
                .setPositiveButton("知道了", null)
                .setNeutralButton("查看任务", (d, w) -> { if (firstTask != null) openTask.accept(firstTask); })
                .create();
        dialog.setOnDismissListener(d -> {
            dismissed.addAll(shown);
            for (String id : new ArrayList<>(shown)) coordinator.presented(id);
            shown.clear(); dialog = null;
        });
        dialog.show();
    }
}
