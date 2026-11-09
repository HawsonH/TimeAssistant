package com.hawson.timeassistant.proactive;

import org.junit.Test;

import java.util.Calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ProactiveSettingsTest {
    @Test public void defaults_keepMasterOffButPrepareDailyRhythm() {
        ProactiveSettings settings = new ProactiveSettings();
        assertFalse(settings.enabled);
        assertTrue(settings.middayEnabled);
        assertTrue(settings.middayTodoPlanningEnabled);
        assertEquals(12 * 60 + 30, settings.middayMinute);
        assertEquals(3, settings.dailyLimit);
    }

    @Test public void quietWindowSupportsCrossingMidnight() {
        ProactiveSettings settings = new ProactiveSettings();
        assertTrue(settings.isQuietAt(23 * 60));
        assertTrue(settings.isQuietAt(6 * 60 + 59));
        assertFalse(settings.isQuietAt(12 * 60));
    }

    @Test public void nextTriggerUsesGraceThenMovesToTomorrow() {
        Calendar now = Calendar.getInstance();
        now.set(2026, Calendar.AUGUST, 13, 12, 45, 0);
        now.set(Calendar.MILLISECOND, 0);
        long immediate = ProactiveScheduleCoordinator.nextTriggerAt(
                now.getTimeInMillis(), 12 * 60 + 30, true);
        assertTrue(immediate >= now.getTimeInMillis());
        assertTrue(immediate - now.getTimeInMillis() < 10_000L);

        long tomorrow = ProactiveScheduleCoordinator.nextTriggerAt(
                now.getTimeInMillis(), 12 * 60 + 30, false);
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(tomorrow);
        assertEquals(14, target.get(Calendar.DAY_OF_MONTH));
        assertEquals(12, target.get(Calendar.HOUR_OF_DAY));
        assertEquals(30, target.get(Calendar.MINUTE));
    }
}
