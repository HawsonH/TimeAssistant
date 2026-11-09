package com.hawson.timeassistant.data.local;

import android.content.Context;
import android.content.SharedPreferences;

import com.hawson.timeassistant.proactive.ProactiveSettings;
import com.hawson.timeassistant.proactive.TodoReviewSlot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Unsynced, unexported local switches and daily-analysis cursors. */
public final class LocalIntegrationSettingsStore {
    /** Serializes configuration changes with proactive dispatch and publication. */
    public static final Object PROACTIVE_LOCK = new Object();
    private static final String REVIEW_COUNT = "proactive_todo_review_count";
    private static final String GENERATION = "proactive_schedule_generation";
    private static final String PREFS = "local_intelligence_settings";
    private static final String LEARNING = "ai_learning_enabled";
    private static final String CALENDAR = "calendar_experiment_enabled";
    private static final String ANALYSIS_DAY = "last_analysis_day";
    private static final String ANALYSIS_SUCCESS = "last_analysis_success_at";
    private static final String PROACTIVE_ENABLED = "proactive_enabled";
    private static final String PROACTIVE_NOTIFICATION = "proactive_notification";
    private static final int CONSENT_VERSION = 2;
    private final SharedPreferences preferences;

    public LocalIntegrationSettingsStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    LocalIntegrationSettingsStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public boolean isLearningEnabled() {
        return preferences.getInt("learning_consent_version", 0) == CONSENT_VERSION
                && preferences.getBoolean(LEARNING, false);
    }
    public void grantLearningConsent() {
        preferences.edit().putInt("learning_consent_version", CONSENT_VERSION)
                .putBoolean(LEARNING, true).apply();
    }
    public void grantProactiveConsent() {
        synchronized (PROACTIVE_LOCK) {
            preferences.edit().putInt("proactive_consent_version", CONSENT_VERSION).apply();
        }
    }
    public void setLearningEnabled(boolean value) { preferences.edit().putBoolean(LEARNING, value).apply(); }
    public boolean isCalendarExperimentEnabled() { return preferences.getBoolean(CALENDAR, false); }
    public void setCalendarExperimentEnabled(boolean value) { preferences.edit().putBoolean(CALENDAR, value).apply(); }
    public long getLastAnalysisDay() { return preferences.getLong(ANALYSIS_DAY, 0); }
    public long getLastAnalysisSuccessAt() { return preferences.getLong(ANALYSIS_SUCCESS, 0); }
    public void markAnalysisComplete(long localDay, long at) {
        preferences.edit().putLong(ANALYSIS_DAY, localDay)
                .putLong(ANALYSIS_SUCCESS, at).apply();
    }
    public void clearAnalysisCursor() {
        preferences.edit().remove(ANALYSIS_DAY).remove(ANALYSIS_SUCCESS).apply();
    }

    public ProactiveSettings getProactiveSettings() {
        synchronized (PROACTIVE_LOCK) {
            return readProactiveSettings();
        }
    }

    private ProactiveSettings readProactiveSettings() {
        migrateTodoReviews();
        ProactiveSettings value = new ProactiveSettings();
        value.enabled = preferences.getInt("proactive_consent_version", 0) == CONSENT_VERSION
                && preferences.getBoolean(PROACTIVE_ENABLED, false);
        value.morningEnabled = preferences.getBoolean("proactive_morning_enabled", true);
        value.middayEnabled = preferences.getBoolean("proactive_midday_enabled", true);
        value.afternoonEnabled = preferences.getBoolean("proactive_afternoon_enabled", true);
        value.eveningEnabled = preferences.getBoolean("proactive_evening_enabled", true);
        value.morningMinute = preferences.getInt("proactive_morning_minute", 8 * 60 + 30);
        value.middayMinute = preferences.getInt("proactive_midday_minute", 12 * 60 + 30);
        value.afternoonMinute = preferences.getInt("proactive_afternoon_minute", 16 * 60 + 30);
        value.eveningMinute = preferences.getInt("proactive_evening_minute", 21 * 60);
        value.attentionTriggerEnabled = preferences.getBoolean("proactive_attention", true);
        value.middayTodoPlanningEnabled = preferences.getBoolean("proactive_midday_todos", true);
        value.todoReviewEnabled = preferences.getBoolean("proactive_todo_review_enabled", true);
        value.scheduleGeneration = preferences.getString(GENERATION, "");
        List<TodoReviewSlot> slots = new ArrayList<>();
        int storedCount = preferences.getInt(REVIEW_COUNT, 0);
        int count = storedCount >= 1 && storedCount <= 6 ? storedCount : 1;
        for (int i = 0; i < count; i++) {
            String key = "proactive_todo_review_" + i + '_';
            String id = preferences.getString(key + "id", "recovered-" + i);
            int minute = preferences.getInt(key + "minute", 12 * 60 + 30);
            try {
                boolean complete = storedCount >= 1 && storedCount <= 6
                        && preferences.contains(key + "id") && preferences.contains(key + "minute");
                slots.add(new TodoReviewSlot(id, minute,
                        complete && preferences.getBoolean(key + "enabled", false)));
            } catch (IllegalArgumentException ignored) {
                slots.add(new TodoReviewSlot("recovered-" + i, 12 * 60 + 30, false));
            }
        }
        try { value.setTodoReviewSlots(slots); }
        catch (IllegalArgumentException ignored) {
            value.setTodoReviewSlots(Collections.singletonList(
                    new TodoReviewSlot("recovered-default", 12 * 60 + 30, false)));
        }
        value.backgroundAiEnabled = preferences.getBoolean("proactive_background_ai", true);
        value.notificationEnabled = preferences.getBoolean(PROACTIVE_NOTIFICATION, true);
        value.dailyLimit = preferences.getInt("proactive_daily_limit", 3);
        value.quietStartMinute = preferences.getInt("proactive_quiet_start", 22 * 60 + 30);
        value.quietEndMinute = preferences.getInt("proactive_quiet_end", 7 * 60);
        value.normalize();
        return value;
    }

