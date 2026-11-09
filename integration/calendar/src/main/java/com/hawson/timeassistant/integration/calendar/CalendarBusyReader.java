package com.hawson.timeassistant.integration.calendar;

import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Privacy-preserving CalendarContract reader. It never projects event identity or content. */
public final class CalendarBusyReader {
    public static final int MAX_SOURCE_ROWS = 200;
    public static final long MAX_RANGE_MILLIS = 31L * 86_400_000L;
    private static final String[] PROJECTION = {
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY
    };

    public List<BusyBlock> query(ContentResolver resolver, long startAt, long endAt) {
        validateRange(startAt, endAt);
        List<BusyBlock> raw = new ArrayList<>();
        try (Cursor cursor = CalendarContract.Instances.query(
                resolver, PROJECTION, startAt, endAt)) {
            if (cursor == null) return Collections.emptyList();
            int count = 0;
            while (cursor.moveToNext() && count++ < MAX_SOURCE_ROWS) {
                int availability = cursor.getInt(3);
                if (availability == CalendarContract.Events.AVAILABILITY_FREE) continue;
                long begin = Math.max(startAt, cursor.getLong(0));
                long end = Math.min(endAt, cursor.getLong(1));
                if (end <= begin) continue;
                raw.add(new BusyBlock(begin, end, cursor.getInt(2) == 1));
            }
        }
        return merge(raw);
    }

    public static List<BusyBlock> merge(List<BusyBlock> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        List<BusyBlock> sorted = new ArrayList<>(source);
        sorted.sort(Comparator.comparingLong(block -> block.startAt));
        List<BusyBlock> result = new ArrayList<>();
        BusyBlock current = sorted.get(0);
        for (int index = 1; index < sorted.size(); index++) {
            BusyBlock next = sorted.get(index);
            if (next.startAt <= current.endAt) {
                current = new BusyBlock(current.startAt, Math.max(current.endAt, next.endAt),
                        current.allDay || next.allDay);
            } else {
                result.add(current);
                current = next;
            }
        }
        result.add(current);
        return Collections.unmodifiableList(result);
    }

    public static void validateRange(long startAt, long endAt) {
        if (startAt <= 0 || endAt <= startAt) throw new IllegalArgumentException("invalid calendar range");
        if (endAt - startAt > MAX_RANGE_MILLIS) {
            throw new IllegalArgumentException("calendar range cannot exceed 31 days");
        }
    }

    public static final class BusyBlock {
        public final long startAt;
        public final long endAt;
        public final boolean allDay;

        public BusyBlock(long startAt, long endAt, boolean allDay) {
            if (endAt <= startAt) throw new IllegalArgumentException("busy block end must follow start");
            this.startAt = startAt; this.endAt = endAt; this.allDay = allDay;
        }
    }
}
