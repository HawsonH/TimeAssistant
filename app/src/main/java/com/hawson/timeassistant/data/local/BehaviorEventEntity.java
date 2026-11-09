package com.hawson.timeassistant.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.util.UUID;

/** Redacted behavior evidence. Never stores task titles, descriptions, chat, or attachments. */
@Entity(tableName = "behavior_events")
public class BehaviorEventEntity {
    public static final String TASK_RESCHEDULED = "task_rescheduled";
    public static final String AI_ACTION_CONFIRMED = "ai_action_confirmed";
    public static final String AI_ACTION_CANCELLED = "ai_action_cancelled";
    public static final String AI_ACTION_REVERTED = "ai_action_reverted";
    public static final String FOCUS_INTERRUPTED = "focus_interrupted";

    @PrimaryKey @NonNull public String id = UUID.randomUUID().toString();
    @NonNull public String type = TASK_RESCHEDULED;
    public long occurredAt = System.currentTimeMillis();
    /** Local start-of-day epoch. */
    public long localDay;
    public int oldStartMinute = -1;
    public int oldEndMinute = -1;
    public int newStartMinute = -1;
    public int newEndMinute = -1;
    @NonNull public String project = "";
    /** Comma-separated, normalized tags. */
    @NonNull public String tags = "";
    @NonNull public String outcome = "";
    @NonNull public String source = "local";
    public long analyzedAt;
}
