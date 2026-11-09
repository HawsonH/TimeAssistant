package com.hawson.timeassistant.data;

import com.hawson.timeassistant.ai.AiPlan;
import com.hawson.timeassistant.ai.TaskActionExecutor;
import com.hawson.timeassistant.domain.ReminderEvent;
import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.ui.schedule.TaskReminderSummary;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskReminderDeliveryTest {
    private final AppRepository repository = new AppRepository(() -> { });

    @Test public void countdownClaimIsAtomicClearsOnceAndKeepsItsDisplayedTime() {
        TaskItem task = repository.upsertTask(new TaskItem("remind").setCountdown(10_000, 0));
        ReminderEvent event = repository.claimReminder(task.getId(), "countdown", 10_000, 10_000);
        assertNotNull(event); assertFalse(repository.getTask(task.getId()).hasCountdown());
        assertNull(repository.claimReminder(task.getId(), "countdown", 10_000, 11_000));
        assertEquals(1, repository.getAllReminderEvents().size());
        assertTrue(TaskReminderSummary.format(repository.getTask(task.getId()), repository.getAllReminderEvents()).contains("已提醒"));
        assertEquals("pending", event.getDeliveryState());
        repository.updateReminderDelivery(event.getId(), "blocked", true);
        assertEquals("blocked", repository.getReminderEvent(event.getId()).getDeliveryState());
        assertTrue(repository.getReminderEvent(event.getId()).getPresentedAt() > 0);
    }

    @Test public void editDeleteCompletionAndDisabledSettingRejectStaleAlarms() {
        TaskItem task = repository.upsertTask(new TaskItem("remind").setCountdown(20_000, 0).setDueAt(30_000));
        assertNull(repository.claimReminder(task.getId(), "countdown", 10_000, 50_000));
        assertNull(repository.claimReminder(task.getId(), "countdown", 20_000, 19_999));
        repository.saveSettings(repository.getSettings().setNotificationSettings(false, false, false, false, false));
        assertNull(repository.claimReminder(task.getId(), "deadline", 30_000, 30_000));
        repository.upsertTask(task.copy().setStatus(TaskItem.Status.COMPLETED));
        assertNull(repository.claimReminder(task.getId(), "countdown", 20_000, 50_000));
        repository.deleteTask(task.getId());
        assertNull(repository.claimReminder(task.getId(), "countdown", 20_000, 50_000));
    }

    @Test public void coincidentReminderKindsCoalesceButRepeatedCountdownAdvances() {
        repository.saveSettings(repository.getSettings().setNotificationSettings(true, true, false, false, false));
        TaskItem task = repository.upsertTask(new TaskItem("test").setCountdown(20_000, 5_000).setDueAt(20_000));
        assertNotNull(repository.claimReminder(task.getId(), "deadline", 20_000, 20_000));
        assertNull(repository.claimReminder(task.getId(), "countdown", 20_000, 20_000));
        assertEquals(25_000, repository.getTask(task.getId()).getCountdownTargetAt());
        assertEquals(1, repository.getAllReminderEvents().size());
        assertNotNull(repository.claimReminder(task.getId(), "countdown", 25_000, 25_000));
    }

    @Test public void failedReminderCommitDoesNotConsumeCountdown() {
        AtomicBoolean failCommit = new AtomicBoolean();
        AppRepository store = new AppRepository(() -> { if (failCommit.get()) throw new IllegalStateException("write failed"); });
        TaskItem task = store.upsertTask(new TaskItem("test").setCountdown(100, 0));
        failCommit.set(true);
        try { store.claimReminder(task.getId(), "countdown", 100, 100); fail(); }
        catch (IllegalStateException expected) { }
        assertTrue(store.getTask(task.getId()).hasCountdown()); assertTrue(store.getAllReminderEvents().isEmpty());
    }

    @Test public void coincidentScheduleEndStillTransitionsWithoutAnotherAlert() {
        repository.saveSettings(repository.getSettings().setNotificationSettings(true, true, true, false, false));
        TaskItem task = repository.upsertTask(new TaskItem("scheduled").schedule(10_000, 20_000, false)
                .setCountdown(20_000, 0));
        assertNotNull(repository.claimReminder(task.getId(), "countdown", 20_000, 20_000));
        assertNull(repository.claimReminder(task.getId(), "schedule_end", 20_000, 20_000));
        assertEquals(TaskItem.Status.AWAITING_CONFIRMATION, repository.getTask(task.getId()).getStatus());
        assertEquals(1, repository.getAllReminderEvents().size());
        assertNull(repository.claimReminder(task.getId(), "schedule_end", 20_000, 21_000));
    }

    @Test public void aiAbsoluteReminderUsesExistingTargetAndRejectsAmbiguousInput() throws Exception {
        long target = System.currentTimeMillis() + 600_000;
        AiPlan plan = AiPlan.parse("{\"message\":\"reminder\",\"needsClarification\":false,\"actions\":["
                + "{\"type\":\"create_task\",\"title\":\"test\",\"reminderAt\":" + target + "}]}");
        TaskActionExecutor.executeConfirmed(repository, plan);
        TaskItem task = repository.getAllTasks().get(0);
        assertEquals(target, task.getCountdownTargetAt()); assertEquals(0, task.getDueAt());
        assertFalse(task.requiresTimeArrangement());
        assertTrue(TaskReminderSummary.format(task, Collections.emptyList()).contains(":"));
        try {
            TaskActionExecutor.executeConfirmed(repository, AiPlan.parse("{\"message\":\"bad\",\"needsClarification\":false,\"actions\":["
                    + "{\"type\":\"create_task\",\"title\":\"bad\",\"reminderAt\":" + target + ",\"countdownMinutes\":3}]}"));
            fail();
        } catch (TaskActionExecutor.ExecutionException expected) { }
        assertEquals(1, repository.getAllTasks().size());
    }
}
