package com.hawson.timeassistant.data.core;

import androidx.annotation.NonNull;
import androidx.room.Entity;

/** A deletion marker retained for LAN conflict resolution. */
@Entity(tableName = "repository_tombstones", primaryKeys = {"type", "id"})
public final class RepositoryTombstoneEntity {
    @NonNull public final String type;
    @NonNull public final String id;
    public final long deletedAt;

    public RepositoryTombstoneEntity(@NonNull String type, @NonNull String id, long deletedAt) {
        this.type = type;
        this.id = id;
        this.deletedAt = deletedAt;
    }
}
