package com.hawson.timeassistant.ui.schedule;

import com.hawson.timeassistant.domain.ReminderEvent;
import com.hawson.timeassistant.domain.TaskItem;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/** Full timestamps are independent of the grouping date. */
public final class TaskReminderSummary {
    private TaskReminderSummary() { }

    public static String format(TaskItem task, List<ReminderEvent> events) {
        SimpleDateFormat date = new SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE);
        StringBuilder result = new StringBuilder();
        if (task.hasCountdown()) {
            result.append("提醒 ").append(date.format(task.getCountdownTargetAt()));
            if (task.isRepeatingCountdown()) result.append(" · 循环");
        } else {
            long latest = 0;
            for (ReminderEvent event : events) {
                if (event.getTargetType() == ReminderEvent.TargetType.TASK
                        && task.getId().equals(event.getTargetId())) latest = Math.max(latest, event.getTriggerAt());
            }
            if (latest > 0) result.append("已提醒 ").append(date.format(latest));
        }
        if (task.getDueAt() > 0) {
            if (result.length() > 0) result.append("\n");
            result.append("截止 ").append(date.format(task.getDueAt()));
        }
        return result.toString();
    }
}
