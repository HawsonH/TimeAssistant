package com.hawson.timeassistant.data;

import android.os.Looper;

/** Background-only bridge for LAN transport code; export/import JSON remains protocol-compatible. */
public final class SyncSnapshotGateway {
    private final AppRepository repository;

    public SyncSnapshotGateway(AppRepository repository) {
        if (repository == null) throw new IllegalArgumentException("repository must not be null");
        this.repository = repository;
    }

    public String exportSnapshot() {
        requireBackgroundThread();
        return repository.createSyncSnapshot();
    }

    public void mergeSnapshot(String snapshot) {
        requireBackgroundThread();
        repository.importJson(snapshot, AppRepository.ImportMode.MERGE);
    }

    private static void requireBackgroundThread() {
        if (Looper.getMainLooper() == Looper.myLooper()) {
            throw new IllegalStateException("SyncSnapshotGateway must be used off the main thread");
        }
    }
}
