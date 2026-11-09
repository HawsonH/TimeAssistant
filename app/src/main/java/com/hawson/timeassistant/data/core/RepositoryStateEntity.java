package com.hawson.timeassistant.data.core;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Singleton metadata row for the core repository database. */
@Entity(tableName = "repository_state")
public final class RepositoryStateEntity {
    @PrimaryKey @NonNull public final String id;
    public final int schemaVersion;
    public final long revision;
    @NonNull public final String migrationState;
    public final long updatedAt;

    public RepositoryStateEntity(@NonNull String id, int schemaVersion, long revision,
                                 @NonNull String migrationState, long updatedAt) {
        this.id = id;
        this.schemaVersion = schemaVersion;
        this.revision = revision;
        this.migrationState = migrationState;
        this.updatedAt = updatedAt;
    }
}
