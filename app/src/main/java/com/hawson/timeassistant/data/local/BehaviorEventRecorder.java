package com.hawson.timeassistant.data.local;

import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.TaskItem;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detects local schedule changes while keeping sensitive task content out of learning storage. */
public final class BehaviorEventRecorder {
    private final LocalIntelligenceStore store;
    private final Map<String, TaskSnapshot> snapshots = new LinkedHashMap<>();

    public BehaviorEventRecorder(LocalIntelligenceStore store, List<TaskItem> initialTasks) {
        this.store = store;
        if (initialTasks != null) for (TaskItem task : initialTasks) snapshots.put(task.getId(), TaskSnapshot.of(task));
    }

    public synchronized void onRepositoryChange(AppRepository repository, AppRepository.Change change,
                                                boolean ignoreEvidence) {
        if (change == null) return;
        if (change.getType() == AppRepository.ChangeType.IMPORT
                || change.getType() == AppRepository.ChangeType.MIGRATION
                || change.getType() == AppRepository.ChangeType.CLEAR) {
            rebuild(repository.getAllTasks());
            return;
        }
        if (change.getType() != AppRepository.ChangeType.TASKS || change.getItemId() == null) return;
        TaskItem current = repository.getTask(change.getItemId());
        TaskSnapshot previous = snapshots.get(change.getItemId());
        if (current == null) {
            snapshots.remove(change.getItemId());
            return;
        }
        TaskSnapshot next = TaskSnapshot.of(current);
        snapshots.put(current.getId(), next);
        if (ignoreEvidence || previous == null || previous.sameSchedule(next)) return;
        BehaviorEventEntity event = new BehaviorEventEntity();
        event.type = BehaviorEventEntity.TASK_RESCHEDULED;
        event.occurredAt = System.currentTimeMillis();
        event.localDay = startOfDay(event.occurredAt);
        event.oldStartMinute = minuteOfDay(previous.startAt);
        event.oldEndMinute = minuteOfDay(previous.endAt);
        event.newStartMinute = minuteOfDay(next.startAt);
        event.newEndMinute = minuteOfDay(next.endAt);
        event.project = next.project;
        event.tags = next.tags;
        event.outcome = "schedule_changed";
        store.recordBehavior(event);
    }

    public void recordAction(String eventType, String outcome) {
        BehaviorEventEntity event = new BehaviorEventEntity();
        event.type = eventType;
        event.occurredAt = System.currentTimeMillis();
        event.localDay = startOfDay(event.occurredAt);
        event.outcome = outcome == null ? "" : outcome;
        store.recordBehavior(event);
    }

    private void rebuild(List<TaskItem> tasks) {
        snapshots.clear();
        for (TaskItem task : tasks) snapshots.put(task.getId(), TaskSnapshot.of(task));
    }

    private static int minuteOfDay(long timestamp) {
        if (timestamp <= 0) return -1;
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
    }

    private static long startOfDay(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static final class TaskSnapshot {
        final long startAt;
        final long endAt;
        final long plannedDateAt;
        final String project;
        final String tags;

        private TaskSnapshot(long startAt, long endAt, long plannedDateAt, String project, String tags) {
            this.startAt = startAt; this.endAt = endAt; this.plannedDateAt = plannedDateAt;
            this.project = project == null ? "" : project.trim();
            this.tags = tags == null ? "" : tags;
        }

        static TaskSnapshot of(TaskItem task) {
            StringBuilder tags = new StringBuilder();
            for (String tag : task.getTags()) {
                if (tags.length() > 0) tags.append(',');
                tags.append(tag.trim());
            }
            return new TaskSnapshot(task.getScheduledStartAt(), task.getScheduledEndAt(),
                    task.getPlannedDateAt(), task.getProject(), tags.toString());
        }

        boolean sameSchedule(TaskSnapshot other) {
            return startAt == other.startAt && endAt == other.endAt
                    && plannedDateAt == other.plannedDateAt;
        }
    }
}
