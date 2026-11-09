package com.hawson.timeassistant.data.local;

import android.content.SharedPreferences;

import com.hawson.timeassistant.proactive.ProactiveSettings;
import com.hawson.timeassistant.proactive.TodoReviewSlot;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** JVM-only preferences fixture; never opens app data or an Android device. */
public final class TodoReviewPreferencesTest {
    private final Map<String, Object> values = new HashMap<>();

    private SharedPreferences preferences() {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("put")) { values.put((String) args[0], args[1]); return proxy; }
                    if (name.equals("remove")) { values.remove(args[0]); return proxy; }
                    if (name.equals("clear")) { values.clear(); return proxy; }
                    if (name.equals("commit")) return true;
                    return null;
                });
        return (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("edit")) return editor;
                    if (method.getName().equals("contains")) return values.containsKey(args[0]);
                    if (method.getName().startsWith("get")) return values.getOrDefault(args[0], args[1]);
                    return null;
                });
    }

    @Test public void migrationPreservesLegacyTimeMasterAndConsentAndIsIdempotent() {
        values.put("proactive_midday_minute", 805);
        values.put("proactive_enabled", true);
        values.put("proactive_consent_version", 2);
        LocalIntegrationSettingsStore store = new LocalIntegrationSettingsStore(preferences());
        ProactiveSettings migrated = store.getProactiveSettings();
        assertTrue(migrated.enabled);
        assertEquals(805, migrated.getTodoReviewSlots().get(0).minuteOfDay);
        assertTrue(migrated.todoReviewEnabled);
        String generation = migrated.scheduleGeneration;
        values.put("proactive_midday_minute", 300);
        values.put("proactive_midday_enabled", false);
        ProactiveSettings reread = store.getProactiveSettings();
        assertEquals(805, reread.getTodoReviewSlots().get(0).minuteOfDay);
        assertEquals(generation, reread.scheduleGeneration);
        assertEquals(2, values.get("proactive_consent_version"));
        assertEquals(true, values.get("proactive_enabled"));
    }

    @Test public void migrationNeverGrantsConsentOrTurnsOnMaster() {
        LocalIntegrationSettingsStore store = new LocalIntegrationSettingsStore(preferences());
        assertFalse(store.getProactiveSettings().enabled);
        assertFalse(values.containsKey("proactive_enabled"));
        assertFalse(values.containsKey("proactive_consent_version"));
        values.put("proactive_enabled", true);
        assertFalse(store.getProactiveSettings().enabled);
    }

    @Test public void disabledLegacyMiddayOrTodoFlagKeepsReviewsOff() {
        for (String flag : new String[]{"proactive_midday_enabled", "proactive_midday_todos"}) {
            values.clear();
            values.put(flag, false);
            ProactiveSettings value = new LocalIntegrationSettingsStore(preferences()).getProactiveSettings();
            assertFalse(value.todoReviewEnabled);
            assertFalse(value.getTodoReviewSlots().get(0).enabled);
        }
    }

    @Test public void slotRoundTripPreservesIdsAndDoesNotFollowAttentionTime() {
        LocalIntegrationSettingsStore store = new LocalIntegrationSettingsStore(preferences());
        ProactiveSettings value = store.getProactiveSettings();
        TodoReviewSlot slot = value.addTodoReviewSlot(500);
        value.updateTodoReviewSlot(slot.withEnabled(false));
        value.middayMinute = 999;
        String oldGeneration = value.scheduleGeneration;
        store.saveProactiveSettings(value);
        ProactiveSettings saved = store.getProactiveSettings();
        assertNotEquals(oldGeneration, saved.scheduleGeneration);
        assertEquals(slot.id, saved.getTodoReviewSlots().get(1).id);
        assertEquals(500, saved.getTodoReviewSlots().get(1).minuteOfDay);
        assertFalse(saved.getTodoReviewSlots().get(1).enabled);
        assertEquals(750, saved.getTodoReviewSlots().get(0).minuteOfDay);
        assertEquals(999, saved.middayMinute);
        saved.removeTodoReviewSlot(slot.id);
        store.saveProactiveSettings(saved);
        assertEquals(1, store.getProactiveSettings().getTodoReviewSlots().size());
        assertFalse(values.containsKey("proactive_todo_review_1_id"));
    }

    @Test public void resetDoesNotReuseWorkGeneration() {
        LocalIntegrationSettingsStore store = new LocalIntegrationSettingsStore(preferences());
        String generation = store.getProactiveSettings().scheduleGeneration;
        store.reset();
        assertNotEquals(generation, store.getProactiveSettings().scheduleGeneration);
    }

    @Test public void migrationPreservesEveryLegacyFrequencyAndConsentCombination() {
        for (int mask = 0; mask < 128; mask++) {
            values.clear();
            boolean master = (mask & 1) != 0;
            boolean consent = (mask & 2) != 0;
            boolean attention = (mask & 4) != 0;
            boolean midday = (mask & 8) != 0;
            boolean todos = (mask & 16) != 0;
            boolean background = (mask & 32) != 0;
            boolean notification = (mask & 64) != 0;
            values.put("proactive_enabled", master);
            values.put("proactive_consent_version", consent ? 2 : 1);
            values.put("proactive_attention", attention);
            values.put("proactive_midday_enabled", midday);
            values.put("proactive_midday_todos", todos);
            values.put("proactive_background_ai", background);
            values.put("proactive_notification", notification);
            values.put("proactive_daily_limit", mask % 6 + 1);
            values.put("proactive_midday_minute", 805);
            LocalIntegrationSettingsStore store = new LocalIntegrationSettingsStore(preferences());
            ProactiveSettings migrated = store.getProactiveSettings();
            assertEquals(master && consent, migrated.enabled);
            assertEquals(attention, migrated.attentionTriggerEnabled);
            assertEquals(midday && todos, migrated.todoReviewEnabled);
            assertEquals(background, migrated.backgroundAiEnabled);
            assertEquals(notification, migrated.notificationEnabled);
            assertEquals(mask % 6 + 1, migrated.dailyLimit);
            assertEquals(1, migrated.getTodoReviewSlots().size());
            assertEquals(805, migrated.getTodoReviewSlots().get(0).minuteOfDay);
            assertEquals(midday && todos, migrated.getTodoReviewSlots().get(0).enabled);
            Map<String, Object> afterMigration = new HashMap<>(values);
            assertEquals(migrated.scheduleGeneration, store.getProactiveSettings().scheduleGeneration);
            assertEquals(afterMigration, values);
        }
    }

    @Test public void corruptOrIncompleteStoredSlotsCannotIntroduceActiveDefaultTrigger() {
        for (int count : new int[]{-1, 0, 1, 7}) {
            values.clear();
            values.put("proactive_todo_review_count", count);
            values.put("proactive_todo_review_0_enabled", true);
            ProactiveSettings saved = new LocalIntegrationSettingsStore(preferences()).getProactiveSettings();
            assertFalse(saved.getTodoReviewSlots().get(0).enabled);
        }
    }

    @Test public void duplicatePersistedTimesFailClosedWithoutChangingConsentOrCap() {
        LocalIntegrationSettingsStore store = new LocalIntegrationSettingsStore(preferences());
        ProactiveSettings value = store.getProactiveSettings();
        value.addTodoReviewSlot(600);
        value.dailyLimit = 1;
        store.saveProactiveSettings(value);
        values.put("proactive_todo_review_1_minute", 750);
        ProactiveSettings saved = store.getProactiveSettings();
        assertFalse(saved.hasTodoReviewAt(750));
        assertEquals(1, saved.dailyLimit);
        assertFalse(saved.enabled);
    }
}
