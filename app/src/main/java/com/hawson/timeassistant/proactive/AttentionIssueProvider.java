package com.hawson.timeassistant.proactive;

import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.TaskItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The shared source of truth for items shown on the schedule Attention page. */
public final class AttentionIssueProvider {
    private final AppRepository repository;

    public AttentionIssueProvider(AppRepository repository) {
        this.repository = repository;
    }

    public Snapshot snapshot(long now) {
        List<TaskItem> overdue = new ArrayList<>();
        List<TaskItem> confirmations = new ArrayList<>();
        List<TaskItem> incomplete = new ArrayList<>();
        for (TaskItem task : repository.getAllTasks()) {
            if (task.getStatus().isTerminal()) continue;
            if (requiresReflow(task, now)) overdue.add(task);
            else if (requiresCompletionConfirmation(task, now)) confirmations.add(task);
            else if (task.requiresTimeArrangement() && !task.hasCompleteTimeArrangement()) {
                incomplete.add(task);
            }
        }
        overdue.sort(Comparator.comparingLong(AttentionIssueProvider::anchor));
        confirmations.sort(Comparator.comparingLong(TaskItem::getScheduledEndAt));
        incomplete.sort((first, second) -> {
            int priority = Integer.compare(second.getPriority(), first.getPriority());
            return priority != 0 ? priority : Long.compare(anchor(first), anchor(second));
        });
        return new Snapshot(overdue, confirmations, incomplete);
    }

    public static boolean requiresReflow(TaskItem task, long now) {
        if (requiresCompletionConfirmation(task, now)) return false;
        return task != null && (task.getStatus() == TaskItem.Status.NEEDS_RESCHEDULING
                || (task.getDueAt() > 0 && task.getDueAt() < now));
    }

    public static boolean requiresCompletionConfirmation(TaskItem task, long now) {
        return task != null && !task.getStatus().isTerminal()
                && task.getStatus() != TaskItem.Status.NEEDS_RESCHEDULING
                && task.isScheduled()
                && (task.getStatus() == TaskItem.Status.AWAITING_CONFIRMATION
                || task.getScheduledEndAt() <= now);
    }

    private static long anchor(TaskItem task) {
        if (task.getScheduledStartAt() > 0) return task.getScheduledStartAt();
        if (task.getPlannedDateAt() > 0) return task.getPlannedDateAt();
        if (task.getDueAt() > 0) return task.getDueAt();
        return Long.MAX_VALUE;
    }

    public static final class Snapshot {
        public final List<TaskItem> overdue;
        public final List<TaskItem> awaitingConfirmation;
        public final List<TaskItem> incompleteTime;

        Snapshot(List<TaskItem> overdue, List<TaskItem> confirmations, List<TaskItem> incomplete) {
            this.overdue = copy(overdue);
            this.awaitingConfirmation = copy(confirmations);
            this.incompleteTime = copy(incomplete);
        }

        public int count() { return overdue.size() + awaitingConfirmation.size() + incompleteTime.size(); }
        public boolean isEmpty() { return count() == 0; }

        public JSONObject toJson(int limitPerGroup) throws Exception {
            return new JSONObject().put("summary", "注意页有 " + count() + " 项")
                    .put("overdue", items(overdue, limitPerGroup))
                    .put("awaitingConfirmation", items(awaitingConfirmation, limitPerGroup))
                    .put("incompleteTime", items(incompleteTime, limitPerGroup));
        }

        public String fingerprint() {
            List<String> values = new ArrayList<>();
            append(values, "o", overdue); append(values, "c", awaitingConfirmation);
            append(values, "i", incompleteTime);
            return sha256(String.join("|", values));
        }

        private static void append(List<String> values, String prefix, List<TaskItem> tasks) {
            for (TaskItem task : tasks) values.add(prefix + ':' + task.getId() + ':' + task.getUpdatedAt());
        }
    }

    private static JSONArray items(List<TaskItem> tasks, int limit) throws Exception {
        JSONArray array = new JSONArray();
        for (TaskItem task : tasks) {
            if (array.length() >= Math.max(1, limit)) break;
            array.put(new JSONObject().put("id", task.getId()).put("title", task.getTitle())
                    .put("priority", task.getPriority()).put("dueAt", task.getDueAt())
                    .put("scheduledStartAt", task.getScheduledStartAt())
                    .put("scheduledEndAt", task.getScheduledEndAt()));
        }
        return array;
    }

    private static List<TaskItem> copy(List<TaskItem> tasks) {
        List<TaskItem> result = new ArrayList<>();
        for (TaskItem task : tasks) result.add(task.copy());
        return java.util.Collections.unmodifiableList(result);
    }

    static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 12; index++) result.append(String.format("%02x", bytes[index]));
            return result.toString();
        } catch (Exception error) { return Integer.toHexString(value.hashCode()); }
    }
}
