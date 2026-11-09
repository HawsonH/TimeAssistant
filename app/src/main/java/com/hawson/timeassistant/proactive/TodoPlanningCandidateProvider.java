package com.hawson.timeassistant.proactive;

import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.domain.TodoDatePolicy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Selects existing todos that may benefit from clarification or conversion into a schedule. */
public final class TodoPlanningCandidateProvider {
    private final AppRepository repository;

    public TodoPlanningCandidateProvider(AppRepository repository) { this.repository = repository; }

    public Snapshot snapshot(long now) {
        List<TaskItem> tasks = new ArrayList<>();
        for (TaskItem task : repository.getAllTasks()) {
            if (task.getStatus().isTerminal() || task.hasCompleteTimeArrangement()
                    || task.requiresTimeArrangement()) continue;
            if (AttentionIssueProvider.requiresReflow(task, now)
                    || AttentionIssueProvider.requiresCompletionConfirmation(task, now)) continue;
            if (!TodoDatePolicy.isVisible(task.getPlannedDateAt(), false, 0,
                    now, now, java.util.TimeZone.getDefault())) continue;
            tasks.add(task);
        }
        tasks.sort((first, second) -> {
            boolean firstDue = first.getDueAt() > 0 && first.getDueAt() <= now + 2L * 86_400_000L;
            boolean secondDue = second.getDueAt() > 0 && second.getDueAt() <= now + 2L * 86_400_000L;
            if (firstDue != secondDue) return firstDue ? -1 : 1;
            int priority = Integer.compare(second.getPriority(), first.getPriority());
            if (priority != 0) return priority;
            long a = first.getDueAt() > 0 ? first.getDueAt() : Long.MAX_VALUE;
            long b = second.getDueAt() > 0 ? second.getDueAt() : Long.MAX_VALUE;
            return Long.compare(a, b);
        });
        if (tasks.size() > 30) tasks = new ArrayList<>(tasks.subList(0, 30));
        return new Snapshot(tasks);
    }

    public static final class Snapshot {
        public final List<TaskItem> tasks;
        Snapshot(List<TaskItem> source) {
            List<TaskItem> copy = new ArrayList<>();
            for (TaskItem task : source) copy.add(task.copy());
            tasks = java.util.Collections.unmodifiableList(copy);
        }
        public boolean isEmpty() { return tasks.isEmpty(); }
        public int count() { return tasks.size(); }
        public JSONObject toJson(int limit) throws Exception {
            JSONArray items = new JSONArray();
            for (TaskItem task : tasks) {
                if (items.length() >= Math.max(1, limit)) break;
                items.put(new JSONObject().put("id", task.getId()).put("title", task.getTitle())
                        .put("description", task.getDescription()).put("priority", task.getPriority())
                        .put("dueAt", task.getDueAt())
                        .put("estimatedMinutes", task.getEstimatedDurationMinutes())
                        .put("project", task.getProject() == null ? "" : task.getProject())
                        .put("tags", new JSONArray(task.getTags())));
            }
            return new JSONObject().put("summary", "有 " + tasks.size() + " 个未排期待办")
                    .put("items", items);
        }
        public String fingerprint() {
            List<String> values = new ArrayList<>();
            for (TaskItem task : tasks) values.add(task.getId() + ':' + task.getUpdatedAt());
            return AttentionIssueProvider.sha256(String.join("|", values));
        }
    }
}
