package com.hawson.timeassistant.data.core;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Last known-good, portable full snapshot. */
@Entity(tableName = "repository_backups")
public final class RepositoryBackupEntity {
    @PrimaryKey public final long revision;
    @NonNull public final String snapshotJson;
    public final long createdAt;

    public RepositoryBackupEntity(long revision, @NonNull String snapshotJson, long createdAt) {
        this.revision = revision;
        this.snapshotJson = snapshotJson;
        this.createdAt = createdAt;
    }
}
