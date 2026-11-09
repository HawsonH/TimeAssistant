package com.hawson.timeassistant.reminder;

import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.TaskItem;

/** Shared schedule/delivery rules. A broadcast's title is never authoritative. */
public final class ReminderPolicy {
    private ReminderPolicy() { }

    public static long targetAt(TaskItem task, AppSettings settings, String kind) {
        if (task == null || task.getStatus().isTerminal()) return 0;
        switch (kind == null ? "" : kind) {
            case "countdown": return task.getCountdownTargetAt();
            case "deadline": return settings.isTaskDeadlineReminderEnabled() ? task.getDueAt() : 0;
            case "overdue": return settings.isOverdueTaskReminderEnabled()
                    ? ReminderScheduler.overdueReminderAt(task) : 0;
            case "schedule": return settings.isScheduleReminderEnabled() && task.isScheduled()
                    && task.getStatus() != TaskItem.Status.NEEDS_RESCHEDULING
                    ? Math.max(0, task.getScheduledStartAt() - 600_000L) : 0;
            case "schedule_end": return settings.isScheduleReminderEnabled() && task.isScheduled()
                    && task.getStatus() != TaskItem.Status.NEEDS_RESCHEDULING ? task.getScheduledEndAt() : 0;
            default: return 0;
        }
    }

    public static boolean isDue(TaskItem task, AppSettings settings, String kind, long target, long now) {
        return target > 0 && target <= now && targetAt(task, settings, kind) == target;
    }
}
