package com.hawson.timeassistant.integration.calendar;

import android.content.Intent;
import android.provider.CalendarContract;

/** Builds a user-visible Calendar app handoff; this app never writes calendar rows directly. */
public final class CalendarEventComposer {
    private CalendarEventComposer() {}

    public static Intent build(String title, long startAt, long endAt, String description,
                               String recurrenceRule) {
        String safeTitle = title == null ? "" : title.trim();
        if (safeTitle.isEmpty()) throw new IllegalArgumentException("calendar title is required");
        CalendarBusyReader.validateRange(startAt, endAt);
        Intent intent = new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startAt)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endAt)
                .putExtra(CalendarContract.Events.TITLE, safeTitle);
        if (description != null && !description.trim().isEmpty()) {
            intent.putExtra(CalendarContract.Events.DESCRIPTION, description.trim());
        }
        if (recurrenceRule != null && !recurrenceRule.trim().isEmpty()) {
            intent.putExtra(CalendarContract.Events.RRULE, recurrenceRule.trim());
        }
        return intent;
    }
}
