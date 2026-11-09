package com.hawson.timeassistant.data.core;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TimeAssistantCoreDao {
    @Query("SELECT * FROM repository_documents ORDER BY type, id")
    List<RepositoryDocumentEntity> loadDocuments();

    @Query("SELECT * FROM repository_tombstones ORDER BY type, id")
    List<RepositoryTombstoneEntity> loadTombstones();

    @Query("SELECT * FROM repository_state WHERE id = :id LIMIT 1")
    RepositoryStateEntity loadState(String id);

    @Query("SELECT * FROM repository_backups ORDER BY revision DESC LIMIT 1")
    RepositoryBackupEntity loadLatestBackup();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertDocuments(List<RepositoryDocumentEntity> rows);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertTombstones(List<RepositoryTombstoneEntity> rows);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertState(RepositoryStateEntity row);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertBackup(RepositoryBackupEntity row);

    @Query("DELETE FROM repository_documents")
    void clearDocuments();

    @Query("DELETE FROM repository_tombstones")
    void clearTombstones();

    @Query("DELETE FROM repository_backups")
    void clearBackups();
}
