package com.hawson.timeassistant.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.util.UUID;

/** Device-local scheduling preference. Existing repository snapshots intentionally exclude it. */
@Entity(tableName = "preference_rules")
public class PreferenceRuleEntity {
    public static final String TYPE_PREFERRED_WINDOW = "preferred_window";
    public static final String TYPE_AVOID_WINDOW = "avoid_window";
    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_PROJECT = "project";
    public static final String SCOPE_TAG = "tag";
    public static final String ORIGIN_EXPLICIT = "explicit";
    public static final String ORIGIN_INFERRED = "inferred";
    public static final String STATUS_CANDIDATE = "candidate";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_EXPIRED = "expired";

    @PrimaryKey @NonNull public String id = UUID.randomUUID().toString();
    @NonNull public String type = TYPE_PREFERRED_WINDOW;
    @NonNull public String scopeType = SCOPE_GLOBAL;
    @NonNull public String scopeValue = "";
    @NonNull public String origin = ORIGIN_EXPLICIT;
    @NonNull public String status = STATUS_ACTIVE;
    /** ISO weekday numbers (1=Monday), comma-separated; empty means every day. */
    @NonNull public String daysOfWeek = "";
    public int startMinute;
    public int endMinute;
    public double confidence = 1d;
    /** Comma-separated opaque event ids. */
    @NonNull public String evidenceRefs = "";
    @NonNull public String reason = "";
    public long createdAt = System.currentTimeMillis();
    public long updatedAt = createdAt;
    public long expiresAt;
    public long rejectedUntil;

    public PreferenceRuleEntity copy() {
        PreferenceRuleEntity value = new PreferenceRuleEntity();
        value.id = id; value.type = type; value.scopeType = scopeType;
        value.scopeValue = scopeValue; value.origin = origin; value.status = status;
        value.daysOfWeek = daysOfWeek; value.startMinute = startMinute;
        value.endMinute = endMinute; value.confidence = confidence;
        value.evidenceRefs = evidenceRefs; value.reason = reason;
        value.createdAt = createdAt; value.updatedAt = updatedAt;
        value.expiresAt = expiresAt; value.rejectedUntil = rejectedUntil;
        return value;
    }
}
