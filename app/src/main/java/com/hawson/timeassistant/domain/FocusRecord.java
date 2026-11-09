/**
 * 专注历史记录
 *
 * 单次已完成或中断的专注会话快照，用于统计与回顾。
 */
package com.hawson.timeassistant.domain;

import com.hawson.timeassistant.util.JsonInterop;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable record of one completed or stopped focus session. */
public final class FocusRecord {
    private final String id;
    private final String taskId;
    private final String taskTitleSnapshot;
    private final long startedAt;
    private final long durationMillis;
    private final int interruptionCount;
    private final long completedAt;
    private final boolean taskCompleted;
    private final String outcome;
    private final String note;

    public FocusRecord(
            String taskId,
            String taskTitleSnapshot,
            long startedAt,
            long durationMillis,
            int interruptionCount,
            long completedAt,
            boolean taskCompleted
    ) {
        this(UUID.randomUUID().toString(), taskId, taskTitleSnapshot, startedAt, durationMillis,
                interruptionCount, completedAt, taskCompleted, "completed", "");
    }

    public FocusRecord(
            String taskId,
            String taskTitleSnapshot,
            long startedAt,
            long durationMillis,
            int interruptionCount,
            long completedAt,
            boolean taskCompleted,
            String outcome,
            String note
    ) {
        this(UUID.randomUUID().toString(), taskId, taskTitleSnapshot, startedAt, durationMillis,
                interruptionCount, completedAt, taskCompleted, outcome, note);
    }

    /**
     * Creates the deterministic history row for a naturally completed timer session. Reusing the
     * same focus session id therefore cannot create a second history record after a duplicate
     * service callback, broadcast, or process restore.
     */
    public static FocusRecord naturalCompletion(
            String sessionId,
            String taskId,
            String taskTitleSnapshot,
            long startedAt,
            long durationMillis,
            int interruptionCount,
            long completedAt
    ) {
        String normalizedSessionId = requireText(sessionId, "sessionId");
        return sessionCompletion(normalizedSessionId, taskId, taskTitleSnapshot, startedAt,
                durationMillis, interruptionCount, completedAt, "completed", "");
    }

    /** All settlement paths share the legacy stable identity; older records keep their IDs. */
    public static FocusRecord sessionCompletion(String sessionId, String taskId,
            String taskTitleSnapshot, long startedAt, long durationMillis, int interruptionCount,
            long completedAt, String outcome, String note) {
        String normalizedSessionId = requireText(sessionId, "sessionId");
        return new FocusRecord("natural-focus-" + normalizedSessionId, taskId,
                taskTitleSnapshot, startedAt, durationMillis, interruptionCount, completedAt,
                false, outcome, note);
    }

    private FocusRecord(
            String id,
            String taskId,
            String taskTitleSnapshot,
            long startedAt,
            long durationMillis,
            int interruptionCount,
            long completedAt,
            boolean taskCompleted,
            String outcome,
            String note
    ) {
        this.id = requireText(id, "id");
        this.taskId = normalizeOptional(taskId);
        this.taskTitleSnapshot = taskTitleSnapshot == null ? "" : taskTitleSnapshot;
        if (startedAt <= 0) throw new IllegalArgumentException("startedAt must be greater than zero");
        if (durationMillis < 0) throw new IllegalArgumentException("durationMillis must not be negative");
        if (interruptionCount < 0) throw new IllegalArgumentException("interruptionCount must not be negative");
        if (completedAt <= 0) throw new IllegalArgumentException("completedAt must be greater than zero");
        if (completedAt < startedAt) throw new IllegalArgumentException("completedAt must not be before startedAt");
        this.startedAt = startedAt;
        this.durationMillis = durationMillis;
        this.interruptionCount = interruptionCount;
        this.completedAt = completedAt;
        this.taskCompleted = taskCompleted;
        this.outcome = normalizeOptional(outcome) == null ? "completed" : outcome.trim();
        this.note = note == null ? "" : note.trim();
    }

    public String getId() { return id; }
    public String getTaskId() { return taskId; }
    public String getTaskTitleSnapshot() { return taskTitleSnapshot; }
    public long getStartedAt() { return startedAt; }
    public long getDurationMillis() { return durationMillis; }
    public int getDurationMinutesRounded() {
        return durationMinutesRounded(durationMillis);
    }

    public static int durationMinutesRounded(long durationMillis) {
        if (durationMillis <= 0) return 0;
        long rounded = durationMillis / 60_000L;
        if (durationMillis % 60_000L >= 30_000L) rounded++;
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, rounded));
    }
    public int getInterruptionCount() { return interruptionCount; }
    public long getCompletedAt() { return completedAt; }
    public boolean isTaskCompleted() { return taskCompleted; }
    public String getOutcome() { return outcome; }
    public String getNote() { return note; }

    public JSONObject toJson() throws JSONException {
        return JsonInterop.toJsonObject(toJsonValue());
    }

    Map<String, Object> toJsonValue() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("taskId", taskId);
        json.put("taskTitleSnapshot", taskTitleSnapshot);
        json.put("startedAt", startedAt);
        json.put("durationMillis", durationMillis);
        json.put("interruptionCount", interruptionCount);
        json.put("completedAt", completedAt);
        json.put("taskCompleted", taskCompleted);
        json.put("outcome", outcome);
        json.put("note", note);
        return Collections.unmodifiableMap(json);
    }

    public static FocusRecord fromJson(JSONObject json) throws JSONException {
        if (json == null) throw new JSONException("focus record JSON must not be null");
        String id = json.optString("id", UUID.randomUUID().toString());
        long completedAt = json.optLong("completedAt", 0);
        long durationMillis = json.has("durationMillis")
                ? json.optLong("durationMillis", 0)
                : json.optLong("durationMinutes", 0) * 60_000L;
        long startedAt = json.optLong("startedAt", completedAt > 0 ? completedAt - durationMillis : 0);
        try {
            return new FocusRecord(
                    id,
                    json.optString("taskId", ""),
                    json.optString("taskTitleSnapshot", json.optString("taskTitle", "")),
                    startedAt,
                    durationMillis,
                    json.optInt("interruptionCount", 0),
                    completedAt,
                    json.optBoolean("taskCompleted", false),
                    json.optString("outcome", "completed"),
                    json.optString("note", "")
            );
        } catch (IllegalArgumentException exception) {
            throw new JSONException("invalid focus record '" + id + "': " + exception.getMessage());
        }
    }

    private static String requireText(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
