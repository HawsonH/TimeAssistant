package com.hawson.timeassistant.domain;

import org.junit.Test;
import java.time.Instant;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class TodoDatePolicyTest {
    private final TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
    private final long today = at("2026-10-15T04:00:00Z");
    private final long tomorrow = at("2026-10-16T04:00:00Z");
    private final long yesterday = at("2026-10-14T04:00:00Z");

    @Test public void futureTodoAppearsOnItsDateNotToday() {
        assertTrue(visible(tomorrow, false, 0, tomorrow));
        assertFalse(visible(tomorrow, false, 0, today));
        assertFalse(visible(tomorrow, false, 0, yesterday));
    }

    @Test public void undatedTodoIsTodaysBacklogOnly() {
        assertTrue(visible(0, false, 0, today));
        assertFalse(visible(0, false, 0, tomorrow));
        assertFalse(visible(0, false, 0, yesterday));
    }

    @Test public void pastPendingTodoRemainsAvailableToday() {
        assertTrue(visible(yesterday, false, 0, today));
        assertTrue(visible(yesterday, false, 0, yesterday));
        assertFalse(visible(yesterday, false, 0, tomorrow));
    }

    @Test public void completedTodoBelongsToActualCompletionDay() {
        assertTrue(visible(yesterday, true, today, today));
        assertFalse(visible(yesterday, true, today, yesterday));
        assertFalse(visible(yesterday, true, 0, today));
    }

    @Test public void groupingUsesLocalDayNotUtcDay() {
        long localToday = at("2026-10-14T16:30:00Z");
        assertTrue(visible(localToday, false, 0, today));
        assertFalse(visible(localToday, false, 0, yesterday));
    }

    private boolean visible(long planned, boolean completed, long completion, long selected) {
        return TodoDatePolicy.isVisible(planned, completed, completion, selected, today, zone);
    }

    private static long at(String value) { return Instant.parse(value).toEpochMilli(); }
}
