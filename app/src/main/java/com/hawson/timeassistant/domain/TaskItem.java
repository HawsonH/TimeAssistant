/**
 * 任务实体
 *
 * 对话、日程、专注共用的核心数据模型，包含排期、优先级、父子任务与状态流转。
 */
package com.hawson.timeassistant.domain;

import com.hawson.timeassistant.util.JsonInterop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistable task domain model shared by chat, schedule, and focus flows.
 *
 * <p>Timestamps are Unix epoch milliseconds. A value of {@code 0} means that an optional
 * timestamp has not been set. Priority uses the constants declared on this class.</p>
 */
public final class TaskItem {
    public static final int PRIORITY_LOW = 0;
    public static final int PRIORITY_NORMAL = 1;
    public static final int PRIORITY_HIGH = 2;
    public static final int PRIORITY_URGENT = 3;

    public static final String SOURCE_CHAT = "chat";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_RECURRING = "recurring";
    public static final String SOURCE_IMPORTED = "imported";
    public static final String SOURCE_LEGACY_SCHEDULE = "legacy_schedule";

    public enum Status {
        PENDING("pending"),
        SCHEDULED("scheduled"),
        IN_PROGRESS("in_progress"),
        AWAITING_CONFIRMATION("awaiting_confirmation"),
        COMPLETED("completed"),
        NEEDS_RESCHEDULING("needs_rescheduling"),
        CANCELLED("cancelled");

        private final String storageValue;

        Status(String storageValue) {
            this.storageValue = storageValue;
        }

        public String storageValue() {
            return storageValue;
        }

        public boolean isTerminal() {
            return this == COMPLETED || this == CANCELLED;
        }

        public static Status fromStorage(String raw) {
            if (raw == null) return PENDING;
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            // Keep old snapshots readable after the archived state was removed.
            if ("archived".equals(normalized)) return CANCELLED;
            for (Status status : values()) {
                if (status.storageValue.equals(normalized)
                        || status.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                    return status;
                }
            }
            switch (raw.trim()) {
                case "待处理": return PENDING;
                case "已安排": return SCHEDULED;
                case "进行中": return IN_PROGRESS;
                case "待确认": return AWAITING_CONFIRMATION;
                case "已完成": return COMPLETED;
                case "需要重新安排": return NEEDS_RESCHEDULING;
                case "已取消": return CANCELLED;
                case "已归档": return CANCELLED;
                default: return PENDING;
            }
        }
    }

    private final String id;
    private String title;
    private String description;
    private Status status;
    private int priority;
    private int estimatedDurationMinutes;
    private int actualDurationMinutes;
    private long dueAt;
    private long plannedDateAt;
    private long scheduledStartAt;
    private long scheduledEndAt;
    private long countdownTargetAt;
    private long countdownIntervalMillis;
    private String recurringGroupId;
    private long recurringOccurrenceAt;
    private boolean requiresTimeArrangement;
    private boolean locked;
    private String parentTaskId;
    private final List<String> childTaskIds;
    private String project;
    private final List<String> tags;
    private String source;
    private long statusChangedAt;
    private long startedAt;
    private long completedAt;
    private final long createdAt;
    private long updatedAt;

    public TaskItem(String title) {
        this(UUID.randomUUID().toString(), title, "", Status.PENDING, PRIORITY_NORMAL,
                0, 0, 0, 0, 0, 0, 0, 0, null, 0, false, false, null,
                Collections.emptyList(), null,
                Collections.emptyList(), SOURCE_MANUAL, 0, 0, 0, System.currentTimeMillis(),
                System.currentTimeMillis());
    }

