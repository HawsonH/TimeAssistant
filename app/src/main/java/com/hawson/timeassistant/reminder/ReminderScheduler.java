/**
 * 提醒调度器
 *
 * 根据任务排期、截止与专注状态，优先注册精确提醒，未授权时安全降级。
 */
package com.hawson.timeassistant.reminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.TaskItem;

import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Schedules exact task alarms when permitted, with an inexact fallback. */
public final class ReminderScheduler {
    public static final String ACTION_TASK_REMINDER =
            "com.hawson.timeassistant.action.TASK_REMINDER";
    public static final String ACTION_COUNTDOWN_UPDATED =
            "com.hawson.timeassistant.action.COUNTDOWN_UPDATED";
    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_TASK_TITLE = "task_title";
    public static final String EXTRA_REMINDER_KIND = "reminder_kind";
    public static final String EXTRA_TRIGGER_AT = "trigger_at";
    public static final String KIND_SCHEDULE = "schedule";
    public static final String KIND_SCHEDULE_END = "schedule_end";
    public static final String KIND_DEADLINE = "deadline";
    public static final String KIND_FOCUS = "focus";
    public static final String KIND_OVERDUE = "overdue";
    public static final String KIND_COUNTDOWN = "countdown";
    public static final String KIND_DAILY_REVIEW = "daily_review";
    static final String LEGACY_KIND_SNOOZED = "snoozed";

    private static final long TEN_MINUTES_MILLIS = 10 * 60_000L;
    private static final long OVERDUE_GRACE_MILLIS = 15 * 60_000L;
    private static final int DAILY_REVIEW_REQUEST_CODE = 0x54A11;
    private static final String REGISTRY_PREFERENCES = "reminder_scheduler_registry";
    private static final String KEY_SCHEDULED_TASK_ALARMS = "scheduled_task_alarms";

    private ReminderScheduler() {}

    public static void refresh(Context context, List<TaskItem> tasks, AppSettings settings) {
        refresh(context, tasks, settings, null);
    }

    public static synchronized void refresh(
            Context context,
            List<TaskItem> tasks,
            AppSettings settings,
            FocusState focusState
    ) {
        if (context == null || tasks == null || settings == null) return;
        Context appContext = applicationContext(context);
        AlarmManager manager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;

        long now = System.currentTimeMillis();
        Set<String> previouslyScheduled = loadRegistry(appContext);
        Set<String> scheduledNow = new LinkedHashSet<>();
        boolean exact = canScheduleExact(context);
        SharedPreferences registry = appContext.getSharedPreferences(REGISTRY_PREFERENCES, Context.MODE_PRIVATE);
        boolean precisionChanged = registry.getBoolean("exact", !exact) != exact || registry.getBoolean("force", false);
        SharedPreferences.Editor targets = registry.edit().putBoolean("exact", exact).remove("force");
        for (TaskItem task : tasks) {
            if (task == null) continue;
            for (String kind : new String[]{KIND_COUNTDOWN, KIND_SCHEDULE, KIND_SCHEDULE_END,
                    KIND_DEADLINE, KIND_OVERDUE}) {
                long target = ReminderPolicy.targetAt(task, settings, kind);
                String key = registryKey(task.getId(), kind);
                long previousTarget = registry.getLong("target:" + key, 0);
                boolean unchanged = previouslyScheduled.contains(key) && previousTarget == target;
                if (target <= 0 || target <= now && !unchanged && !KIND_COUNTDOWN.equals(kind)) continue;
                scheduledNow.add(key);
                if (!unchanged || precisionChanged) {
                    scheduleOne(appContext, manager, task, kind, target);
                    targets.putLong("target:" + key, target);
                }
            }
        }

        for (String stale : entriesToCancel(previouslyScheduled, scheduledNow)) {
            RegistryEntry entry = parseRegistryEntry(stale);
            if (entry != null) {
                cancel(appContext, manager, requestCode(entry.taskId, entry.kind),
                        entry.taskId, entry.kind);
                targets.remove("target:" + stale);
            }
        }
        targets.putStringSet(KEY_SCHEDULED_TASK_ALARMS, scheduledNow).apply();
        scheduleDailyReview(
                appContext,
                manager,
                settings.isDailyReviewReminderEnabled()
        );
    }

