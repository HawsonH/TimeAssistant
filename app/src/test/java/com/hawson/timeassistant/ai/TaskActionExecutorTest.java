package com.hawson.timeassistant.ai;

import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.TaskItem;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TaskActionExecutorTest {
    @Test
    public void parsesEpochAndIsoTimestamps() throws Exception {
        assertEquals(1_700_000_000_000L, TaskActionExecutor.parseTimestamp(1_700_000_000_000L));
        assertEquals(1_700_000_000_000L,
                TaskActionExecutor.parseTimestamp("2023-11-14T22:13:20Z"));
        assertEquals(1_700_000_000_000L,
                TaskActionExecutor.parseTimestamp("2023-11-15T06:13:20+08:00"));

        try {
            TaskActionExecutor.parseTimestamp("2023-11-15T06:13:20");
            fail("Expected timezone-less timestamp to be rejected");
        } catch (TaskActionExecutor.ExecutionException error) {
            assertTrue(error.getMessage().contains("必须包含时区"));
        }
    }

    @Test
    public void validatesPrimitiveActionFields() throws Exception {
        assertEquals(3, TaskActionExecutor.parsePriority("3"));
        assertEquals(45, TaskActionExecutor.positiveInt(45, "durationMinutes"));
        assertEquals(Arrays.asList("工作", "写作"),
                TaskActionExecutor.stringList(Arrays.asList("工作", "写作"), "tags"));
    }

    @Test
    public void describesConfirmedPlanWithoutMutatingRepository() throws Exception {
        AiPlan plan = AiPlan.parse("{\"message\":\"安排好了\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"create_task\",\"title\":\"写报告\","
                + "\"estimatedMinutes\":60,\"reason\":\"user-request\","
                + "\"summary\":\"one task\",\"subtasks\":[\"收集资料\","
                + "{\"title\":\"撰写初稿\",\"estimatedMinutes\":45}]}]}");
        TaskActionExecutor.ConfirmationDescription description = TaskActionExecutor.describe(plan);
        assertTrue(description.items.get(0).contains("写报告"));
        assertTrue(description.items.get(0).contains("2 个子任务"));
        assertEquals(false, description.destructive);
    }

    @Test
    public void executesConfirmedGreetingUpdate() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        AiPlan plan = AiPlan.parse("{\"message\":\"换一句问候\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"set_greeting\",\"text\":\"今天先照顾好节奏\"}]}");

        TaskActionExecutor.ExecutionResult result =
                TaskActionExecutor.executeForTesting(repository, plan);

        assertEquals("今天先照顾好节奏", repository.getSettings().getGreetingText());
        assertEquals("问候语已更新", result.summary);
    }

    @Test
    public void conversationalCreateDefaultsToTodoAndSupportsRepeatingCountdown() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        long before = System.currentTimeMillis();
        AiPlan plan = AiPlan.parse("{\"message\":\"已经记下\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"create_task\",\"title\":\"喝水\","
                + "\"countdownMinutes\":30,\"repeatCountdown\":true}]}");

        TaskActionExecutor.executeForTesting(repository, plan);

        TaskItem task = findTaskByTitle(repository.getAllTasks(), "喝水");
        assertNotNull(task);
        assertFalse(task.requiresTimeArrangement());
        assertFalse(task.isScheduled());
        assertTrue(task.isRepeatingCountdown());
        assertEquals(30 * 60_000L, task.getCountdownIntervalMillis());
        assertTrue(task.getCountdownTargetAt() >= before + 30 * 60_000L);
        assertTrue(task.getCountdownTargetAt() <= System.currentTimeMillis() + 30 * 60_000L);
    }

    @Test
    public void executesCreateTaskWithInlineSubtasksAndSchedule() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        long startAt = 1_700_000_000_000L;
        long endAt = startAt + 120 * 60_000L;
        AiPlan plan = AiPlan.parse("{\"message\":\"报告计划已整理\","
                + "\"needsClarification\":false,\"actions\":[{"
                + "\"type\":\"create_task\",\"title\":\"课程报告\","
                + "\"description\":\"完成期末课程报告\",\"priority\":3,"
                + "\"estimatedMinutes\":120,\"scheduledStartAt\":" + startAt + ","
                + "\"scheduledEndAt\":" + endAt + ",\"locked\":true,"
                + "\"subtasks\":[\"收集资料\",{\"title\":\"撰写初稿\","
                + "\"estimatedMinutes\":60,\"tags\":[\"写作\"]}]}]}");

        TaskActionExecutor.ExecutionResult result =
                TaskActionExecutor.executeForTesting(repository, plan);

        assertEquals(3, result.affectedTasks.size());
        assertEquals(3, repository.getAllTasks().size());
        TaskItem parent = findTaskByTitle(repository.getAllTasks(), "课程报告");
        assertNotNull(parent);
        assertEquals(TaskItem.Status.SCHEDULED, parent.getStatus());
        assertTrue(parent.requiresTimeArrangement());
        assertEquals(startAt, parent.getScheduledStartAt());
        assertEquals(endAt, parent.getScheduledEndAt());
        assertTrue(parent.isLocked());
        assertEquals(2, parent.getChildTaskIds().size());

        TaskItem research = findTaskByTitle(repository.getAllTasks(), "收集资料");
        TaskItem draft = findTaskByTitle(repository.getAllTasks(), "撰写初稿");
        assertNotNull(research);
        assertNotNull(draft);
        assertEquals(parent.getId(), research.getParentTaskId());
        assertEquals(parent.getId(), draft.getParentTaskId());
        assertTrue(parent.getChildTaskIds().contains(research.getId()));
        assertTrue(parent.getChildTaskIds().contains(draft.getId()));
        assertEquals(60, draft.getEstimatedDurationMinutes());
        assertEquals(Collections.singletonList("写作"), draft.getTags());
    }

    @Test
    public void rollsBackInlineParentAndChildrenWhenChildPersistenceFails() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TaskItem existing = repository.upsertTask(new TaskItem("保留任务"));
        repository.failOnTitle = "子任务二";
        AiPlan plan = AiPlan.parse("{\"message\":\"子任务持久化会失败\","
                + "\"needsClarification\":false,\"actions\":["
                + "{\"type\":\"create_task\",\"title\":\"不应保留\","
                + "\"subtasks\":[\"子任务一\",\"子任务二\"]}]}");

        try {
            TaskActionExecutor.executeForTesting(repository, plan);
            fail("Expected the second action to fail");
        } catch (TaskActionExecutor.ExecutionException error) {
            assertEquals(0, error.actionIndex);
            assertEquals(AiPlan.Action.Type.CREATE_TASK, error.actionType);
            assertTrue(error.getMessage().contains("已回滚全部变更"));
        }

        List<TaskItem> remaining = repository.getAllTasks();
        assertEquals(1, remaining.size());
        assertNotNull(repository.getTask(existing.getId()));
        assertNull(findTaskByTitle(remaining, "不应保留"));
        assertNull(findTaskByTitle(remaining, "子任务一"));
        assertNull(findTaskByTitle(remaining, "子任务二"));
    }

    @Test
    public void rejectsCreateOutsideAvailableWindowWithoutLeavingTask() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        repository.settings = new AppSettings().setAvailableWindow(9 * 60, 18 * 60);
        long startAt = localTime(10, 8, 30);
        AiPlan plan = AiPlan.parse("{\"message\":\"安排\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"create_task\",\"title\":\"过早任务\","
                + "\"scheduledStartAt\":" + startAt + ",\"scheduledEndAt\":"
                + (startAt + 30 * 60_000L) + "}]}");

        TaskActionExecutor.ExecutionException error = executeExpectingFailure(repository, plan);

        assertTrue(error.getMessage().contains("每日可用时间"));
        assertTrue(repository.getAllTasks().isEmpty());
    }

    @Test
    public void rejectsUpdateInsideBlockedRangeAndRollsBack() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        repository.settings = new AppSettings()
                .setAvailableWindow(7 * 60, 23 * 60)
                .setBlockedTimeRanges(Collections.singletonList(
                        new AppSettings.TimeRange(12 * 60, 13 * 60, "午餐")
                ));
        TaskItem task = repository.upsertTask(new TaskItem("午间任务"));
        long startAt = localTime(10, 12, 30);
        AiPlan plan = AiPlan.parse("{\"message\":\"安排\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"update_task\",\"taskId\":\""
                + task.getId() + "\",\"changes\":{\"scheduledStartAt\":" + startAt
                + ",\"scheduledEndAt\":" + (startAt + 30 * 60_000L) + "}}]}");

        TaskActionExecutor.ExecutionException error = executeExpectingFailure(repository, plan);

        assertTrue(error.getMessage().contains("午餐"));
        assertFalse(repository.getTask(task.getId()).isScheduled());
    }

    @Test
    public void rejectsOverlapButAllowsAdjacentTask() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        long existingStart = localTime(10, 14, 0);
        repository.upsertTask(new TaskItem("已有安排")
                .schedule(existingStart, existingStart + 60 * 60_000L, false));
        TaskItem target = repository.upsertTask(new TaskItem("待安排"));

        TaskActionExecutor.ExecutionException error = executeExpectingFailure(
                repository,
                schedulePlan(target.getId(), existingStart + 30 * 60_000L, 30)
        );
        assertTrue(error.getMessage().contains("已有安排"));
        assertFalse(repository.getTask(target.getId()).isScheduled());

        TaskActionExecutor.executeForTesting(
                repository,
                schedulePlan(target.getId(), existingStart + 60 * 60_000L, 30)
        );
        assertEquals(existingStart + 60 * 60_000L,
                repository.getTask(target.getId()).getScheduledStartAt());
    }

    @Test
    public void honorsDisabledAiReschedulingForMoveAndClear() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        repository.settings = new AppSettings()
                .setAvailableWindow(0, 24 * 60)
                .setAllowAiRescheduleUnlockedTasks(false);
        long originalStart = localTime(10, 10, 0);
        TaskItem task = repository.upsertTask(new TaskItem("手动安排")
                .schedule(originalStart, originalStart + 30 * 60_000L, false));

        TaskActionExecutor.ExecutionException moveError = executeExpectingFailure(
                repository,
                schedulePlan(task.getId(), originalStart + 60 * 60_000L, 30)
        );
        assertTrue(moveError.getMessage().contains("不允许 AI 调整"));
        assertEquals(originalStart, repository.getTask(task.getId()).getScheduledStartAt());

        AiPlan clearPlan = AiPlan.parse("{\"message\":\"清除\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"update_task\",\"taskId\":\""
                + task.getId() + "\",\"changes\":{\"scheduledStartAt\":null}}]}");
        TaskActionExecutor.ExecutionException clearError =
                executeExpectingFailure(repository, clearPlan);
        assertTrue(clearError.getMessage().contains("不允许 AI 清除"));
        assertEquals(originalStart, repository.getTask(task.getId()).getScheduledStartAt());
    }

    @Test
    public void supportsOvernightAvailabilityAndBlockedRanges() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        repository.settings = new AppSettings()
                .setAvailableWindow(22 * 60, 7 * 60)
                .setBlockedTimeRanges(Collections.singletonList(
                        new AppSettings.TimeRange(30, 60, "夜间勿扰")
                ));
        TaskItem late = repository.upsertTask(new TaskItem("深夜工作"));
        long lateStart = localTime(10, 23, 0);

        TaskActionExecutor.executeForTesting(
                repository,
                schedulePlan(late.getId(), lateStart, 60)
        );
        assertEquals(lateStart, repository.getTask(late.getId()).getScheduledStartAt());

        TaskItem blocked = repository.upsertTask(new TaskItem("勿扰冲突"));
        long blockedStart = localTime(11, 0, 30);
        TaskActionExecutor.ExecutionException error = executeExpectingFailure(
                repository,
                schedulePlan(blocked.getId(), blockedStart, 20)
        );
        assertTrue(error.getMessage().contains("夜间勿扰"));
        assertFalse(repository.getTask(blocked.getId()).isScheduled());
    }

    private static AiPlan schedulePlan(String taskId, long startAt, int durationMinutes)
            throws Exception {
        return AiPlan.parse("{\"message\":\"安排\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"schedule_task\",\"taskId\":\""
                + taskId + "\",\"startAt\":" + startAt + ",\"durationMinutes\":"
                + durationMinutes + "}]}");
    }

    private static TaskActionExecutor.ExecutionException executeExpectingFailure(
            MemoryRepository repository,
            AiPlan plan
    ) throws Exception {
        try {
            TaskActionExecutor.executeForTesting(repository, plan);
            fail("Expected schedule validation to fail");
            return null;
        } catch (TaskActionExecutor.ExecutionException error) {
            return error;
        }
    }

    private static long localTime(int dayOffset, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, dayOffset);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static TaskItem findTaskByTitle(List<TaskItem> tasks, String title) {
        for (TaskItem task : tasks) {
            if (title.equals(task.getTitle())) return task;
        }
        return null;
    }

    private static final class MemoryRepository implements TaskActionExecutor.RepositoryAccess {
        private final LinkedHashMap<String, TaskItem> tasks = new LinkedHashMap<>();
        private final Map<String, Snapshot> snapshots = new HashMap<>();
        private FocusState currentFocus = FocusState.idle();
        private AppSettings settings = new AppSettings().setAvailableWindow(0, 24 * 60);
        private int nextSnapshotId;
        private String failOnTitle;

        @Override
        public String createSnapshot() {
            String id = "snapshot-" + (++nextSnapshotId);
            snapshots.put(id, new Snapshot(tasks, currentFocus, settings));
            return id;
        }

        @Override
        public void restoreSnapshot(String snapshot) {
            Snapshot stored = snapshots.get(snapshot);
            if (stored == null) throw new IllegalArgumentException("snapshot not found");
            tasks.clear();
            for (TaskItem task : stored.tasks.values()) tasks.put(task.getId(), task.copy());
            currentFocus = stored.currentFocus;
            settings = stored.settings.copy();
        }

        @Override
        public TaskItem upsertTask(TaskItem task) {
            if (task.getTitle().equals(failOnTitle)) {
                throw new IllegalStateException("simulated persistence failure");
            }
            TaskItem stored = task.copy();
            tasks.put(stored.getId(), stored);
            return stored.copy();
        }

        @Override
        public TaskItem getTask(String id) {
            TaskItem task = tasks.get(id);
            return task == null ? null : task.copy();
        }

        @Override
        public List<TaskItem> getAllTasks() {
            List<TaskItem> result = new ArrayList<>();
            for (TaskItem task : tasks.values()) result.add(task.copy());
            return result;
        }

        @Override
        public AppSettings getSettings() {
            return settings.copy();
        }

        @Override
        public void saveSettings(AppSettings settings) {
            this.settings = settings.copy();
        }

        @Override
        public boolean deleteTask(String id) {
            TaskItem removed = tasks.remove(id);
            if (removed == null) return false;
            for (TaskItem task : tasks.values()) {
                task.removeChildTaskId(id);
                if (id.equals(task.getParentTaskId())) task.setParentTaskId(null);
            }
            if (id.equals(currentFocus.getTaskId())) currentFocus = FocusState.idle();
            return true;
        }

        @Override
        public TaskItem updateTaskStatus(String id, TaskItem.Status status) {
            TaskItem task = tasks.get(id);
            if (task == null) throw new IllegalArgumentException("task not found: " + id);
            task.setStatus(status);
            return task.copy();
        }

        @Override
        public FocusState getCurrentFocus() {
            return currentFocus;
        }

        @Override
        public void setCurrentFocus(FocusState focusState) {
            currentFocus = focusState;
        }

        @Override
        public void clearCurrentFocus() {
            currentFocus = FocusState.idle();
        }

        @Override
        public int getFocusDurationMinutes() {
            return 25;
        }

        private static final class Snapshot {
            final LinkedHashMap<String, TaskItem> tasks = new LinkedHashMap<>();
            final FocusState currentFocus;
            final AppSettings settings;

            Snapshot(Map<String, TaskItem> source, FocusState currentFocus, AppSettings settings) {
                for (TaskItem task : source.values()) tasks.put(task.getId(), task.copy());
                this.currentFocus = currentFocus;
                this.settings = settings.copy();
            }
        }
    }
}
