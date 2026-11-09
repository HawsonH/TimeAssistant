package com.hawson.timeassistant.ui.task;

import org.junit.Test;
import java.util.Calendar;
import java.util.TimeZone;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class TaskDateTimeFieldsTest {
    @Test public void optionalScheduleAllowsEmptyAndPartialValues() {
        assertNull(TaskDateTimeFields.DateValues.validationError(0, 0, 0, false));
        assertNull(TaskDateTimeFields.DateValues.validationError(100, 200, 0, false));
        assertNull(TaskDateTimeFields.DateValues.validationError(100, 0, 300, false));
    }

    @Test public void recurringScheduleRequiresAllThreeValues() {
        assertNotNull(TaskDateTimeFields.DateValues.validationError(0, 200, 300, true));
        assertNotNull(TaskDateTimeFields.DateValues.validationError(100, 0, 300, true));
        assertNotNull(TaskDateTimeFields.DateValues.validationError(100, 200, 0, true));
        assertNull(TaskDateTimeFields.DateValues.validationError(100, 200, 300, true));
    }

    @Test public void earlierOrEqualEndIsRejectedWithoutChangingDraft() {
        assertNotNull(TaskDateTimeFields.DateValues.validationError(100, 300, 200, false));
        assertNotNull(TaskDateTimeFields.DateValues.validationError(100, 300, 300, false));
        assertNull(TaskDateTimeFields.DateValues.validationError(100, 300, 400, false));
    }

    private Calendar date(String zone, int year, int month, int day, int hour) {
        Calendar value = Calendar.getInstance(TimeZone.getTimeZone(zone));
        value.clear();
        value.set(year, month - 1, day, hour, 30);
        return value;
    }

    @Test public void dateChangeDoesNotClampAtMonthEnd() {
        Calendar from = date("UTC", 2026, 1, 31, 0);
        Calendar to = date("UTC", 2026, 2, 2, 0);
        long result = TaskDateTimeFields.DateValues.moveDate(
                date("UTC", 2026, 1, 31, 15).getTimeInMillis(), from, to);
        assertEquals(date("UTC", 2026, 2, 2, 15).getTimeInMillis(), result);
    }

    @Test public void dateChangeRetainsExplicitOvernightEnd() {
        Calendar from = date("UTC", 2026, 12, 31, 0);
        Calendar to = date("UTC", 2027, 1, 5, 0);
        long result = TaskDateTimeFields.DateValues.moveDate(
                date("UTC", 2027, 1, 1, 1).getTimeInMillis(), from, to);
        assertEquals(date("UTC", 2027, 1, 6, 1).getTimeInMillis(), result);
    }

    @Test public void dateChangeRetainsWallClockAcrossDst() {
        Calendar from = date("America/New_York", 2026, 3, 7, 0);
        Calendar to = date("America/New_York", 2026, 3, 9, 0);
        long result = TaskDateTimeFields.DateValues.moveDate(
                date("America/New_York", 2026, 3, 7, 10).getTimeInMillis(), from, to);
        assertEquals(date("America/New_York", 2026, 3, 9, 10).getTimeInMillis(), result);
    }

    @Test public void dateChangeHandlesLeapDayBackwards() {
        Calendar from = date("UTC", 2028, 3, 1, 0);
        Calendar to = date("UTC", 2028, 2, 29, 0);
        long result = TaskDateTimeFields.DateValues.moveDate(
                date("UTC", 2028, 3, 1, 12).getTimeInMillis(), from, to);
        assertEquals(date("UTC", 2028, 2, 29, 12).getTimeInMillis(), result);
    }
}
