package com.hawson.timeassistant.domain;

import com.hawson.timeassistant.util.JsonInterop;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Persisted business reminder; Android notifications are only a delivery surface for it. */
public final class ReminderEvent {
    public enum TargetType {
        TASK("task"), FOCUS("focus"), REVIEW("review");
        private final String value;
        TargetType(String value) { this.value = value; }
        public String storageValue() { return value; }
        static TargetType fromStorage(String raw) {
            if (raw != null) for (TargetType item : values()) {
                if (item.value.equalsIgnoreCase(raw) || item.name().equalsIgnoreCase(raw)) return item;
            }
            return TASK;
        }
    }

    public enum State {
        ACTION_REQUIRED("action_required"), SNOOZED("snoozed"), HANDLED("handled");
        private final String value;
        State(String value) { this.value = value; }
        public String storageValue() { return value; }
        static State fromStorage(String raw) {
            if (raw != null) for (State item : values()) {
                if (item.value.equalsIgnoreCase(raw) || item.name().equalsIgnoreCase(raw)) return item;
            }
            return ACTION_REQUIRED;
        }
    }

    private final String id;
    private final TargetType targetType;
    private final String targetId;
    private final String reason;
    private String title;
    private final long triggerAt;
    private State state;
    private long snoozedUntil;
    private long handledAt;
    private final long createdAt;
    private long updatedAt;
    private String deliveryState = "pending";
    private long presentedAt;

