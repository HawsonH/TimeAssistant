package com.hawson.timeassistant.domain;

import com.hawson.timeassistant.util.JsonInterop;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A single-occurrence exception that never mutates its owning recurrence rule. */
public final class RecurringOccurrenceOverride {
    public enum Type {
        SKIPPED("skipped"), RESCHEDULED("rescheduled"), MODIFIED("modified");

        private final String storageValue;

        Type(String storageValue) { this.storageValue = storageValue; }
        public String storageValue() { return storageValue; }

        static Type fromStorage(String value) {
            if (value == null) return MODIFIED;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Type type : values()) {
                if (type.storageValue.equals(normalized)
                        || type.name().toLowerCase(Locale.ROOT).equals(normalized)) return type;
            }
            return MODIFIED;
        }
    }

    private final String id;
    private final String recurringGroupId;
    private final long originalOccurrenceAt;
    private Type type;
    private String title;
    private String description;
    private long startAt;
    private long endAt;
    private final long createdAt;
    private long updatedAt;

    public static RecurringOccurrenceOverride skipped(String groupId, long occurrenceAt) {
        return new RecurringOccurrenceOverride(stableId(groupId, occurrenceAt), groupId,
                occurrenceAt, Type.SKIPPED, null, null, 0, 0,
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    public static RecurringOccurrenceOverride rescheduled(
            String groupId, long occurrenceAt, long startAt, long endAt) {
        return new RecurringOccurrenceOverride(stableId(groupId, occurrenceAt), groupId,
                occurrenceAt, Type.RESCHEDULED, null, null, startAt, endAt,
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    public static RecurringOccurrenceOverride modified(
            String groupId, long occurrenceAt, String title, String description,
            long startAt, long endAt) {
        return new RecurringOccurrenceOverride(stableId(groupId, occurrenceAt), groupId,
                occurrenceAt, Type.MODIFIED, normalizeOptional(title),
                description, startAt, endAt,
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    private RecurringOccurrenceOverride(
            String id, String recurringGroupId, long originalOccurrenceAt, Type type,
            String title, String description, long startAt, long endAt,
            long createdAt, long updatedAt) {
        this.id = requireText(id, "id");
        this.recurringGroupId = requireText(recurringGroupId, "recurringGroupId");
        if (originalOccurrenceAt <= 0) {
            throw new IllegalArgumentException("originalOccurrenceAt must be positive");
        }
        validateTimes(startAt, endAt);
        Type resolved = type == null ? Type.MODIFIED : type;
        if (resolved == Type.RESCHEDULED && (startAt <= 0 || endAt <= 0)) {
            throw new IllegalArgumentException("rescheduled override requires a complete time block");
        }
        this.originalOccurrenceAt = originalOccurrenceAt;
        this.type = resolved;
        this.title = normalizeOptional(title);
        this.description = description;
        this.startAt = startAt;
        this.endAt = endAt;
        long now = System.currentTimeMillis();
        this.createdAt = createdAt > 0 ? createdAt : now;
        this.updatedAt = Math.max(updatedAt > 0 ? updatedAt : this.createdAt, this.createdAt);
    }

    public String getId() { return id; }
    public String getRecurringGroupId() { return recurringGroupId; }
    public long getOriginalOccurrenceAt() { return originalOccurrenceAt; }
    public Type getType() { return type; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public long getStartAt() { return startAt; }
    public long getEndAt() { return endAt; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public boolean isSkipped() { return type == Type.SKIPPED; }
    public boolean hasTimeOverride() { return startAt > 0 && endAt > 0; }

    public String effectiveTitle(RecurringTaskGroup group) {
        return title == null ? group.getTitle() : title;
    }

    public String effectiveDescription(RecurringTaskGroup group) {
        return description == null ? group.getDescription() : description;
    }

    public long effectiveStartAt(long fallback) { return hasTimeOverride() ? startAt : fallback; }

    public long effectiveEndAt(long fallbackStart, long fallbackDuration) {
        return hasTimeOverride() ? endAt : Math.addExact(fallbackStart, fallbackDuration);
    }

    public RecurringOccurrenceOverride copy() {
        return new RecurringOccurrenceOverride(id, recurringGroupId, originalOccurrenceAt,
                type, title, description, startAt, endAt, createdAt, updatedAt);
    }

    public RecurringOccurrenceOverride markUpdatedAfter(long timestamp) {
        updatedAt = Math.max(updatedAt, timestamp == Long.MAX_VALUE
                ? Long.MAX_VALUE : timestamp + 1L);
        return this;
    }

    public JSONObject toJson() throws JSONException {
        return JsonInterop.toJsonObject(toJsonValue());
    }

    Map<String, Object> toJsonValue() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("recurringGroupId", recurringGroupId);
        json.put("originalOccurrenceAt", originalOccurrenceAt);
        json.put("type", type.storageValue());
        json.put("title", title);
        json.put("description", description);
        json.put("startAt", startAt);
        json.put("endAt", endAt);
        json.put("createdAt", createdAt);
        json.put("updatedAt", updatedAt);
        return Collections.unmodifiableMap(json);
    }

    public static RecurringOccurrenceOverride fromJson(JSONObject json) throws JSONException {
        if (json == null) throw new JSONException("recurring override JSON must not be null");
        long now = System.currentTimeMillis();
        String groupId = json.optString("recurringGroupId", "");
        long occurrenceAt = json.optLong("originalOccurrenceAt", 0);
        try {
            return new RecurringOccurrenceOverride(
                    json.optString("id", stableId(groupId, occurrenceAt)), groupId,
                    occurrenceAt, Type.fromStorage(json.optString("type", "modified")),
                    optionalString(json, "title"), optionalString(json, "description"),
                    json.optLong("startAt", 0), json.optLong("endAt", 0),
                    json.optLong("createdAt", now),
                    json.optLong("updatedAt", json.optLong("createdAt", now)));
        } catch (IllegalArgumentException error) {
            throw new JSONException("invalid recurring override: " + error.getMessage());
        }
    }

    public static String stableId(String groupId, long occurrenceAt) {
        return (groupId == null ? "" : groupId.trim()) + "@" + occurrenceAt;
    }

    private static void validateTimes(long startAt, long endAt) {
        if (startAt < 0 || endAt < 0) throw new IllegalArgumentException("times cannot be negative");
        if ((startAt == 0) != (endAt == 0)) {
            throw new IllegalArgumentException("time override must contain both start and end");
        }
        if (startAt > 0 && endAt <= startAt) {
            throw new IllegalArgumentException("endAt must be later than startAt");
        }
    }

    private static String optionalString(JSONObject json, String key) {
        return json.has(key) && !json.isNull(key) ? json.optString(key, "") : null;
    }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String requireText(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