    private TaskItem(
            String id,
            String title,
            String description,
            Status status,
            int priority,
            int estimatedDurationMinutes,
            int actualDurationMinutes,
            long dueAt,
            long plannedDateAt,
            long scheduledStartAt,
            long scheduledEndAt,
            long countdownTargetAt,
            long countdownIntervalMillis,
            String recurringGroupId,
            long recurringOccurrenceAt,
            boolean requiresTimeArrangement,
            boolean locked,
            String parentTaskId,
            Collection<String> childTaskIds,
            String project,
            Collection<String> tags,
            String source,
            long statusChangedAt,
            long startedAt,
            long completedAt,
            long createdAt,
            long updatedAt
    ) {
        this.id = requireText(id, "id");
        this.title = requireText(title, "title");
        this.description = nullToEmpty(description);
        this.status = status == null ? Status.PENDING : status;
        this.priority = clampPriority(priority);
        this.estimatedDurationMinutes = requireNonNegative(estimatedDurationMinutes, "estimatedDurationMinutes");
        this.actualDurationMinutes = requireNonNegative(actualDurationMinutes, "actualDurationMinutes");
        this.dueAt = requireNonNegative(dueAt, "dueAt");
        this.plannedDateAt = requireNonNegative(plannedDateAt, "plannedDateAt");
        this.scheduledStartAt = requireNonNegative(scheduledStartAt, "scheduledStartAt");
        this.scheduledEndAt = requireNonNegative(scheduledEndAt, "scheduledEndAt");
        validateSchedule(this.scheduledStartAt, this.scheduledEndAt);
        this.countdownTargetAt = requireNonNegative(countdownTargetAt, "countdownTargetAt");
        this.countdownIntervalMillis = requireNonNegative(
                countdownIntervalMillis, "countdownIntervalMillis");
        validateCountdown(this.countdownTargetAt, this.countdownIntervalMillis);
        this.recurringGroupId = normalizeOptionalText(recurringGroupId);
        this.recurringOccurrenceAt = requireNonNegative(
                recurringOccurrenceAt, "recurringOccurrenceAt");
        validateRecurringOccurrence(this.recurringGroupId, this.recurringOccurrenceAt);
        this.requiresTimeArrangement = requiresTimeArrangement;
        this.locked = locked && this.scheduledStartAt > 0 && this.scheduledEndAt > 0;
        this.parentTaskId = normalizeOptionalId(parentTaskId, this.id);
        this.childTaskIds = new ArrayList<>();
        replaceChildren(childTaskIds);
        this.project = normalizeOptionalText(project);
        this.tags = new ArrayList<>();
        replaceTags(tags);
        this.source = normalizeSource(source);
        long now = System.currentTimeMillis();
        this.createdAt = createdAt > 0 ? createdAt : now;
        this.updatedAt = Math.max(updatedAt > 0 ? updatedAt : this.createdAt, this.createdAt);
        this.statusChangedAt = statusChangedAt > 0 ? statusChangedAt : this.updatedAt;
        this.startedAt = requireNonNegative(startedAt, "startedAt");
        this.completedAt = requireNonNegative(completedAt, "completedAt");
        if (this.status == Status.IN_PROGRESS && this.startedAt == 0) {
            this.startedAt = this.statusChangedAt;
        }
        if (this.status == Status.COMPLETED && this.completedAt == 0) {
            this.completedAt = this.statusChangedAt;
        }
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Status getStatus() { return status; }
    public int getPriority() { return priority; }
    public int getEstimatedDurationMinutes() { return estimatedDurationMinutes; }
    public int getActualDurationMinutes() { return actualDurationMinutes; }
    public long getDueAt() { return dueAt; }
    public long getPlannedDateAt() { return plannedDateAt; }
    public long getScheduledStartAt() { return scheduledStartAt; }
    public long getScheduledEndAt() { return scheduledEndAt; }
    public long getCountdownTargetAt() { return countdownTargetAt; }
    public long getCountdownIntervalMillis() { return countdownIntervalMillis; }
    public boolean hasCountdown() { return countdownTargetAt > 0; }
    public boolean isRepeatingCountdown() { return countdownIntervalMillis > 0; }
    public String getRecurringGroupId() { return recurringGroupId; }
    public long getRecurringOccurrenceAt() { return recurringOccurrenceAt; }
    public boolean isRecurringOccurrence() {
        return recurringGroupId != null && recurringOccurrenceAt > 0;
    }
    public boolean isScheduled() { return scheduledStartAt > 0 && scheduledEndAt > 0; }
    public boolean hasCompleteTimeArrangement() { return isScheduled(); }
    public boolean requiresTimeArrangement() { return requiresTimeArrangement; }
    public boolean hasScheduleDate() {
        return plannedDateAt > 0 || scheduledStartAt > 0 || scheduledEndAt > 0;
    }
    public boolean hasPartialSchedule() { return hasScheduleDate() && !isScheduled(); }
    public boolean isLocked() { return locked; }
    public String getParentTaskId() { return parentTaskId; }
    public List<String> getChildTaskIds() {
        return Collections.unmodifiableList(new ArrayList<>(childTaskIds));
    }
    public String getProject() { return project; }
    public List<String> getTags() {
        return Collections.unmodifiableList(new ArrayList<>(tags));
    }
    public String getSource() { return source; }
    public long getStatusChangedAt() { return statusChangedAt; }
    public long getStartedAt() { return startedAt; }
    public long getCompletedAt() { return completedAt; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }

    public TaskItem setTitle(String title) {
        this.title = requireText(title, "title");
        touch();
        return this;
    }

    public TaskItem setDescription(String description) {
        this.description = nullToEmpty(description);
        touch();
        return this;
    }

    public TaskItem setStatus(Status status) {
        if (status == null) throw new IllegalArgumentException("status must not be null");
        if (this.status == status) return this;
        transitionStatus(status);
        if (status == Status.NEEDS_RESCHEDULING) requiresTimeArrangement = true;
        touch();
        return this;
    }

    /**
     * Restores a completed task to the state that matches its current timing context.
     * Completed schedule blocks whose end time has passed need rescheduling; current and future
     * schedule blocks stay scheduled, while tasks without a complete block return to pending.
     */
    public TaskItem undoCompletion(long now) {
        requireNonNegative(now, "now");
        if (status != Status.COMPLETED) return this;
        boolean overdue = isScheduled() && scheduledEndAt <= now;
        setStatus(overdue ? Status.NEEDS_RESCHEDULING
                : isScheduled() ? Status.SCHEDULED : Status.PENDING);
        if (!isScheduled() && isRepeatingCountdown()) advanceCountdownAfter(now);
        return this;
    }

    public TaskItem setPriority(int priority) {
        this.priority = clampPriority(priority);
        touch();
        return this;
    }

    public TaskItem setEstimatedDurationMinutes(int minutes) {
        this.estimatedDurationMinutes = requireNonNegative(minutes, "estimatedDurationMinutes");
        touch();
        return this;
    }

    public TaskItem setActualDurationMinutes(int minutes) {
        this.actualDurationMinutes = requireNonNegative(minutes, "actualDurationMinutes");
        touch();
        return this;
    }

    public TaskItem addActualDurationMinutes(int minutes) {
        requireNonNegative(minutes, "minutes");
        this.actualDurationMinutes = Math.addExact(this.actualDurationMinutes, minutes);
        touch();
        return this;
    }

    public TaskItem setDueAt(long dueAt) {
        this.dueAt = requireNonNegative(dueAt, "dueAt");
        touch();
        return this;
    }

    public TaskItem setRequiresTimeArrangement(boolean required) {
        requiresTimeArrangement = required;
        if (!required && status == Status.NEEDS_RESCHEDULING) transitionStatus(Status.PENDING);
        touch();
        return this;
    }

    public TaskItem setPlannedDateAt(long plannedDateAt) {
        this.plannedDateAt = requireNonNegative(plannedDateAt, "plannedDateAt");
        touch();
        return this;
    }

    /**
     * Stores a task's optional date and time parts without forcing a complete time block.
     * A task is considered scheduled only when both start and end are present.
     */
    public TaskItem setScheduleFields(long plannedDateAt, long startAt, long endAt, boolean locked) {
        requireNonNegative(plannedDateAt, "plannedDateAt");
        requireNonNegative(startAt, "scheduledStartAt");
        requireNonNegative(endAt, "scheduledEndAt");
        validateSchedule(startAt, endAt);
        boolean complete = startAt > 0 && endAt > 0;
        if (locked && !complete) {
            throw new IllegalArgumentException("only a complete schedule can be locked");
        }
        this.plannedDateAt = plannedDateAt > 0 ? plannedDateAt : (startAt > 0 ? startAt : endAt);
        this.scheduledStartAt = startAt;
        this.scheduledEndAt = endAt;
        this.locked = locked;
        if (complete && (status == Status.PENDING || status == Status.NEEDS_RESCHEDULING
                || status == Status.AWAITING_CONFIRMATION)) {
            transitionStatus(Status.SCHEDULED);
        } else if (!complete && (status == Status.SCHEDULED
                || status == Status.AWAITING_CONFIRMATION)) {
            transitionStatus(Status.PENDING);
        }
        touch();
        return this;
    }

    public TaskItem schedule(long startAt, long endAt, boolean locked) {
        requireNonNegative(startAt, "scheduledStartAt");
        requireNonNegative(endAt, "scheduledEndAt");
        if (startAt == 0) throw new IllegalArgumentException("scheduledStartAt must be greater than zero");
        if (endAt == 0) throw new IllegalArgumentException("scheduledEndAt must be greater than zero");
        return setScheduleFields(startAt, startAt, endAt, locked);
    }

    public TaskItem clearSchedule() {
        this.plannedDateAt = 0;
        this.scheduledStartAt = 0;
        this.scheduledEndAt = 0;
        this.locked = false;
        if (status == Status.SCHEDULED || status == Status.AWAITING_CONFIRMATION) {
            transitionStatus(Status.PENDING);
        }
        touch();
        return this;
    }

    /**
     * Sets a local reminder countdown without turning the task into a scheduled time block.
     * A positive interval repeats the same countdown after every trigger; zero is one-shot.
     */
    public TaskItem setCountdown(long targetAt, long repeatIntervalMillis) {
        targetAt = requireNonNegative(targetAt, "countdownTargetAt");
        repeatIntervalMillis = requireNonNegative(
                repeatIntervalMillis, "countdownIntervalMillis");
        validateCountdown(targetAt, repeatIntervalMillis);
        this.countdownTargetAt = targetAt;
        this.countdownIntervalMillis = repeatIntervalMillis;
        touch();
        return this;
    }

    public TaskItem clearCountdown() {
        if (countdownTargetAt == 0 && countdownIntervalMillis == 0) return this;
        countdownTargetAt = 0;
        countdownIntervalMillis = 0;
        touch();
        return this;
    }

    /** Advances a repeating countdown to the first occurrence strictly after {@code now}. */
    public TaskItem advanceCountdownAfter(long now) {
        requireNonNegative(now, "now");
        if (countdownTargetAt <= 0 || countdownIntervalMillis <= 0
                || countdownTargetAt > now) return this;
        long elapsed = now - countdownTargetAt;
        long steps = elapsed / countdownIntervalMillis + 1L;
        try {
            countdownTargetAt = Math.addExact(countdownTargetAt,
                    Math.multiplyExact(steps, countdownIntervalMillis));
        } catch (ArithmeticException overflow) {
            countdownTargetAt = Long.MAX_VALUE;
        }
        touch();
        return this;
    }

    public TaskItem setRecurringOccurrence(String groupId, long occurrenceAt) {
        String normalizedId = normalizeOptionalText(groupId);
        occurrenceAt = requireNonNegative(occurrenceAt, "recurringOccurrenceAt");
        validateRecurringOccurrence(normalizedId, occurrenceAt);
        recurringGroupId = normalizedId;
        recurringOccurrenceAt = occurrenceAt;
        touch();
        return this;
    }

    public TaskItem setLocked(boolean locked) {
        if (locked && !isScheduled()) {
            throw new IllegalStateException("an unscheduled task cannot be locked");
        }
        this.locked = locked;
        touch();
        return this;
    }

    public TaskItem setParentTaskId(String parentTaskId) {
        this.parentTaskId = normalizeOptionalId(parentTaskId, id);
        touch();
        return this;
    }

    public TaskItem setChildTaskIds(Collection<String> ids) {
        replaceChildren(ids);
        touch();
        return this;
    }

    public TaskItem addChildTaskId(String childTaskId) {
        String normalized = normalizeOptionalId(childTaskId, id);
        if (normalized == null) return this;
        if (!childTaskIds.contains(normalized)) {
            childTaskIds.add(normalized);
            touch();
        }
        return this;
    }

    public TaskItem removeChildTaskId(String childTaskId) {
        if (childTaskIds.remove(childTaskId)) touch();
        return this;
    }

    public TaskItem setProject(String project) {
        this.project = normalizeOptionalText(project);
        touch();
        return this;
    }

    public TaskItem setTags(Collection<String> tags) {
        replaceTags(tags);
        touch();
        return this;
    }

    public TaskItem addTag(String tag) {
        String normalized = normalizeOptionalText(tag);
        if (normalized != null && !tags.contains(normalized)) {
            tags.add(normalized);
            touch();
        }
        return this;
    }

    public TaskItem removeTag(String tag) {
        if (tags.remove(normalizeOptionalText(tag))) touch();
        return this;
    }

    public TaskItem setSource(String source) {
        this.source = normalizeSource(source);
        touch();
        return this;
    }

    public TaskItem copy() {
        return new TaskItem(id, title, description, status, priority, estimatedDurationMinutes,
                actualDurationMinutes, dueAt, plannedDateAt, scheduledStartAt, scheduledEndAt,
                countdownTargetAt, countdownIntervalMillis, recurringGroupId,
                recurringOccurrenceAt, requiresTimeArrangement, locked,
                parentTaskId, childTaskIds, project, tags, source,
                statusChangedAt, startedAt, completedAt, createdAt, updatedAt);
    }

    public JSONObject toJson() throws JSONException {
        return JsonInterop.toJsonObject(toJsonValue());
    }

    /** Package-visible portable representation used by JVM tests and the Android JSON adapter. */
    Map<String, Object> toJsonValue() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("title", title);
        json.put("description", description);
        json.put("status", status.storageValue());
        json.put("priority", priority);
        json.put("estimatedDurationMinutes", estimatedDurationMinutes);
        json.put("actualDurationMinutes", actualDurationMinutes);
        json.put("dueAt", dueAt);
        json.put("plannedDateAt", plannedDateAt);
        json.put("scheduled", isScheduled());
        json.put("scheduledStartAt", scheduledStartAt);
        json.put("scheduledEndAt", scheduledEndAt);
        json.put("countdownTargetAt", countdownTargetAt);
        json.put("countdownIntervalMillis", countdownIntervalMillis);
        json.put("recurringGroupId", recurringGroupId);
        json.put("recurringOccurrenceAt", recurringOccurrenceAt);
        json.put("requiresTimeArrangement", requiresTimeArrangement);
        json.put("locked", locked);
        json.put("parentTaskId", parentTaskId);
        json.put("childTaskIds", new ArrayList<>(childTaskIds));
        json.put("project", project);
        json.put("tags", new ArrayList<>(tags));
        json.put("source", source);
        json.put("statusChangedAt", statusChangedAt);
        json.put("startedAt", startedAt);
        json.put("completedAt", completedAt);
        json.put("createdAt", createdAt);
        json.put("updatedAt", updatedAt);
        return Collections.unmodifiableMap(json);
    }

