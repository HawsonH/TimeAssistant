package com.hawson.timeassistant.proactive;

import java.util.UUID;

/** A daily local-time trigger whose identity survives edits and reordering. */
public final class TodoReviewSlot {
    public final String id;
    public final int minuteOfDay;
    public final boolean enabled;

    public TodoReviewSlot(String id, int minuteOfDay, boolean enabled) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("slot id required");
        if (minuteOfDay < 0 || minuteOfDay >= 1440) throw new IllegalArgumentException("invalid minute");
        this.id = id;
        this.minuteOfDay = minuteOfDay;
        this.enabled = enabled;
    }

    public static TodoReviewSlot create(int minuteOfDay) {
        return new TodoReviewSlot(UUID.randomUUID().toString(), minuteOfDay, true);
    }

    public TodoReviewSlot withTime(int minuteOfDay) {
        return new TodoReviewSlot(id, minuteOfDay, enabled);
    }

    public TodoReviewSlot withEnabled(boolean enabled) {
        return new TodoReviewSlot(id, minuteOfDay, enabled);
    }
}
