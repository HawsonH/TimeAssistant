package com.hawson.timeassistant.data.local;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Async Room facade with a small read cache so rendering never blocks the main thread. */
public final class LocalIntelligenceStore implements AutoCloseable {
    public interface Callback<T> { void onResult(T value); }

    private static final long DAY = 86_400_000L;
    private final LocalAiDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile List<PreferenceRuleEntity> preferenceCache = Collections.emptyList();

    public LocalIntelligenceStore(Context context) {
        dao = LocalAiDatabase.get(context).dao();
        refreshPreferences(null);
        executor.execute(() -> {
            long now = System.currentTimeMillis();
            dao.pruneBehavior(now - 180L * DAY);
            dao.pruneToolExecutions(now - 180L * DAY);
            dao.pruneProactiveRuns(now - 90L * DAY);
            dao.expireCandidates(now);
            refreshPreferences(null);
        });
    }

    public List<PreferenceRuleEntity> preferences() {
        List<PreferenceRuleEntity> copy = new ArrayList<>();
        for (PreferenceRuleEntity rule : preferenceCache) copy.add(rule.copy());
        return Collections.unmodifiableList(copy);
    }

    public void refreshPreferences(Callback<List<PreferenceRuleEntity>> callback) {
        executor.execute(() -> {
            List<PreferenceRuleEntity> loaded = dao.allPreferences();
            List<PreferenceRuleEntity> safe = new ArrayList<>();
            for (PreferenceRuleEntity rule : loaded) safe.add(rule.copy());
            preferenceCache = Collections.unmodifiableList(safe);
            if (callback != null) main.post(() -> callback.onResult(preferences()));
        });
    }

    public void putPreference(PreferenceRuleEntity rule, Runnable completion) {
        if (!PreferencePolicy.isValid(rule)) throw new IllegalArgumentException("invalid preference rule");
        PreferenceRuleEntity copy = rule.copy();
        copy.updatedAt = System.currentTimeMillis();
        executor.execute(() -> {
            dao.putPreference(copy);
            preferenceCache = Collections.unmodifiableList(new ArrayList<>(dao.allPreferences()));
            if (completion != null) main.post(completion);
        });
    }

    public void putPreferences(List<PreferenceRuleEntity> rules, Runnable completion) {
        List<PreferenceRuleEntity> copies = new ArrayList<>();
        for (PreferenceRuleEntity rule : rules == null
                ? Collections.<PreferenceRuleEntity>emptyList() : rules) {
            if (!PreferencePolicy.isValid(rule)) throw new IllegalArgumentException("invalid preference rule");
            PreferenceRuleEntity copy = rule.copy();
            copy.updatedAt = System.currentTimeMillis();
            copies.add(copy);
        }
        executor.execute(() -> {
            for (PreferenceRuleEntity rule : copies) dao.putPreference(rule);
            preferenceCache = Collections.unmodifiableList(new ArrayList<>(dao.allPreferences()));
            if (completion != null) main.post(completion);
        });
    }

    public void deletePreference(String id, Runnable completion) {
        executor.execute(() -> {
            dao.deletePreference(id);
            preferenceCache = Collections.unmodifiableList(new ArrayList<>(dao.allPreferences()));
            if (completion != null) main.post(completion);
        });
    }

    public void recordBehavior(BehaviorEventEntity event) {
        if (event == null) return;
        executor.execute(() -> dao.insertBehavior(event));
    }

    public void loadUnanalyzedEvents(long since, int limit,
                                     Callback<List<BehaviorEventEntity>> callback) {
        executor.execute(() -> {
            List<BehaviorEventEntity> values = dao.unanalyzedEvents(since, Math.min(200, Math.max(1, limit)));
            main.post(() -> callback.onResult(Collections.unmodifiableList(values)));
        });
    }

    public void markAnalyzed(List<String> ids, long at) {
        if (ids == null || ids.isEmpty()) return;
        executor.execute(() -> dao.markAnalyzed(new ArrayList<>(ids), at));
    }

    public void putToolExecution(ToolExecutionEntity execution) {
        if (execution != null) executor.execute(() -> dao.putToolExecution(execution));
    }

    public void clearLearningData(Runnable completion) {
        executor.execute(() -> {
            dao.deleteBehaviorEvents();
            dao.deleteInferredPreferences();
            preferenceCache = Collections.unmodifiableList(new ArrayList<>(dao.allPreferences()));
            if (completion != null) main.post(completion);
        });
    }

    public void clearAll(Runnable completion) {
        executor.execute(() -> {
            dao.deleteBehaviorEvents();
            dao.deleteAllPreferences();
            dao.deleteToolExecutions();
            dao.deleteProactiveRuns();
            preferenceCache = Collections.emptyList();
            if (completion != null) main.post(completion);
        });
    }

    @Override public void close() { executor.shutdownNow(); }
}