    public static void cancelTask(Context context, TaskItem task) {
        if (context == null || task == null) return;
        Context appContext = applicationContext(context);
        AlarmManager manager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;

        cancelTaskAlarms(appContext, manager, task.getId());
        Set<String> registered = loadRegistry(appContext);
        boolean changed = registered.remove(registryKey(task.getId(), KIND_SCHEDULE));
        changed |= registered.remove(registryKey(task.getId(), KIND_SCHEDULE_END));
        changed |= registered.remove(registryKey(task.getId(), KIND_DEADLINE));
        changed |= registered.remove(registryKey(task.getId(), KIND_OVERDUE));
        changed |= registered.remove(registryKey(task.getId(), KIND_COUNTDOWN));
        if (changed) saveRegistry(appContext, registered);
    }

    private static void scheduleOne(
            Context context,
            AlarmManager manager,
            TaskItem task,
            String kind,
            long atMillis
    ) {
        PendingIntent pendingIntent = pendingIntent(
                context,
                requestCode(task.getId(), kind),
                task.getId(),
                task.getTitle(),
                kind, atMillis
        );
        long deliveryAt = Math.max(atMillis, System.currentTimeMillis() + 50L);
        try {
            if (canScheduleExact(context)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deliveryAt, pendingIntent);
                return;
            }
        } catch (SecurityException revoked) { /* Permission can change after the check. */ }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deliveryAt, pendingIntent);
    }

    public static boolean canScheduleExact(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return manager != null && (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms());
    }

    public static synchronized void triggered(Context context, String taskId, String kind) {
        SharedPreferences preferences = applicationContext(context).getSharedPreferences(REGISTRY_PREFERENCES, Context.MODE_PRIVATE);
        Set<String> registered = loadRegistry(context);
        String key = registryKey(taskId, kind); registered.remove(key);
        SharedPreferences.Editor edit = preferences.edit().putStringSet(KEY_SCHEDULED_TASK_ALARMS, registered).remove("target:" + key);
        if (KIND_DAILY_REVIEW.equals(kind)) edit.remove("daily_target");
        edit.apply();
    }

    public static synchronized void invalidate(Context context) {
        applicationContext(context).getSharedPreferences(REGISTRY_PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean("force", true).remove("daily_target").apply();
    }

    private static void scheduleDailyReview(
            Context context,
            AlarmManager manager,
            boolean enabled
    ) {
        SharedPreferences registry = context.getSharedPreferences(REGISTRY_PREFERENCES, Context.MODE_PRIVATE);
        if (enabled && registry.getLong("daily_target", 0) > 0) return;
        long triggerAt = nextDailyReviewAt();
        PendingIntent pendingIntent = pendingIntent(
                context,
                DAILY_REVIEW_REQUEST_CODE,
                "daily_review",
                "今日复盘",
                KIND_DAILY_REVIEW, triggerAt
        );
        manager.cancel(pendingIntent);
        if (!enabled) { registry.edit().remove("daily_target").apply(); return; }

        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        registry.edit().putLong("daily_target", triggerAt).apply();
    }

    private static void cancelTaskAlarms(
            Context context,
            AlarmManager manager,
            String taskId
    ) {
        cancel(context, manager, requestCode(taskId, KIND_SCHEDULE), taskId, KIND_SCHEDULE);
        cancel(context, manager, requestCode(taskId, KIND_SCHEDULE_END), taskId,
                KIND_SCHEDULE_END);
        cancel(context, manager, requestCode(taskId, KIND_DEADLINE), taskId, KIND_DEADLINE);
        cancel(context, manager, requestCode(taskId, KIND_OVERDUE), taskId, KIND_OVERDUE);
        cancel(context, manager, requestCode(taskId, KIND_COUNTDOWN), taskId, KIND_COUNTDOWN);
    }

    private static void cancel(
            Context context,
            AlarmManager manager,
            int requestCode,
            String taskId,
            String kind
    ) {
        manager.cancel(pendingIntent(context, requestCode, taskId, "", kind));
    }

    private static PendingIntent pendingIntent(
            Context context,
            int requestCode,
            String taskId,
            String title,
            String kind
    ) {
        return pendingIntent(context, requestCode, taskId, title, kind, 0);
    }

    private static PendingIntent pendingIntent(Context context, int requestCode, String taskId,
                                             String title, String kind, long targetAt
    ) {
        Intent intent = new Intent(context, ReminderReceiver.class)
                .setAction(ACTION_TASK_REMINDER)
                .setData(android.net.Uri.parse("timeassistant-reminder://task/"
                        + android.net.Uri.encode(taskId == null ? "" : taskId) + "/" + kind))
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_TASK_TITLE, title)
                .putExtra(EXTRA_REMINDER_KIND, kind)
                .putExtra(EXTRA_TRIGGER_AT, targetAt);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    static Set<String> entriesToCancel(Set<String> registered, Set<String> scheduled) {
        Set<String> stale = new LinkedHashSet<>();
        if (registered != null) stale.addAll(registered);
        if (scheduled != null) stale.removeAll(scheduled);
        return stale;
    }

    static String registryKey(String taskId, String kind) {
        return (kind == null ? "" : kind) + ':' + (taskId == null ? "" : taskId);
    }

    static RegistryEntry parseRegistryEntry(String value) {
        if (value == null) return null;
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) return null;
        String kind = value.substring(0, separator);
        if (!KIND_SCHEDULE.equals(kind)
                && !KIND_SCHEDULE_END.equals(kind)
                && !KIND_DEADLINE.equals(kind)
                && !KIND_FOCUS.equals(kind)
                && !KIND_OVERDUE.equals(kind)
                && !KIND_COUNTDOWN.equals(kind)
                && !LEGACY_KIND_SNOOZED.equals(kind)) {
            return null;
        }
        return new RegistryEntry(value.substring(separator + 1), kind);
    }

    static int requestCode(String taskId, String kind) {
        if (KIND_OVERDUE.equals(kind)) {
            return rawRequestCode(taskId, KIND_DEADLINE) ^ 0x40000000;
        }
        return rawRequestCode(taskId, kind);
    }

    static long overdueReminderAt(TaskItem task) {
        if (task == null || task.getStatus().isTerminal()) return 0;
        long dueAt = task.getDueAt();
        if (dueAt <= 0) return 0;
        return dueAt > Long.MAX_VALUE - OVERDUE_GRACE_MILLIS
                ? Long.MAX_VALUE : dueAt + OVERDUE_GRACE_MILLIS;
    }

    static long focusReminderAt(FocusState focusState, long now) {
        if (focusState == null || focusState.getState() != FocusState.State.RUNNING) return 0;
        long remaining = focusState.remainingAt(now);
        if (remaining <= 0) return 0;
        return now > Long.MAX_VALUE - remaining ? Long.MAX_VALUE : now + remaining;
    }

    private static int rawRequestCode(String taskId, String kind) {
        String stable = (taskId == null ? "" : taskId) + ':' + (kind == null ? "" : kind);
        return stable.hashCode() & 0x7fffffff;
    }

    private static long nextDailyReviewAt() {
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, 21);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        return next.getTimeInMillis();
    }

    private static Context applicationContext(Context context) {
        Context appContext = context.getApplicationContext();
        return appContext == null ? context : appContext;
    }

    private static Set<String> loadRegistry(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                REGISTRY_PREFERENCES,
                Context.MODE_PRIVATE
        );
        Set<String> stored = preferences.getStringSet(KEY_SCHEDULED_TASK_ALARMS, null);
        return stored == null ? new LinkedHashSet<>() : new LinkedHashSet<>(stored);
    }

    private static void saveRegistry(Context context, Set<String> entries) {
        context.getSharedPreferences(REGISTRY_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_SCHEDULED_TASK_ALARMS, new HashSet<>(entries))
                .apply();
    }

    static final class RegistryEntry {
        final String taskId;
        final String kind;

        RegistryEntry(String taskId, String kind) {
            this.taskId = taskId;
            this.kind = kind;
        }
    }
}
