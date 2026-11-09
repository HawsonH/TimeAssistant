package com.hawson.timeassistant.integration.calendar;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CalendarBusyReaderTest {
    @Test public void mergesOverlappingAndAdjacentBusyBlocks() {
        List<CalendarBusyReader.BusyBlock> merged = CalendarBusyReader.merge(Arrays.asList(
                new CalendarBusyReader.BusyBlock(200, 300, false),
                new CalendarBusyReader.BusyBlock(100, 220, true),
                new CalendarBusyReader.BusyBlock(300, 350, false),
                new CalendarBusyReader.BusyBlock(500, 600, false)));
        assertEquals(2, merged.size());
        assertEquals(100, merged.get(0).startAt);
        assertEquals(350, merged.get(0).endAt);
        assertTrue(merged.get(0).allDay);
    }

    @Test public void rejectsRangesLongerThanThirtyOneDays() {
        try {
            CalendarBusyReader.validateRange(1, 1 + 32L * 86_400_000L);
            fail("expected range rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("31"));
        }
    }
}
