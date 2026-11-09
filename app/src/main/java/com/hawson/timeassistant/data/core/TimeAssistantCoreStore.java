package com.hawson.timeassistant.data.core;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.room.Room;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Blocking Room gateway. Callers must use it from their own background queue; it deliberately
 * exposes no asynchronous UI API so Room work can never accidentally happen on the main thread.
 */
public final class TimeAssistantCoreStore {
    public static final String STATE_ID = "core";
    public static final String MIGRATION_READY = "ready";
    public static final String MIGRATION_RECOVERY_REQUIRED = "recovery_required";
    private static final int SNAPSHOT_SCHEMA_VERSION = 4;

    private final TimeAssistantCoreDatabase database;

    public TimeAssistantCoreStore(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;
        database = Room.databaseBuilder(appContext, TimeAssistantCoreDatabase.class,
                TimeAssistantCoreDatabase.DATABASE_NAME).build();
    }

    public TimeAssistantCoreDatabase getDatabase() {
        return database;
    }

    @Nullable
    public JSONObject loadSnapshot() throws JSONException {
        assertBackgroundThread();
        TimeAssistantCoreDao dao = database.repositoryDao();
        RepositoryStateEntity state = dao.loadState(STATE_ID);
        if (state == null) return null;
        return RepositorySnapshotCodec.rehydrate(dao.loadDocuments(), dao.loadTombstones(),
                Math.max(1, state.schemaVersion), state.updatedAt);
    }

    @Nullable
    public JSONObject loadBackupSnapshot() throws JSONException {
        assertBackgroundThread();
        RepositoryBackupEntity backup = database.repositoryDao().loadLatestBackup();
        return backup == null ? null : new JSONObject(backup.snapshotJson);
    }

    public String getMigrationState() {
        assertBackgroundThread();
        RepositoryStateEntity state = database.repositoryDao().loadState(STATE_ID);
        return state == null ? null : state.migrationState;
    }

    public long getRevision() {
        assertBackgroundThread();
        RepositoryStateEntity state = database.repositoryDao().loadState(STATE_ID);
        return state == null ? 0L : state.revision;
    }

    public long replaceSnapshot(JSONObject snapshot) throws JSONException {
        assertBackgroundThread();
        RepositorySnapshotCodec.MaterializedSnapshot materialized =
                RepositorySnapshotCodec.materialize(snapshot);
        final String raw = snapshot.toString();
        final long now = System.currentTimeMillis();
        final long[] revisionHolder = new long[1];
        database.runInTransaction(() -> {
            TimeAssistantCoreDao dao = database.repositoryDao();
            RepositoryStateEntity prior = dao.loadState(STATE_ID);
            long revision = prior == null ? 1L : Math.max(1L, prior.revision + 1L);
            dao.clearDocuments();
            dao.clearTombstones();
            if (!materialized.documents.isEmpty()) dao.insertDocuments(materialized.documents);
            if (!materialized.tombstones.isEmpty()) dao.insertTombstones(materialized.tombstones);
            dao.clearBackups();
            dao.insertBackup(new RepositoryBackupEntity(revision, raw, now));
            dao.insertState(new RepositoryStateEntity(STATE_ID, SNAPSHOT_SCHEMA_VERSION, revision,
                    MIGRATION_READY, now));
            // A failed read-back must roll back the documents and revision, not report failure
            // after the transaction is already durable.
            try {
                if (loadSnapshot() == null) throw new JSONException("core snapshot read-back failed");
            } catch (JSONException failure) {
                throw new IllegalStateException("core snapshot read-back failed", failure);
            }
            revisionHolder[0] = revision;
        });
        return revisionHolder[0];
    }

    public void markRecoveryRequired() {
        assertBackgroundThread();
        database.runInTransaction(() -> {
            TimeAssistantCoreDao dao = database.repositoryDao();
            RepositoryStateEntity prior = dao.loadState(STATE_ID);
            long revision = prior == null ? 0L : prior.revision;
            dao.insertState(new RepositoryStateEntity(STATE_ID, SNAPSHOT_SCHEMA_VERSION, revision,
                    MIGRATION_RECOVERY_REQUIRED, System.currentTimeMillis()));
        });
    }

    public void close() {
        database.close();
    }

    private static void assertBackgroundThread() {
        if (android.os.Looper.getMainLooper() == android.os.Looper.myLooper()) {
            throw new IllegalStateException("TimeAssistantCoreStore must be called off the main thread");
        }
    }
}
