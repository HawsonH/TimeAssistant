package com.hawson.timeassistant;

import android.content.Intent;
import android.provider.CalendarContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.hawson.timeassistant.integration.calendar.CalendarEventComposer;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class CalendarEventComposerTest {
    @Test public void buildsUserVisibleInsertIntentWithoutWriteOperation() {
        long start = 1_900_000_000_000L;
        Intent intent = CalendarEventComposer.build("会议", start, start + 3_600_000L,
                "讨论", "FREQ=WEEKLY;COUNT=4");
        assertEquals(Intent.ACTION_INSERT, intent.getAction());
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.getData());
        assertEquals("会议", intent.getStringExtra(CalendarContract.Events.TITLE));
        assertEquals(start, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0));
    }
}
