package com.hawson.timeassistant.domain;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChatSessionPolicyTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    @Test
    public void requiresBothANewDateAndTwoHoursWithoutAppActivity() {
        long activeDay = utc(2026, Calendar.AUGUST, 11, 0, 0);
        long now = utc(2026, Calendar.AUGUST, 12, 1, 0);

        assertFalse(ChatSessionPolicy.shouldStartNewSession(
                activeDay, utc(2026, Calendar.AUGUST, 11, 23, 30), now, UTC));
        assertTrue(ChatSessionPolicy.shouldStartNewSession(
                activeDay, utc(2026, Calendar.AUGUST, 11, 22, 59), now, UTC));
    }

    @Test
    public void doesNotResetAgainDuringTheSameDate() {
        long activeDay = utc(2026, Calendar.AUGUST, 12, 0, 0);
        long now = utc(2026, Calendar.AUGUST, 12, 18, 0);

        assertFalse(ChatSessionPolicy.shouldStartNewSession(
                activeDay, utc(2026, Calendar.AUGUST, 12, 8, 0), now, UTC));
    }

    @Test
    public void repairsAnActiveDayThatIsInTheFuture() {
        assertTrue(ChatSessionPolicy.shouldStartNewSession(
                utc(2026, Calendar.AUGUST, 13, 0, 0),
                utc(2026, Calendar.AUGUST, 12, 9, 0),
                utc(2026, Calendar.AUGUST, 12, 10, 0), UTC));
    }

    private static long utc(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(UTC);
        calendar.clear();
        calendar.set(year, month, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
