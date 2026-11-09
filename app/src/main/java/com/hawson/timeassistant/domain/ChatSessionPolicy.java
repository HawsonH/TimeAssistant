package com.hawson.timeassistant.domain;

import java.util.Calendar;
import java.util.TimeZone;

/** Pure rollover rules for daily chat sessions. */
public final class ChatSessionPolicy {
    public static final long REQUIRED_INACTIVITY_MILLIS = 2L * 60L * 60L * 1_000L;

    private ChatSessionPolicy() { }

    public static boolean shouldStartNewSession(long activeDay, long lastActivityAt,
                                                long now, TimeZone timeZone) {
        TimeZone zone = timeZone == null ? TimeZone.getDefault() : timeZone;
        long activeDayStart = startOfDay(activeDay, zone);
        long todayStart = startOfDay(now, zone);
        if (activeDayStart > todayStart) return true;
        if (sameDay(activeDayStart, todayStart, zone)) return false;
        return lastActivityAt <= 0L
                || (now >= lastActivityAt
                && now - lastActivityAt >= REQUIRED_INACTIVITY_MILLIS);
    }

    public static long startOfDay(long value, TimeZone timeZone) {
        TimeZone zone = timeZone == null ? TimeZone.getDefault() : timeZone;
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(value > 0 ? value : System.currentTimeMillis());
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static boolean sameDay(long first, long second, TimeZone zone) {
        Calendar a = Calendar.getInstance(zone);
        a.setTimeInMillis(first);
        Calendar b = Calendar.getInstance(zone);
        b.setTimeInMillis(second);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
