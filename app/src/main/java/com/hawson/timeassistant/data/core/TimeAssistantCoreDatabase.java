package com.hawson.timeassistant.data.core;

import androidx.room.Database;
import androidx.room.RoomDatabase;

/** Separate Room database for user-owned tasks, conversations, focus and settings data. */
@Database(
        entities = {
                RepositoryDocumentEntity.class,
                RepositoryTombstoneEntity.class,
                RepositoryStateEntity.class,
                RepositoryBackupEntity.class
        },
        version = 1,
        exportSchema = true
)
public abstract class TimeAssistantCoreDatabase extends RoomDatabase {
    public static final String DATABASE_NAME = "time_assistant_core";

    public abstract TimeAssistantCoreDao repositoryDao();
}
