package com.hawson.timeassistant.reminder;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.ReminderEvent;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Application-scoped scheduling and delivery. No page or login is needed. */
public final class ReminderCoordinator {
    private final Context context;
    private final AppRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "TaskRemindersIO"); thread.setDaemon(true); return thread;
    });
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private ScheduledFuture<?> refresh;

    public ReminderCoordinator(Context context, AppRepository repository) {
        this.context = context.getApplicationContext(); this.repository = repository;
        repository.addChangeListener(change -> {
            requestRefresh();
            if (change.getType() == AppRepository.ChangeType.REMINDERS) main.post(() -> {
                for (Runnable listener : listeners) listener.run();
            });
        });
        requestRefresh();
    }

    public synchronized void requestRefresh() {
        if (refresh != null) refresh.cancel(false);
        refresh = io.schedule(this::reconcile, 100, TimeUnit.MILLISECONDS);
    }

    public synchronized void reconcile() {
        ReminderScheduler.refresh(context, repository.getAllTasks(), repository.getSettings(), repository.getCurrentFocus());
        for (ReminderEvent event : repository.getAllReminderEvents()) {
            if (!"pending".equals(event.getDeliveryState()) || event.getState() != ReminderEvent.State.ACTION_REQUIRED) continue;
            com.hawson.timeassistant.domain.TaskItem task = repository.getTask(event.getTargetId());
            boolean obsolete = event.getTargetType() == ReminderEvent.TargetType.TASK && (task == null || task.getStatus().isTerminal());
            repository.updateReminderDelivery(event.getId(), obsolete ? "blocked" : ReminderNotifications.post(context, event), false);
        }
    }

    public synchronized void receive(String taskId, String kind, long target) {
        ReminderEvent event = repository.claimReminder(taskId, kind, target, System.currentTimeMillis());
        if (event == null) { requestRefresh(); return; }
        ReminderScheduler.triggered(context, taskId, kind);
        repository.updateReminderDelivery(event.getId(), ReminderNotifications.post(context, event), false);
        requestRefresh();
    }

    public void addListener(Runnable listener) { listeners.addIfAbsent(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    public void presented(String id) { io.execute(() -> repository.updateReminderDelivery(id, null, true)); }
}
