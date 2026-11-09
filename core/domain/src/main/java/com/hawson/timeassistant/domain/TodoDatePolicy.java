package com.hawson.timeassistant.domain;

import java.util.Calendar;
import java.util.TimeZone;

/** Undated and overdue pending todos stay in today's backlog; future todos keep their date. */
public final class TodoDatePolicy {
    private TodoDatePolicy() { }

    public static boolean isVisible(long plannedAt, boolean completed, long completedAt,
                                    long selectedAt, long now, TimeZone timeZone) {
        long selectedDay = dayStart(selectedAt, timeZone);
        if (completed) return completedAt > 0 && dayStart(completedAt, timeZone) == selectedDay;
        long today = dayStart(now, timeZone);
        if (plannedAt <= 0) return selectedDay == today;
        long plannedDay = dayStart(plannedAt, timeZone);
        return plannedDay == selectedDay || selectedDay == today && plannedDay < today;
    }

    private static long dayStart(long at, TimeZone timeZone) {
        Calendar calendar = Calendar.getInstance(timeZone);
        calendar.setTimeInMillis(at);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
