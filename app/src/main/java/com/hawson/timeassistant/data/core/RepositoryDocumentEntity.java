package com.hawson.timeassistant.data.core;

import androidx.annotation.NonNull;
import androidx.room.Entity;

/** A complete JSON representation of one synchronisable domain aggregate. */
@Entity(tableName = "repository_documents", primaryKeys = {"type", "id"})
public final class RepositoryDocumentEntity {
    @NonNull public final String type;
    @NonNull public final String id;
    public final long updatedAt;
    @NonNull public final String payloadJson;

    public RepositoryDocumentEntity(@NonNull String type, @NonNull String id, long updatedAt,
                                    @NonNull String payloadJson) {
        this.type = type;
        this.id = id;
        this.updatedAt = updatedAt;
        this.payloadJson = payloadJson;
    }
}