    public static ReminderEvent triggered(TargetType targetType, String targetId, String reason,
                                          String title, long triggerAt) {
        return new ReminderEvent(stableId(targetType, targetId, reason, triggerAt),
                targetType, targetId, reason, title, triggerAt,
                State.ACTION_REQUIRED, 0, 0,
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    private ReminderEvent(String id, TargetType targetType, String targetId, String reason,
                          String title, long triggerAt, State state, long snoozedUntil,
                          long handledAt, long createdAt, long updatedAt) {
        this.id = requireText(id, "id");
        this.targetType = targetType == null ? TargetType.TASK : targetType;
        this.targetId = normalizeOptional(targetId);
        this.reason = requireText(reason, "reason").toLowerCase(Locale.ROOT);
        this.title = requireText(title, "title");
        if (triggerAt <= 0) throw new IllegalArgumentException("triggerAt must be positive");
        this.triggerAt = triggerAt;
        this.state = state == null ? State.ACTION_REQUIRED : state;
        this.snoozedUntil = Math.max(0, snoozedUntil);
        this.handledAt = Math.max(0, handledAt);
        if (this.state == State.SNOOZED && this.snoozedUntil <= 0) {
            throw new IllegalArgumentException("snoozed reminder requires snoozedUntil");
        }
        long now = System.currentTimeMillis();
        this.createdAt = createdAt > 0 ? createdAt : now;
        this.updatedAt = Math.max(updatedAt > 0 ? updatedAt : this.createdAt, this.createdAt);
    }

    public String getId() { return id; }
    public TargetType getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getReason() { return reason; }
    public String getTitle() { return title; }
    public long getTriggerAt() { return triggerAt; }
    public State getState() { return state; }
    public long getSnoozedUntil() { return snoozedUntil; }
    public long getHandledAt() { return handledAt; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public String getDeliveryState() { return deliveryState; }
    public long getPresentedAt() { return presentedAt; }

    public ReminderEvent deliveryState(String value) {
        if (!java.util.Arrays.asList("pending", "posted", "blocked", "failed").contains(value))
            throw new IllegalArgumentException("invalid delivery state");
        deliveryState = value; touch(); return this;
    }

    public ReminderEvent markPresented(long now) {
        presentedAt = Math.max(1, now); touch(); return this;
    }

    public ReminderEvent snoozeUntil(long value) {
        if (value <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("snoozedUntil must be in the future");
        }
        state = State.SNOOZED;
        snoozedUntil = value;
        handledAt = 0;
        touch();
        return this;
    }

    public ReminderEvent makeActionRequired() {
        state = State.ACTION_REQUIRED;
        snoozedUntil = 0;
        handledAt = 0;
        touch();
        return this;
    }

    public ReminderEvent markHandled(long value) {
        state = State.HANDLED;
        snoozedUntil = 0;
        handledAt = value > 0 ? value : System.currentTimeMillis();
        touch();
        return this;
    }

    public ReminderEvent setTitle(String value) {
        title = requireText(value, "title");
        touch();
        return this;
    }

    public ReminderEvent copy() {
        ReminderEvent copy = new ReminderEvent(id, targetType, targetId, reason, title, triggerAt,
                state, snoozedUntil, handledAt, createdAt, updatedAt);
        copy.deliveryState = deliveryState; copy.presentedAt = presentedAt;
        return copy;
    }

    public JSONObject toJson() throws JSONException {
        return JsonInterop.toJsonObject(toJsonValue());
    }

    Map<String, Object> toJsonValue() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("targetType", targetType.storageValue());
        json.put("targetId", targetId);
        json.put("reason", reason);
        json.put("title", title);
        json.put("triggerAt", triggerAt);
        json.put("state", state.storageValue());
        json.put("snoozedUntil", snoozedUntil);
        json.put("handledAt", handledAt);
        json.put("createdAt", createdAt);
        json.put("updatedAt", updatedAt);
        json.put("deliveryState", deliveryState);
        json.put("presentedAt", presentedAt);
        return Collections.unmodifiableMap(json);
    }

    public static ReminderEvent fromJson(JSONObject json) throws JSONException {
        if (json == null) throw new JSONException("reminder event JSON must not be null");
        long now = System.currentTimeMillis();
        try {
            TargetType targetType = TargetType.fromStorage(json.optString("targetType", "task"));
            String targetId = optionalString(json, "targetId");
            String reason = json.optString("reason", "reminder");
            long triggerAt = json.optLong("triggerAt", 0);
            ReminderEvent result = new ReminderEvent(
                    json.optString("id", stableId(targetType, targetId, reason, triggerAt)),
                    targetType, targetId, reason, json.optString("title", "提醒"), triggerAt,
                    State.fromStorage(json.optString("state", "action_required")),
                    json.optLong("snoozedUntil", 0), json.optLong("handledAt", 0),
                    json.optLong("createdAt", now),
                    json.optLong("updatedAt", json.optLong("createdAt", now)));
            // Older snapshots already delivered their reminders; never replay them on upgrade.
            String delivery = json.optString("deliveryState", "posted");
            if (!"pending".equals(delivery) && !"posted".equals(delivery)
                    && !"blocked".equals(delivery) && !"failed".equals(delivery))
                throw new IllegalArgumentException("invalid delivery state");
            result.deliveryState = delivery;
            result.presentedAt = json.optLong("presentedAt", result.createdAt);
            if (result.presentedAt < 0) throw new IllegalArgumentException("invalid presentation time");
            return result;
        } catch (IllegalArgumentException error) {
            throw new JSONException("invalid reminder event: " + error.getMessage());
        }
    }

    public static String stableId(TargetType type, String targetId, String reason, long triggerAt) {
        return (type == null ? TargetType.TASK.storageValue() : type.storageValue()) + ':'
                + (targetId == null ? "" : targetId.trim()) + ':'
                + (reason == null ? "reminder" : reason.trim().toLowerCase(Locale.ROOT)) + ':'
                + triggerAt;
    }

    private void touch() {
        updatedAt = Math.max(System.currentTimeMillis(),
                updatedAt == Long.MAX_VALUE ? Long.MAX_VALUE : updatedAt + 1L);
    }

    private static String optionalString(JSONObject json, String key) {
        return json.has(key) && !json.isNull(key) ? normalizeOptional(json.optString(key, "")) : null;
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