    public static TaskItem fromJson(JSONObject json) throws JSONException {
        if (json == null) throw new JSONException("task JSON must not be null");
        long now = System.currentTimeMillis();
        String id = json.optString("id", UUID.randomUUID().toString());
        String title = json.optString("title", "").trim();
        if (title.isEmpty()) throw new JSONException("task title is required");
        int estimated = json.optInt("estimatedDurationMinutes", json.optInt("durationMinutes", 0));
        long startAt = json.optLong("scheduledStartAt", json.optLong("startAt", 0));
        long endAt = json.optLong("scheduledEndAt", 0);
        if (startAt > 0 && endAt == 0 && estimated > 0 && !json.has("scheduledEndAt")) {
            endAt = startAt + estimated * 60_000L;
        }
        long plannedDateAt = json.optLong("plannedDateAt", startAt > 0 ? startAt : endAt);
        List<String> children = new ArrayList<>();
        JSONArray childArray = json.optJSONArray("childTaskIds");
        if (childArray != null) {
            for (int i = 0; i < childArray.length(); i++) {
                String child = childArray.optString(i, "").trim();
                if (!child.isEmpty()) children.add(child);
            }
        }
        List<String> tags = new ArrayList<>();
        JSONArray tagArray = json.optJSONArray("tags");
        if (tagArray != null) {
            for (int i = 0; i < tagArray.length(); i++) {
                String tag = tagArray.optString(i, "").trim();
                if (!tag.isEmpty()) tags.add(tag);
            }
        }
        try {
            return new TaskItem(
                    id,
                    title,
                    json.optString("description", ""),
                    Status.fromStorage(json.optString("status", Status.PENDING.storageValue())),
                    json.optInt("priority", PRIORITY_NORMAL),
                    estimated,
                    json.optInt("actualDurationMinutes", 0),
                    json.optLong("dueAt", 0),
                    plannedDateAt,
                    startAt,
                    endAt,
                    json.optLong("countdownTargetAt", 0),
                    json.optLong("countdownIntervalMillis", 0),
                    optionalString(json, "recurringGroupId"),
                    json.optLong("recurringOccurrenceAt", 0),
                    json.has("requiresTimeArrangement")
                            ? json.optBoolean("requiresTimeArrangement", false)
                            : startAt > 0 || endAt > 0
                            || Status.fromStorage(json.optString(
                                    "status", Status.PENDING.storageValue()))
                            == Status.NEEDS_RESCHEDULING,
                    json.optBoolean("locked", false),
                    optionalString(json, "parentTaskId"),
                    children,
                    optionalString(json, "project"),
                    tags,
                    json.optString("source", SOURCE_IMPORTED),
                    json.optLong("statusChangedAt",
                            json.optLong("updatedAt", json.optLong("createdAt", now))),
                    json.optLong("startedAt", 0),
                    json.optLong("completedAt", 0),
                    json.optLong("createdAt", now),
                    json.optLong("updatedAt", json.optLong("createdAt", now))
            );
        } catch (IllegalArgumentException exception) {
            throw new JSONException("invalid task '" + id + "': " + exception.getMessage());
        }
    }

