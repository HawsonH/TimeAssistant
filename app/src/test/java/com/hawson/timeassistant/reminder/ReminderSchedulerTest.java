package com.hawson.timeassistant.reminder;

import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.TaskItem;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

public class ReminderSchedulerTest {
    @Test
    public void entriesToCancelReturnsAlarmsLeftByDeletedTasks() {
        String deletedTaskId = "deleted-task";
        String remainingTaskId = "remaining-task";
        Set<String> registered = new LinkedHashSet<>(Arrays.asList(
                ReminderScheduler.registryKey(
                        deletedTaskId,
                        ReminderScheduler.KIND_SCHEDULE
                ),
                ReminderScheduler.registryKey(
                        deletedTaskId,
                        ReminderScheduler.KIND_SCHEDULE_END
                ),
                ReminderScheduler.registryKey(
                        deletedTaskId,
                        ReminderScheduler.KIND_DEADLINE
                ),
                ReminderScheduler.registryKey(
                        deletedTaskId,
                        ReminderScheduler.KIND_OVERDUE
                ),
                ReminderScheduler.registryKey(
                        remainingTaskId,
                        ReminderScheduler.KIND_SCHEDULE
                )
        ));
        Set<String> scheduled = Collections.singleton(
                ReminderScheduler.registryKey(
                        remainingTaskId,
                        ReminderScheduler.KIND_SCHEDULE
                )
        );

        assertEquals(
                new LinkedHashSet<>(Arrays.asList(
                        ReminderScheduler.registryKey(
                                deletedTaskId,
                                ReminderScheduler.KIND_SCHEDULE
                        ),
                        ReminderScheduler.registryKey(
                                deletedTaskId,
                                ReminderScheduler.KIND_SCHEDULE_END
                        ),
                        ReminderScheduler.registryKey(
                                deletedTaskId,
                                ReminderScheduler.KIND_DEADLINE
                        ),
                        ReminderScheduler.registryKey(
                                deletedTaskId,
                                ReminderScheduler.KIND_OVERDUE
                        )
                )),
                ReminderScheduler.entriesToCancel(registered, scheduled)
        );
    }

    @Test
    public void registryEntryRoundTripsTaskIdsContainingColons() {
        String taskId = "project:task:42";
        ReminderScheduler.RegistryEntry entry = ReminderScheduler.parseRegistryEntry(
                ReminderScheduler.registryKey(taskId, ReminderScheduler.KIND_DEADLINE)
        );

        assertEquals(taskId, entry.taskId);
        assertEquals(ReminderScheduler.KIND_DEADLINE, entry.kind);

        ReminderScheduler.RegistryEntry countdown = ReminderScheduler.parseRegistryEntry(
                ReminderScheduler.registryKey(taskId, ReminderScheduler.KIND_COUNTDOWN)
        );
        assertEquals(taskId, countdown.taskId);
        assertEquals(ReminderScheduler.KIND_COUNTDOWN, countdown.kind);

        ReminderScheduler.RegistryEntry scheduleEnd = ReminderScheduler.parseRegistryEntry(
                ReminderScheduler.registryKey(taskId, ReminderScheduler.KIND_SCHEDULE_END)
        );
        assertEquals(taskId, scheduleEnd.taskId);
        assertEquals(ReminderScheduler.KIND_SCHEDULE_END, scheduleEnd.kind);

        String eventRegistryId = "event:task:42:deadline:1000";
        ReminderScheduler.RegistryEntry snoozed = ReminderScheduler.parseRegistryEntry(
                ReminderScheduler.registryKey(eventRegistryId,
                        ReminderScheduler.LEGACY_KIND_SNOOZED));
        assertEquals(eventRegistryId, snoozed.taskId);
        assertEquals(ReminderScheduler.LEGACY_KIND_SNOOZED, snoozed.kind);
    }

    @Test
    public void focusReminderUsesLiveRemainingTimeAndStopsWhenNotRunning() {
        long startedAt = 100_000L;
        FocusState running = FocusState.ready(
                "task-1",
                FocusState.Mode.FOCUS,
                60_000L,
                1
        ).startOrResume(startedAt);

        assertEquals(160_000L, ReminderScheduler.focusReminderAt(running, 115_000L));
        assertEquals(0L, ReminderScheduler.focusReminderAt(
                running.pause(115_000L),
                120_000L
        ));
        assertEquals(0L, ReminderScheduler.focusReminderAt(
                running.finish(120_000L),
                120_000L
        ));
        assertEquals(0L, ReminderScheduler.focusReminderAt(running, 170_000L));
    }

    @Test
    public void overdueReminderUsesDeadlineAndLeavesScheduleEndForConfirmation() {
        TaskItem both = new TaskItem("both")
                .setDueAt(300_000L)
                .schedule(100_000L, 200_000L, false);
        TaskItem dueOnly = new TaskItem("due").setDueAt(250_000L);
        TaskItem scheduleOnly = new TaskItem("schedule")
                .schedule(100_000L, 225_000L, false);

        long grace = 15 * 60_000L;
        assertEquals(300_000L + grace, ReminderScheduler.overdueReminderAt(both));
        assertEquals(250_000L + grace, ReminderScheduler.overdueReminderAt(dueOnly));
        assertEquals(0L, ReminderScheduler.overdueReminderAt(scheduleOnly));
        assertEquals(0L, ReminderScheduler.overdueReminderAt(
                both.copy().setStatus(TaskItem.Status.COMPLETED)
        ));
    }

    @Test
    public void overdueRequestCodeCannotEqualDeadlineRequestCode() {
        String taskId = "task-with-both-reminders";
        assertNotEquals(
                ReminderScheduler.requestCode(taskId, ReminderScheduler.KIND_DEADLINE),
                ReminderScheduler.requestCode(taskId, ReminderScheduler.KIND_OVERDUE)
        );
        assertNotEquals(
                ReminderScheduler.requestCode(taskId, ReminderScheduler.KIND_SCHEDULE),
                ReminderScheduler.requestCode(taskId, ReminderScheduler.KIND_SCHEDULE_END)
        );
    }

    @Test
    public void malformedOrUnknownRegistryEntriesAreIgnored() {
        assertNull(ReminderScheduler.parseRegistryEntry("not-a-registry-entry"));
        assertNull(ReminderScheduler.parseRegistryEntry("unknown:task-id"));
        assertNull(ReminderScheduler.parseRegistryEntry(
                ReminderScheduler.registryKey("", ReminderScheduler.KIND_SCHEDULE)
        ));
    }
}
