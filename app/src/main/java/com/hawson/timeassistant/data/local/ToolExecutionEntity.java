package com.hawson.timeassistant.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.util.UUID;

/** Redacted audit record for context reads and external handoffs. */
@Entity(tableName = "tool_executions")
public class ToolExecutionEntity {
    public static final String RUNNING = "running";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    public static final String HANDED_OFF = "handed_off";
    public static final String CANCELLED = "cancelled";

    @PrimaryKey @NonNull public String id = UUID.randomUUID().toString();
    @NonNull public String toolId = "";
    @NonNull public String requestId = "";
    @NonNull public String status = RUNNING;
    @NonNull public String summary = "";
    @NonNull public String error = "";
    public long startedAt = System.currentTimeMillis();
    public long finishedAt;
}
