package com.hawson.timeassistant.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.util.UUID;

/** Durable deduplication and audit record for a proactive check-in. */
@Entity(tableName = "proactive_runs", indices = {
        @Index(value = {"localDay", "routineType"}),
        @Index(value = {"localDay", "fingerprint"})
})
public final class ProactiveRunEntity {
    public static final String STARTED = "started";
    public static final String GENERATED = "generated";
    public static final String SKIPPED_EMPTY = "skipped_empty";
    public static final String SKIPPED_DUPLICATE = "skipped_duplicate";
    public static final String SKIPPED_LIMIT = "skipped_limit";
    public static final String SKIPPED_QUIET = "skipped_quiet";
    public static final String DEFERRED_FOCUS = "deferred_focus";
    public static final String DEFERRED_USER_AI = "deferred_user_ai";
    public static final String FAILED = "failed";

    @PrimaryKey @NonNull public String id = UUID.randomUUID().toString();
    @NonNull public String routineType = "midday";
    public long localDay;
    public long scheduledAt;
    public long startedAt = System.currentTimeMillis();
    public long finishedAt;
    @NonNull public String fingerprint = "";
    @NonNull public String status = STARTED;
    public String chatMessageId;
    public long notificationSentAt;
    @NonNull public String summary = "";
    @NonNull public String error = "";
}