    public void saveProactiveSettings(ProactiveSettings value) {
        synchronized (PROACTIVE_LOCK) {
            writeProactiveSettings(value);
        }
    }

    private void writeProactiveSettings(ProactiveSettings value) {
        if (value == null) throw new IllegalArgumentException("proactive settings must not be null");
        ProactiveSettings safe = value.copy();
        safe.normalize();
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(PROACTIVE_ENABLED, safe.enabled)
                .putBoolean("proactive_morning_enabled", safe.morningEnabled)
                .putBoolean("proactive_midday_enabled", safe.middayEnabled)
                .putBoolean("proactive_afternoon_enabled", safe.afternoonEnabled)
                .putBoolean("proactive_evening_enabled", safe.eveningEnabled)
                .putInt("proactive_morning_minute", safe.morningMinute)
                .putInt("proactive_midday_minute", safe.middayMinute)
                .putInt("proactive_afternoon_minute", safe.afternoonMinute)
                .putInt("proactive_evening_minute", safe.eveningMinute)
                .putBoolean("proactive_attention", safe.attentionTriggerEnabled)
                .putBoolean("proactive_midday_todos", safe.middayTodoPlanningEnabled)
                .putBoolean("proactive_background_ai", safe.backgroundAiEnabled)
                .putBoolean(PROACTIVE_NOTIFICATION, safe.notificationEnabled)
                .putInt("proactive_daily_limit", safe.dailyLimit)
                .putInt("proactive_quiet_start", safe.quietStartMinute)
                .putInt("proactive_quiet_end", safe.quietEndMinute)
                .putBoolean("proactive_todo_review_enabled", safe.todoReviewEnabled)
                .putString(GENERATION, UUID.randomUUID().toString());
        writeSlots(editor, safe.getTodoReviewSlots());
        editor.apply();
    }

    private void migrateTodoReviews() {
        if (preferences.contains(REVIEW_COUNT)) return;
        int minute = preferences.getInt("proactive_midday_minute", 12 * 60 + 30);
        if (minute < 0 || minute >= 1440) minute = 12 * 60 + 30;
        boolean enabled = preferences.getBoolean("proactive_midday_enabled", true)
                && preferences.getBoolean("proactive_midday_todos", true);
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean("proactive_todo_review_enabled", enabled)
                .putString(GENERATION, UUID.randomUUID().toString());
        writeSlots(editor, Collections.singletonList(new TodoReviewSlot("legacy-midday", minute, enabled)));
        editor.apply();
    }

    private static void writeSlots(SharedPreferences.Editor editor, List<TodoReviewSlot> slots) {
        editor.putInt(REVIEW_COUNT, slots.size());
        for (int i = 0; i < 6; i++) {
            String key = "proactive_todo_review_" + i + '_';
            if (i >= slots.size()) {
                editor.remove(key + "id").remove(key + "minute").remove(key + "enabled");
                continue;
            }
            TodoReviewSlot slot = slots.get(i);
            editor.putString(key + "id", slot.id).putInt(key + "minute", slot.minuteOfDay)
                    .putBoolean(key + "enabled", slot.enabled);
        }
    }

    public void reset() {
        synchronized (PROACTIVE_LOCK) { preferences.edit().clear().apply(); }
    }
}
