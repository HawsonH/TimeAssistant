package com.hawson.timeassistant.data.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface LocalAiDao {
    @Query("SELECT * FROM preference_rules ORDER BY updatedAt DESC")
    List<PreferenceRuleEntity> allPreferences();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putPreference(PreferenceRuleEntity rule);

    @Query("DELETE FROM preference_rules WHERE id = :id")
    void deletePreference(String id);

    @Query("DELETE FROM preference_rules WHERE origin = 'inferred'")
    void deleteInferredPreferences();

    @Query("DELETE FROM preference_rules")
    void deleteAllPreferences();

    @Query("UPDATE preference_rules SET status = 'expired', updatedAt = :now "
            + "WHERE status = 'candidate' AND expiresAt > 0 AND expiresAt <= :now")
    void expireCandidates(long now);

    @Query("SELECT * FROM behavior_events WHERE analyzedAt = 0 AND occurredAt >= :since "
            + "ORDER BY occurredAt ASC LIMIT :limit")
    List<BehaviorEventEntity> unanalyzedEvents(long since, int limit);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertBehavior(BehaviorEventEntity event);

    @Query("UPDATE behavior_events SET analyzedAt = :analyzedAt WHERE id IN (:ids)")
    void markAnalyzed(List<String> ids, long analyzedAt);

    @Query("DELETE FROM behavior_events WHERE occurredAt < :before")
    void pruneBehavior(long before);

    @Query("DELETE FROM behavior_events")
    void deleteBehaviorEvents();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putToolExecution(ToolExecutionEntity execution);

    @Query("SELECT * FROM tool_executions ORDER BY startedAt DESC LIMIT :limit")
    List<ToolExecutionEntity> recentToolExecutions(int limit);

    @Query("DELETE FROM tool_executions WHERE startedAt < :before")
    void pruneToolExecutions(long before);

    @Query("DELETE FROM tool_executions")
    void deleteToolExecutions();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putProactiveRun(ProactiveRunEntity run);

    @Query("SELECT COUNT(*) FROM proactive_runs WHERE localDay = :localDay AND status = 'generated'")
    int proactiveGeneratedCount(long localDay);

    @Query("SELECT COUNT(*) FROM proactive_runs WHERE localDay = :localDay "
            + "AND fingerprint = :fingerprint "
            + "AND status = 'generated'")
    int proactiveFingerprintCount(long localDay, String fingerprint);

    @Query("SELECT COUNT(*) FROM proactive_runs WHERE localDay = :localDay "
            + "AND routineType = :routineType AND status = 'generated'")
    int proactiveRoutineGeneratedCount(long localDay, String routineType);

    @Query("SELECT * FROM proactive_runs WHERE localDay = :localDay ORDER BY startedAt DESC")
    List<ProactiveRunEntity> proactiveRunsForDay(long localDay);

    @Query("DELETE FROM proactive_runs WHERE startedAt < :before")
    void pruneProactiveRuns(long before);

    @Query("DELETE FROM proactive_runs")
    void deleteProactiveRuns();
}