    private void replaceChildren(Collection<String> ids) {
        Set<String> unique = new LinkedHashSet<>();
        if (ids != null) {
            for (String childId : ids) {
                String normalized = normalizeOptionalId(childId, id);
                if (normalized != null) unique.add(normalized);
            }
        }
        childTaskIds.clear();
        childTaskIds.addAll(unique);
    }

    private void replaceTags(Collection<String> values) {
        Set<String> unique = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String normalized = normalizeOptionalText(value);
                if (normalized != null) unique.add(normalized);
            }
        }
        tags.clear();
        tags.addAll(unique);
    }

    private void touch() {
        updatedAt = Math.max(System.currentTimeMillis(), nextVersion(updatedAt));
    }

    private void transitionStatus(Status next) {
        Status previous = status;
        status = next;
        long now = System.currentTimeMillis();
        statusChangedAt = Math.max(now, nextVersion(statusChangedAt));
        if (next == Status.IN_PROGRESS) startedAt = statusChangedAt;
        if (next == Status.COMPLETED) completedAt = statusChangedAt;
        else if (previous == Status.COMPLETED) completedAt = 0;
    }

    /**
     * Marks an explicitly restored task newer than a deletion tombstone. Package-visible so the
     * repository can make undo/import a real last-write-wins resurrection without exposing
     * timestamp mutation to UI or AI callers.
     */
    public TaskItem markUpdatedAfter(long timestamp) {
        updatedAt = Math.max(
                System.currentTimeMillis(),
                Math.max(nextVersion(updatedAt), nextVersion(timestamp))
        );
        return this;
    }

    private static long nextVersion(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1;
    }

    private static String optionalString(JSONObject json, String key) {
        if (!json.has(key) || json.isNull(key)) return null;
        String value = json.optString(key, "").trim();
        return value.isEmpty() ? null : value;
    }

    private static String requireText(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        return normalized;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeSource(String source) {
        String normalized = source == null ? "" : source.trim();
        return normalized.isEmpty() ? SOURCE_MANUAL : normalized;
    }

    private static String normalizeOptionalText(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeOptionalId(String value, String ownId) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.equals(ownId)) throw new IllegalArgumentException("a task cannot reference itself");
        return normalized;
    }

    private static int clampPriority(int priority) {
        return Math.max(PRIORITY_LOW, Math.min(priority, PRIORITY_URGENT));
    }

    private static int requireNonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    private static long requireNonNegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    private static void validateSchedule(long startAt, long endAt) {
        if (startAt > 0 && endAt > 0 && endAt <= startAt) {
            throw new IllegalArgumentException("scheduledEndAt must be after scheduledStartAt");
        }
    }

    private static void validateCountdown(long targetAt, long intervalMillis) {
        if (targetAt == 0 && intervalMillis > 0) {
            throw new IllegalArgumentException(
                    "countdownIntervalMillis requires countdownTargetAt");
        }
    }

    private static void validateRecurringOccurrence(String groupId, long occurrenceAt) {
        if ((groupId == null) != (occurrenceAt == 0)) {
            throw new IllegalArgumentException(
                    "recurringGroupId and recurringOccurrenceAt must be set together");
        }
    }
}
