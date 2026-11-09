package com.hawson.timeassistant.domain;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DomainModelsJsonTest {
    @Test
    public void newTaskDefaultsToTodoUntilItHasACompleteTimeBlock() {
        TaskItem task = new TaskItem("默认待办");
        assertFalse(task.requiresTimeArrangement());
        assertFalse(task.isScheduled());

        long start = 1_900_000_000_000L;
        task.setRequiresTimeArrangement(true).schedule(start, start + 30 * 60_000L, false);
        assertTrue(task.isScheduled());

        task.clearSchedule().setRequiresTimeArrangement(false);
        assertFalse(task.isScheduled());
        assertFalse(task.requiresTimeArrangement());
    }

    @Test
    public void awaitingConfirmationPersistsAndNewTimeReturnsTaskToScheduled() throws Exception {
        long start = 1_700_000_000_000L;
        TaskItem task = new TaskItem("结束确认")
                .setRequiresTimeArrangement(true)
                .schedule(start, start + 30 * 60_000L, false)
                .setStatus(TaskItem.Status.IN_PROGRESS)
                .setStatus(TaskItem.Status.AWAITING_CONFIRMATION);

        assertEquals(TaskItem.Status.AWAITING_CONFIRMATION, task.getStatus());
        assertEquals("awaiting_confirmation",
                parseObject(task.toJsonValue()).get("status"));
        assertEquals(TaskItem.Status.AWAITING_CONFIRMATION, task.copy().getStatus());
        assertTrue(task.getStatusChangedAt() > 0);

        task.schedule(start + 60 * 60_000L, start + 90 * 60_000L, false);
        assertEquals(TaskItem.Status.SCHEDULED, task.getStatus());
        task.setStatus(TaskItem.Status.AWAITING_CONFIRMATION).clearSchedule();
        assertEquals(TaskItem.Status.PENDING, task.getStatus());
    }

    @Test
    public void taskJsonPreservesGoalFields() throws Exception {
        long start = 1_800_000_000_000L;
        TaskItem original = new TaskItem("完成课程报告")
                .setDescription("先资料，再写作和修改")
                .setPriority(TaskItem.PRIORITY_HIGH)
                .setEstimatedDurationMinutes(120)
                .setActualDurationMinutes(35)
                .setDueAt(start + 4 * 60 * 60_000L)
                .schedule(start, start + 2 * 60 * 60_000L, true)
                .setStatus(TaskItem.Status.IN_PROGRESS)
                .setParentTaskId("parent-1")
                .setChildTaskIds(Arrays.asList("child-a", "child-b", "child-a"))
                .setProject("课程")
                .setTags(Arrays.asList("写作", "重要", "写作"))
                .setSource(TaskItem.SOURCE_CHAT);

        Map<String, Object> json = parseObject(original.toJsonValue());

        assertEquals(original.getId(), json.get("id"));
        assertEquals("完成课程报告", json.get("title"));
        assertEquals(TaskItem.Status.IN_PROGRESS.storageValue(), json.get("status"));
        assertEquals(TaskItem.PRIORITY_HIGH, intValue(json.get("priority")));
        assertEquals(120, intValue(json.get("estimatedDurationMinutes")));
        assertEquals(35, intValue(json.get("actualDurationMinutes")));
        assertEquals(start, longValue(json.get("scheduledStartAt")));
        assertEquals(Boolean.TRUE, json.get("locked"));
        assertEquals("parent-1", json.get("parentTaskId"));
        assertEquals(Arrays.asList("child-a", "child-b"), json.get("childTaskIds"));
        assertEquals("课程", json.get("project"));
        assertEquals(Arrays.asList("写作", "重要"), json.get("tags"));
        assertEquals(TaskItem.SOURCE_CHAT, json.get("source"));
    }

    @Test
    public void taskSupportsDateOnlyAndIndependentTimeParts() throws Exception {
        long day = 1_800_000_000_000L;
        long start = day + 9 * 60 * 60_000L;
        long end = day + 10 * 60 * 60_000L;

        TaskItem dateOnly = new TaskItem("只选日期")
                .setScheduleFields(day, 0, 0, false);
        assertFalse(dateOnly.isScheduled());
        assertTrue(dateOnly.hasPartialSchedule());
        assertEquals(day, dateOnly.getPlannedDateAt());

        TaskItem endOnly = new TaskItem("只选结束")
                .setScheduleFields(day, 0, end, false);
        assertFalse(endOnly.isScheduled());
        assertEquals(end, endOnly.getScheduledEndAt());

        TaskItem complete = new TaskItem("完整时间")
                .setScheduleFields(day, start, end, true);
        assertTrue(complete.isScheduled());
        assertEquals(TaskItem.Status.SCHEDULED, complete.getStatus());
        assertEquals(day, longValue(parseObject(complete.toJsonValue()).get("plannedDateAt")));

        TaskItem todo = new TaskItem("无需排期").setRequiresTimeArrangement(false);
        assertFalse(todo.requiresTimeArrangement());
        assertFalse(todo.hasCompleteTimeArrangement());
        assertEquals(Boolean.FALSE, parseObject(todo.toJsonValue()).get("requiresTimeArrangement"));
    }

    @Test
    public void taskStatusTransitionsKeepStartedAndCompletedTimestamps() throws Exception {
        TaskItem task = new TaskItem("有时间标定的任务");
        long initialStatusAt = task.getStatusChangedAt();

        task.setStatus(TaskItem.Status.IN_PROGRESS);
        long startedAt = task.getStartedAt();
        assertTrue(startedAt >= initialStatusAt);
        assertEquals(startedAt, task.getStatusChangedAt());

        task.setStatus(TaskItem.Status.COMPLETED);
        assertTrue(task.getCompletedAt() >= startedAt);
        assertEquals(task.getCompletedAt(), task.getStatusChangedAt());
        Map<String, Object> json = parseObject(task.toJsonValue());
        assertEquals(startedAt, longValue(json.get("startedAt")));
        assertEquals(task.getCompletedAt(), longValue(json.get("completedAt")));

        task.setStatus(TaskItem.Status.PENDING);
        assertEquals(0L, task.getCompletedAt());
        assertTrue(task.getStatusChangedAt() > startedAt);
    }

    @Test
    public void undoCompletionUsesScheduleTimeToChooseTodoOrOverdue() {
        long now = 2_000_000L;
        TaskItem overdue = new TaskItem("已过时日程")
                .schedule(1_000_000L, 1_500_000L, false)
                .setStatus(TaskItem.Status.COMPLETED)
                .undoCompletion(now);
        assertEquals(TaskItem.Status.NEEDS_RESCHEDULING, overdue.getStatus());
        assertTrue(overdue.requiresTimeArrangement());
        assertEquals(0L, overdue.getCompletedAt());

        TaskItem upcoming = new TaskItem("未来日程")
                .schedule(2_500_000L, 3_000_000L, false)
                .setStatus(TaskItem.Status.COMPLETED)
                .undoCompletion(now);
        assertEquals(TaskItem.Status.SCHEDULED, upcoming.getStatus());
        assertEquals(0L, upcoming.getCompletedAt());

        TaskItem todo = new TaskItem("普通待办")
                .setStatus(TaskItem.Status.COMPLETED)
                .undoCompletion(now);
        assertEquals(TaskItem.Status.PENDING, todo.getStatus());
        assertEquals(0L, todo.getCompletedAt());
    }

    @Test
    public void countdownPersistsAndRepeatingTimerAdvancesWithoutCreatingTasks() throws Exception {
        long target = 1_800_000_000_000L;
        long interval = 30 * 60_000L;
        TaskItem recurringTodo = new TaskItem("喝水")
                .setRequiresTimeArrangement(false)
                .setCountdown(target, interval);

        TaskItem copy = recurringTodo.copy();
        copy.advanceCountdownAfter(target + interval + 1L);
        Map<String, Object> json = parseObject(copy.toJsonValue());

        assertTrue(copy.hasCountdown());
        assertTrue(copy.isRepeatingCountdown());
        assertEquals(target + 2 * interval, copy.getCountdownTargetAt());
        assertEquals(interval, copy.getCountdownIntervalMillis());
        assertEquals(target + 2 * interval, longValue(json.get("countdownTargetAt")));
        assertEquals(interval, longValue(json.get("countdownIntervalMillis")));
        assertFalse(copy.requiresTimeArrangement());
        assertFalse(copy.isScheduled());
    }

    @Test
    public void recurringGroupProjectsOccurrencesAndConcreteTaskKeepsSeriesIdentity()
            throws Exception {
        Calendar anchor = Calendar.getInstance();
        anchor.clear();
        anchor.set(2026, Calendar.JANUARY, 31, 9, 30);
        RecurringTaskGroup monthly = new RecurringTaskGroup(
                "月末复盘", anchor.getTimeInMillis(), 60 * 60_000L,
                RecurringTaskGroup.Unit.MONTHS, 1);
        Calendar february = Calendar.getInstance();
        february.clear();
        february.set(2026, Calendar.FEBRUARY, 28, 12, 0);

        long occurrence = monthly.occurrenceStartOnDay(february.getTimeInMillis());
        Calendar projected = Calendar.getInstance();
        projected.setTimeInMillis(occurrence);
        assertEquals(Calendar.FEBRUARY, projected.get(Calendar.MONTH));
        assertEquals(28, projected.get(Calendar.DAY_OF_MONTH));
        assertEquals(9, projected.get(Calendar.HOUR_OF_DAY));
        assertEquals(30, projected.get(Calendar.MINUTE));

        TaskItem instance = new TaskItem(monthly.getTitle())
                .setRecurringOccurrence(monthly.getId(), occurrence)
                .schedule(occurrence, occurrence + monthly.getDurationMillis(), false)
                .setSource(TaskItem.SOURCE_RECURRING);
        Map<String, Object> json = parseObject(instance.toJsonValue());
        assertTrue(instance.isRecurringOccurrence());
        assertEquals(monthly.getId(), json.get("recurringGroupId"));
        assertEquals(occurrence, longValue(json.get("recurringOccurrenceAt")));

        RecurringTaskGroup everyThreeDays = new RecurringTaskGroup(
                "拉伸", anchor.getTimeInMillis(), 15 * 60_000L,
                RecurringTaskGroup.Unit.DAYS, 3);
        Calendar afterSixDays = (Calendar) anchor.clone();
        afterSixDays.add(Calendar.DAY_OF_YEAR, 6);
        Calendar afterFiveDays = (Calendar) anchor.clone();
        afterFiveDays.add(Calendar.DAY_OF_YEAR, 5);
        assertTrue(everyThreeDays.occurrenceStartOnDay(afterSixDays.getTimeInMillis()) > 0);
        assertEquals(0L, everyThreeDays.occurrenceStartOnDay(afterFiveDays.getTimeInMillis()));
    }

    @Test
    public void advancedRecurrenceSupportsWeekdaysSelectionsLimitsAndMonthlyOrdinal() {
        Calendar monday = Calendar.getInstance();
        monday.clear();
        monday.set(2030, Calendar.JANUARY, 7, 9, 0);
        RecurringTaskGroup workdays = new RecurringTaskGroup("工作日站会",
                monday.getTimeInMillis(), 30 * 60_000L,
                RecurringTaskGroup.Unit.WEEKS, 1)
                .setAdvancedRule(true, null, 0, 0, 0, 3);
        Calendar thursday = (Calendar) monday.clone();
        thursday.add(Calendar.DAY_OF_YEAR, 3);
        assertEquals(0L, workdays.occurrenceStartOnDay(thursday.getTimeInMillis()));
        Calendar wednesday = (Calendar) monday.clone();
        wednesday.add(Calendar.DAY_OF_YEAR, 2);
        assertTrue(workdays.occurrenceStartOnDay(wednesday.getTimeInMillis()) > 0);

        RecurringTaskGroup selected = new RecurringTaskGroup("隔周例会",
                monday.getTimeInMillis(), 60 * 60_000L,
                RecurringTaskGroup.Unit.WEEKS, 2)
                .setAdvancedRule(false,
                        Arrays.asList(Calendar.MONDAY, Calendar.THURSDAY),
                        0, 0, 0, 0);
        Calendar firstThursday = (Calendar) monday.clone();
        firstThursday.add(Calendar.DAY_OF_YEAR, 3);
        Calendar nextThursday = (Calendar) firstThursday.clone();
        nextThursday.add(Calendar.DAY_OF_YEAR, 7);
        assertTrue(selected.occurrenceStartOnDay(firstThursday.getTimeInMillis()) > 0);
        assertEquals(0L, selected.occurrenceStartOnDay(nextThursday.getTimeInMillis()));

        Calendar firstMonday = Calendar.getInstance();
        firstMonday.clear();
        firstMonday.set(2030, Calendar.APRIL, 1, 10, 0);
        RecurringTaskGroup ordinal = new RecurringTaskGroup("月度回顾",
                firstMonday.getTimeInMillis(), 60 * 60_000L,
                RecurringTaskGroup.Unit.MONTHS, 1)
                .setAdvancedRule(false, null, 2, Calendar.MONDAY, 0, 0);
        Calendar secondMonday = Calendar.getInstance();
        secondMonday.clear();
        secondMonday.set(2030, Calendar.APRIL, 8, 12, 0);
        assertTrue(ordinal.occurrenceStartOnDay(secondMonday.getTimeInMillis()) > 0);
    }

    @Test
    public void occurrenceOverrideAndReminderKeepIndependentBusinessState() {
        long occurrence = 1_900_000_000_000L;
        RecurringOccurrenceOverride skipped =
                RecurringOccurrenceOverride.skipped("series-1", occurrence);
        assertTrue(skipped.isSkipped());
        assertEquals("series-1@" + occurrence, skipped.getId());

        RecurringOccurrenceOverride moved = RecurringOccurrenceOverride.rescheduled(
                "series-1", occurrence, occurrence + 60_000L, occurrence + 3_600_000L);
        assertTrue(moved.hasTimeOverride());
        assertEquals(occurrence + 60_000L, moved.getStartAt());

        ReminderEvent reminder = ReminderEvent.triggered(ReminderEvent.TargetType.TASK,
                "task-1", "deadline", "交报告", occurrence);
        String stableId = reminder.getId();
        reminder.snoozeUntil(System.currentTimeMillis() + 60_000L);
        assertEquals(ReminderEvent.State.SNOOZED, reminder.getState());
        reminder.makeActionRequired().markHandled(System.currentTimeMillis());
        assertEquals(ReminderEvent.State.HANDLED, reminder.getState());
        assertEquals(stableId, reminder.getId());
    }

    @Test
    public void chatAndFocusRecordJsonPreserveStructuredState() throws Exception {
        ChatMessage message = new ChatMessage(
                ChatMessage.Role.ASSISTANT,
                "请确认安排",
                "{\"type\":\"create_task\",\"title\":\"报告\"}",
                ChatMessage.ActionState.PENDING_CONFIRMATION
        );
        Map<String, Object> messageJson = parseObject(message.toJsonValue());
        assertEquals(ChatMessage.Role.ASSISTANT.storageValue(), messageJson.get("role"));
        assertEquals(ChatMessage.ActionState.PENDING_CONFIRMATION.storageValue(),
                messageJson.get("actionState"));
        assertEquals("{\"type\":\"create_task\",\"title\":\"报告\"}",
                messageJson.get("actionPayload"));
        ChatMessage executed = message.withActionState(ChatMessage.ActionState.EXECUTED);
        assertTrue(executed.getUpdatedAt() > message.getUpdatedAt());
        assertEquals(message.getCreatedAt(), executed.getCreatedAt());
        assertEquals(ChatMessage.ActionState.EXECUTED, executed.getActionState());

        ChatMessage partiallyExecuted = message.withActionItemStates(
                Arrays.asList(0), ChatMessage.ActionItemState.EXECUTED, 2);
        assertEquals(ChatMessage.ActionState.PENDING_CONFIRMATION,
                partiallyExecuted.getActionState());
        assertEquals(ChatMessage.ActionItemState.EXECUTED,
                partiallyExecuted.getActionItemState(0));
        assertEquals(ChatMessage.ActionItemState.PENDING,
                partiallyExecuted.getActionItemState(1));
        ChatMessage superseded = partiallyExecuted.cancelPendingActionItems();
        assertEquals(ChatMessage.ActionState.EXECUTED, superseded.getActionState());
        assertEquals(ChatMessage.ActionItemState.EXECUTED, superseded.getActionItemState(0));
        assertEquals(ChatMessage.ActionItemState.CANCELLED, superseded.getActionItemState(1));
        List<?> savedItemStates = (List<?>) parseObject(superseded.toJsonValue())
                .get("actionItemStates");
        assertEquals("executed", savedItemStates.get(0));
        assertEquals("cancelled", savedItemStates.get(1));

        long sessionMidday = 1_786_512_000_000L;
        ChatMessage assignedSession = superseded.withConversationDay(sessionMidday);
        assertEquals(ChatSessionPolicy.startOfDay(sessionMidday, null),
                assignedSession.getConversationDay());
        assertEquals(superseded.getCreatedAt(), assignedSession.getCreatedAt());
        assertEquals(assignedSession.getConversationDay(),
                longValue(parseObject(assignedSession.toJsonValue()).get("conversationDay")));

        ChatMessage withAttachment = new ChatMessage(
                ChatMessage.Role.USER,
                "参考这份资料",
                null,
                ChatMessage.ActionState.NONE,
                Arrays.asList(new ChatMessage.Attachment("计划.png", "image/png", 2048))
        );
        Map<String, Object> attachmentJson = parseObject(withAttachment.toJsonValue());
        List<?> savedAttachments = (List<?>) attachmentJson.get("attachments");
        assertEquals(1, savedAttachments.size());
        assertEquals("计划.png", objectValue(savedAttachments.get(0)).get("name"));
        assertEquals(2048L, longValue(objectValue(savedAttachments.get(0)).get("size")));

        FocusRecord record = new FocusRecord(
                "task-1", "完成报告", 10_000L, 25 * 60_000L, 2,
                10_000L + 25 * 60_000L, true);
        Map<String, Object> recordJson = parseObject(record.toJsonValue());
        assertEquals("task-1", recordJson.get("taskId"));
        assertEquals(25 * 60_000L, longValue(recordJson.get("durationMillis")));
        assertEquals(2, intValue(recordJson.get("interruptionCount")));
        assertEquals(Boolean.TRUE, recordJson.get("taskCompleted"));
    }

    @Test
    public void focusDurationUsesOneConsistentRoundedMinuteValue() {
        assertEquals(0, FocusRecord.durationMinutesRounded(0));
        assertEquals(1, FocusRecord.durationMinutesRounded(1));
        assertEquals(1, FocusRecord.durationMinutesRounded(89_999L));
        assertEquals(2, FocusRecord.durationMinutesRounded(90_000L));
        assertEquals(25, FocusRecord.durationMinutesRounded(25 * 60_000L));
    }

    @Test
    public void settingsCanExcludeApiKeyWithoutLosingOtherConfiguration() throws Exception {
        AppSettings settings = new AppSettings()
                .setAiService("https://example.test/v1", "secret-key", "test-model")
                .setGreetingText("先做最重要的一件事")
                .setFocusDurations(30, 7, 20)
                .setFocusCycleCounts(4, 3, 1)
                .setAvailableWindow(8 * 60, 22 * 60)
                .setWorkWindow(9 * 60, 17 * 60)
                .setBlockedTimeRanges(Arrays.asList(
                        new AppSettings.TimeRange(12 * 60, 13 * 60, "午餐")))
                .setNotificationSettings(true, false, true, true, false)
                .setPairingInfo("123456", "桌面端")
                .setSyncConfiguration("wss://sync.example.test", true);

        Map<String, Object> safeJson = parseObject(settings.toJsonValue(false));
        Map<String, Object> safeAi = objectValue(safeJson.get("ai"));
        assertFalse(safeAi.containsKey("apiKey"));

        Map<String, Object> localJson = parseObject(settings.toJsonValue(true));
        Map<String, Object> localAi = objectValue(localJson.get("ai"));
        assertEquals("secret-key", localAi.get("apiKey"));
        assertEquals("先做最重要的一件事", localJson.get("greetingText"));
        Map<String, Object> focus = objectValue(localJson.get("focus"));
        assertEquals(30, intValue(focus.get("focusDurationMinutes")));
        assertEquals(4, intValue(focus.get("focusRoundsPerCycle")));
        assertFalse(focus.containsKey("shortBreakRoundsPerCycle"));
        assertFalse(focus.containsKey("longBreakRoundsPerCycle"));
        assertEquals(3, settings.copy().getShortBreakRoundsPerCycle());
        assertFalse(settings.isLastFocusInCycle(1));
        assertFalse(settings.isLastFocusInCycle(3));
        assertTrue(settings.isLastFocusInCycle(4));
        assertTrue(settings.isLastFocusInCycle(8));
        Map<String, Object> time = objectValue(localJson.get("timePreferences"));
        List<?> blocked = (List<?>) time.get("blockedTimeRanges");
        assertEquals("午餐", objectValue(blocked.get(0)).get("label"));
        assertEquals(Boolean.TRUE, objectValue(localJson.get("sync")).get("syncEnabled"));
        assertEquals("先做最重要的一件事", settings.copy().getGreetingText());
    }

    @Test
    public void focusAutomationNormalizesConflictingOptions() throws Exception {
        AppSettings settings = new AppSettings()
                .setFocusCountUpEnabled(true)
                .setFocusEndSignals(false, true)
                .setFocusAutomation(true, true, true, true);

        assertTrue(settings.isFocusCountUpEnabled());
        assertFalse(settings.isFocusEndSoundEnabled());
        assertTrue(settings.isFocusEndVibrationEnabled());
        assertTrue(settings.isAutoAdvanceFocusSegment());
        assertTrue(settings.isBreaksDisabled());
        assertFalse(settings.isAutoStartBreak());
        assertTrue(settings.isAutoStartNextPomodoro());

        settings.setAutoStartNextRound(false);
        assertFalse(settings.isAutoStartBreak());
        assertFalse(settings.isAutoStartNextPomodoro());
        Map<String, Object> focus = objectValue(
                parseObject(settings.toJsonValue(false)).get("focus"));
        assertEquals(Boolean.TRUE, focus.get("focusCountUpEnabled"));
        assertEquals(Boolean.FALSE, focus.get("focusEndSoundEnabled"));
        assertEquals(Boolean.TRUE, focus.get("focusEndVibrationEnabled"));
        assertEquals(Boolean.TRUE, focus.get("breaksDisabled"));
        assertEquals(Boolean.FALSE, focus.get("autoAdvanceFocusSegment"));

        AppSettings copied = settings.copy();
        assertFalse(copied.isFocusEndSoundEnabled());
        assertTrue(copied.isFocusEndVibrationEnabled());
    }

    @Test
    public void runningFocusCanBeSnapshottedAndRestored() throws Exception {
        FocusState ready = FocusState.ready("task-1", FocusState.Mode.FOCUS, 60_000L, 3,
                "深度写作");
        FocusState running = ready.startOrResume(100_000L);
        assertEquals(45_000L, running.remainingAt(115_000L));

        FocusState snapshot = running.snapshotAt(115_000L);
        Map<String, Object> json = parseObject(snapshot.toJsonValue());
        assertEquals(FocusState.State.RUNNING.storageValue(), json.get("state"));
        assertEquals(45_000L, longValue(json.get("remainingMillis")));
        assertEquals(3, intValue(json.get("pomodoroIndex")));
        assertEquals(ready.getSessionId(), snapshot.getSessionId());
        assertEquals("深度写作", snapshot.getSessionLabel());
        assertEquals(snapshot.getSessionId(), json.get("sessionId"));
        assertEquals(snapshot.getSessionLabel(), json.get("sessionLabel"));
        assertEquals(Boolean.FALSE, json.get("naturalCompletionRecorded"));

        FocusState paused = snapshot.pause(120_000L);
        assertEquals(FocusState.State.PAUSED, paused.getState());
        assertEquals(1, paused.getInterruptionCount());
        assertNull(FocusState.idle().getTaskId());
    }

    @Test
    public void readyFocusHasDistinctStateAndRetainsSessionOnResume() {
        FocusState ready = FocusState.ready("task-1", FocusState.Mode.FOCUS, 60_000L, 1);
        assertEquals(FocusState.State.READY, ready.getState());
        FocusState running = ready.startOrResume(100_000L);
        FocusState paused = running.pause(110_000L);
        assertEquals(50_000L, paused.remainingAt(500_000L));
        FocusState resumed = paused.startOrResume(500_000L);
        assertEquals(40_000L, resumed.remainingAt(510_000L));
        assertEquals(ready.getSessionId(), resumed.getSessionId());
        assertEquals(1, resumed.getInterruptionCount());
    }

    @Test
    public void duplicateStartDoesNotResetRunningTimer() {
        FocusState running = FocusState.ready(null, FocusState.Mode.FOCUS, 60_000L, 1)
                .startOrResume(100_000L);
        FocusState duplicate = running.startOrResume(120_000L);
        assertEquals(40_000L, duplicate.remainingAt(120_000L));
        assertEquals(100_000L, duplicate.getLastStartedAt());
    }

    @Test
    public void pausingAtDeadlineFinishesWithoutInterruption() {
        FocusState expired = FocusState.ready(null, FocusState.Mode.SHORT_BREAK, 60_000L, 0)
                .startOrResume(100_000L).pause(160_000L);
        assertEquals(FocusState.State.FINISHED, expired.getState());
        assertEquals(0L, expired.getRemainingMillis());
        assertEquals(0, expired.getInterruptionCount());
    }

    @Test
    public void naturalFocusRecordUsesStableSessionIdentity() {
        FocusRecord first = FocusRecord.naturalCompletion("session-1", null, "无任务专注",
                1_000L, 60_000L, 0, 61_000L);
        FocusRecord duplicate = FocusRecord.naturalCompletion("session-1", null, "无任务专注",
                1_000L, 60_000L, 0, 61_000L);
        assertEquals(first.getId(), duplicate.getId());
        assertEquals("completed", first.getOutcome());
    }

    @Test
    public void manualAndNaturalSettlementShareOneStableRecordIdentity() {
        FocusRecord natural = FocusRecord.naturalCompletion("session-1", null, "专注",
                1_000L, 60_000L, 0, 61_000L);
        FocusRecord manual = FocusRecord.sessionCompletion("session-1", null, "专注",
                1_000L, 30_000L, 1, 31_000L, "interrupted", "临时电话");
        assertEquals(natural.getId(), manual.getId());
        assertEquals("interrupted", manual.getOutcome());
        assertEquals("临时电话", manual.getNote());
        assertEquals(30_000L, manual.getDurationMillis());
    }

    @Test
    public void focusCanRunWithoutTaskAndEarlyEndKeepsReason() throws Exception {
        FocusState noTask = FocusState.ready(null, FocusState.Mode.FOCUS, 25 * 60_000L, 1);
        assertNull(noTask.getTaskId());

        FocusRecord interrupted = new FocusRecord(
                null,
                "临时写作",
                10_000L,
                5 * 60_000L,
                1,
                10_000L + 5 * 60_000L,
                false,
                "interrupted",
                "临时电话"
        );
        Map<String, Object> json = parseObject(interrupted.toJsonValue());
        assertNull(json.get("taskId"));
        assertEquals("interrupted", json.get("outcome"));
        assertEquals("临时电话", json.get("note"));
    }

    @Test
    public void syncSettingsExcludeInstallationIdentityAndSecrets() throws Exception {
        AppSettings settings = new AppSettings()
                .setAiService("https://example.test/v1", "local-secret", "shared-model")
                .setFocusDurations(40, 8, 20)
                .setPairingInfo("123456", "desktop-local")
                .setSyncConfiguration("192.168.1.20:37681", true)
                .setLastSyncAt(1_900_000_000_000L);

        Map<String, Object> syncJson = parseObject(settings.toSyncJsonValue());

        assertFalse(syncJson.containsKey("sync"));
        assertFalse(objectValue(syncJson.get("ai")).containsKey("apiKey"));
        assertEquals("shared-model", objectValue(syncJson.get("ai")).get("model"));
        assertEquals(40, intValue(objectValue(syncJson.get("focus"))
                .get("focusDurationMinutes")));
    }

    @Test
    public void importedPreferencesKeepLocalPairingIdentity() {
        AppSettings local = new AppSettings()
                .setPairingInfo("123456", "desktop-local")
                .setSyncConfiguration("192.168.1.20:37681", true)
                .setLastSyncAt(1_900_000_000_000L);
        String localDeviceId = local.getDeviceId();

        AppSettings incoming = new AppSettings()
                .setPairingInfo("654321", "wrong-peer")
                .setSyncConfiguration("10.0.0.8:9999", true)
                .setLastSyncAt(2_000_000_000_000L)
                .preserveLocalSyncStateFrom(local);

        assertEquals(localDeviceId, incoming.getDeviceId());
        assertEquals("123456", incoming.getPairingCode());
        assertEquals("desktop-local", incoming.getPairedDeviceName());
        assertEquals("192.168.1.20:37681", incoming.getSyncEndpoint());
        assertEquals(1_900_000_000_000L, incoming.getLastSyncAt());
        assertTrue(incoming.isSyncEnabled());
    }

    @Test
    public void localSyncMetadataDoesNotAdvanceSharedPreferenceVersion() {
        AppSettings settings = new AppSettings();
        long sharedVersion = settings.getUpdatedAt();

        settings.setPairingInfo("123456", "desktop")
                .setSyncConfiguration("192.168.1.20:37681", true)
                .setLastSyncAt(1_900_000_000_000L);

        assertEquals(sharedVersion, settings.getUpdatedAt());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(Map<String, Object> source) {
        return (Map<String, Object>) toJavaValue(source);
    }

    private static Object toJavaValue(Object value) {
        if (value == null) return null;
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                result.put(entry.getKey(), toJavaValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<>();
            for (Object item : (Iterable<?>) value) result.add(toJavaValue(item));
            return result;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectValue(Object value) {
        return (Map<String, Object>) value;
    }

    private static int intValue(Object value) {
        return ((Number) value).intValue();
    }

    private static long longValue(Object value) {
        return ((Number) value).longValue();
    }
}
