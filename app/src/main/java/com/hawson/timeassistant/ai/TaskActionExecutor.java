/**
 * AI 任务执行器
 *
 * 仅在用户明确确认后，将校验过的 AiPlan 应用到数据仓库；失败时自动回滚到执行前快照。
 */
package com.hawson.timeassistant.ai;

import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.ChatMessage;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.RecurringOccurrenceOverride;
import com.hawson.timeassistant.domain.RecurringTaskGroup;
import com.hawson.timeassistant.domain.TaskItem;

import java.math.BigDecimal;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Applies a validated {@link AiPlan} only through the explicitly-confirmed entry point.
 *
 * <p>The repository owns the atomic local write boundary and rolls back failed actions before
 * publishing changes. Undo requires an unchanged revision. The class never invokes itself from parsing or
 * description code, so callers must present {@link #describe(AiPlan)} and independently obtain
 * UI confirmation before calling {@link #executeConfirmed(AppRepository, AiPlan)}.</p>
 */
public final class TaskActionExecutor {
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final Set<String> PATCH_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("title", "description", "priority", "estimatedMinutes",
                    "estimated_minutes", "estimatedDurationMinutes", "durationMinutes",
                    "duration_minutes", "actualDurationMinutes", "actual_minutes", "dueAt",
                    "due_at", "deadline", "project", "tags", "startAt", "start_at",
                    "scheduledStartAt", "endAt", "end_at", "scheduledEndAt", "locked",
                    "requiresTimeArrangement", "requires_time_arrangement",
                    "reminderAt", "reminder_at", "countdownMinutes", "countdown_minutes", "repeatCountdown",
                    "repeat_countdown", "schedule", "type", "reason", "summary")));
    private static final Set<String> RECURRING_PATCH_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("title", "description", "startAt", "start_at",
                    "durationMinutes", "duration_minutes", "unit", "interval",
                    "workdaysOnly", "weekdays", "monthlyOrdinal", "monthlyWeekday",
                    "endsAt", "maxOccurrences")));

    private TaskActionExecutor() {}

    /** The sole mutation entry point; the method name intentionally requires confirmed intent. */
    public static ExecutionResult executeConfirmed(AppRepository repository, AiPlan plan)
            throws ExecutionException {
        return executeConfirmed(repository, plan, UUID.randomUUID().toString(), null,
                Collections.emptyList(), 0);
    }

    public static ExecutionResult executeConfirmed(AppRepository repository, AiPlan plan,
            String messageId, List<Integer> taskIndexes, int actionCount) throws ExecutionException {
        if (messageId == null) throw new IllegalArgumentException("messageId is required");
        return executeConfirmed(repository, plan, UUID.randomUUID().toString(), messageId,
                taskIndexes, actionCount);
    }

    /**
     * Execute a selected local sub-plan and mark its original proposal indexes in one commit.
     * External host work must run outside this call; its later writes invalidate this ticket.
     * Keep the returned result/sessionId in a UI ViewModel across rotation, not saved state.
     */
    public static ExecutionResult executeConfirmed(AppRepository repository, AiPlan plan,
            String sessionId, String messageId, List<Integer> actionIndexes, int actionCount)
            throws ExecutionException {
        if (repository == null) throw new IllegalArgumentException("repository 不能为空");
        if (plan == null) throw new IllegalArgumentException("plan must not be null");
        List<Integer> indexes = actionIndexes == null ? Collections.emptyList()
                : new ArrayList<>(actionIndexes);
        if (messageId != null && (indexes.size() != plan.actions.size()
                || new LinkedHashSet<>(indexes).size() != indexes.size() || indexes.isEmpty())) {
            throw new IllegalArgumentException("provide one distinct proposal index per local action");
        }
        try {
            AppRepository.ProposalSessionResult<ExecutionResult> session =
                    repository.runProposalSession(sessionId, messageId, indexes, actionCount, () -> {
                        if (messageId != null) {
                            ChatMessage message = repository.getChatMessage(messageId);
                            if (message == null) throw new IllegalArgumentException("proposal not found");
                            for (Integer index : indexes) {
                                if (index == null || index < 0 || index >= actionCount
                                        || (message.getActionItemState(index)
                                        != ChatMessage.ActionItemState.PENDING
                                        && message.getActionItemState(index)
                                        != ChatMessage.ActionItemState.FAILED)) {
                                    throw new IllegalArgumentException("提案操作不是待确认或可重试状态");
                                }
                            }
                        }
                        ExecutionResult result = executeConfirmed(new AppRepositoryAccess(repository),
                                plan, false);
                        if (messageId != null) repository.updateChatActionItemStates(messageId,
                                indexes, ChatMessage.ActionItemState.EXECUTED, actionCount);
                        return result;
                    });
            ExecutionResult value = session.value;
            return new ExecutionResult(value.affectedTasks, value.summary, value.destructive,
                    value.undoSnapshot, repository, sessionId, session.undoTicket);
        } catch (RuntimeException error) {
            throw new ExecutionException("本地提案执行失败：" + safeMessage(error), error);
        }
    }

    /**
     * Package-private adapter entry point for deterministic JVM tests of execution and rollback.
     * Production callers must use the explicitly confirmed {@link AppRepository} overload.
     */
    static ExecutionResult executeForTesting(RepositoryAccess repository, AiPlan plan)
            throws ExecutionException {
        if (repository == null) throw new IllegalArgumentException("repository 不能为空");
        synchronized (repository) {
            return executeConfirmed(repository, plan, true);
        }
    }

    private static ExecutionResult executeConfirmed(RepositoryAccess repository, AiPlan plan,
                                                    boolean restoreOnFailure)
            throws ExecutionException {
        if (plan == null) throw new IllegalArgumentException("plan 不能为空");
        if (plan.needsClarification) {
            throw new ExecutionException(-1, null, "计划仍需补充信息，不能执行");
        }

        ConfirmationDescription confirmation = describe(plan);
        final String undoSnapshot;
        try {
            undoSnapshot = restoreOnFailure ? repository.createSnapshot() : null;
        } catch (RuntimeException error) {
            throw new ExecutionException(-1, null, "无法创建执行前快照", error);
        }

        LinkedHashMap<String, TaskItem> affected = new LinkedHashMap<>();
        for (int index = 0; index < plan.actions.size(); index++) {
            AiPlan.Action action = plan.actions.get(index);
            try {
                apply(repository, action, affected);
            } catch (ExecutionException | RuntimeException error) {
                try {
                    if (restoreOnFailure) repository.restoreSnapshot(undoSnapshot);
                } catch (RuntimeException rollbackError) {
                    ExecutionException failure = new ExecutionException(index, action.type,
                            "操作失败，且自动回滚未成功：" + safeMessage(error), error);
                    failure.addSuppressed(rollbackError);
                    throw failure;
                }
                if (error instanceof ExecutionException) {
                    ExecutionException actionError = (ExecutionException) error;
                    throw new ExecutionException(index, action.type,
                            "操作失败，已回滚全部变更：" + actionError.getMessage(), actionError);
                }
                throw new ExecutionException(index, action.type,
                        "操作失败，已回滚全部变更：" + safeMessage(error), error);
            }
        }

        List<TaskItem> affectedTasks = new ArrayList<>();
        for (TaskItem task : affected.values()) affectedTasks.add(task.copy());
        boolean greetingOnly = !plan.actions.isEmpty();
        for (AiPlan.Action action : plan.actions) {
            if (action.type != AiPlan.Action.Type.SET_GREETING) greetingOnly = false;
        }
        String summary = plan.actions.isEmpty()
                ? "没有需要执行的操作"
                : (greetingOnly ? "问候语已更新"
                : affectedTasks.isEmpty()
                ? "已执行 " + plan.actions.size() + " 项操作"
                : "已执行 " + plan.actions.size() + " 项操作，影响 "
                + affectedTasks.size() + " 个任务");
        return new ExecutionResult(affectedTasks, summary, confirmation.destructive,
                undoSnapshot);
    }

    /** Pure description/validation for a confirmation card. It never touches a repository. */
    public static ConfirmationDescription describe(AiPlan plan) throws ExecutionException {
        if (plan == null) throw new IllegalArgumentException("plan 不能为空");
        List<String> items = new ArrayList<>();
        boolean destructive = false;
        for (int index = 0; index < plan.actions.size(); index++) {
            AiPlan.Action action = plan.actions.get(index);
            try {
                validateActionFields(action);
                items.add(describeAction(action));
                if (action.type == AiPlan.Action.Type.DELETE_TASK
                        || action.type == AiPlan.Action.Type.CANCEL_TASK
                        || action.type == AiPlan.Action.Type.STOP_RECURRING_GROUP) destructive = true;
            } catch (ExecutionException error) {
                throw new ExecutionException(index, action.type,
                        "无法生成确认说明：" + error.getMessage(), error);
            }
        }
        String heading = plan.needsClarification ? "需要补充信息"
                : (destructive ? "确认高影响操作" : "确认 AI 操作");
        return new ConfirmationDescription(heading, plan.message, items, destructive,
                plan.needsClarification);
    }

    private static void apply(RepositoryAccess repository, AiPlan.Action action,
                              LinkedHashMap<String, TaskItem> affected)
            throws ExecutionException {
        validateActionFields(action);
        Map<String, Object> fields = action.fields;
        switch (action.type) {
            case CREATE_TASK:
                createTask(repository, fields, affected);
                return;
            case UPDATE_TASK:
                updateTask(repository, fields, affected);
                return;
            case DELETE_TASK:
                deleteTask(repository, fields, affected);
                return;
            case CANCEL_TASK:
                setStatus(repository, fields, affected, TaskItem.Status.CANCELLED, true);
                return;
            case SCHEDULE_TASK:
                scheduleTask(repository, fields, affected);
                return;
            case COMPLETE_TASK:
                completeTask(repository, fields, affected);
                return;
            case SPLIT_TASK:
                splitTask(repository, fields, affected);
                return;
            case START_FOCUS:
                prepareFocus(repository, fields, affected);
                return;
            case SET_GREETING:
                setGreeting(repository, fields);
                return;
            case CREATE_RECURRING_GROUP:
                createRecurringGroup(repository, fields);
                return;
            case UPDATE_RECURRING_GROUP:
                updateRecurringGroup(repository, fields);
                return;
            case STOP_RECURRING_GROUP:
                stopRecurringGroup(repository, fields);
                return;
            case SET_RECURRING_OVERRIDE:
                setRecurringOverride(repository, fields);
                return;
            default:
                throw new ExecutionException("不支持的 action：" + action.type);
        }
    }

    private static void setGreeting(RepositoryAccess repository, Map<String, Object> fields)
            throws ExecutionException {
        String text = requiredString(value(fields, "text"), "text");
        try {
            AppSettings settings = repository.getSettings();
            settings.setGreetingText(text);
            repository.saveSettings(settings);
        } catch (IllegalArgumentException error) {
            throw new ExecutionException("问候语无效：" + error.getMessage(), error);
        }
    }

    private static void createRecurringGroup(RepositoryAccess repository,
                                             Map<String, Object> fields)
            throws ExecutionException {
        String title = requiredString(value(fields, "title"), "title");
        long startAt = positiveTimestamp(find(fields, "startAt", "start_at"), "startAt");
        Lookup durationField = find(fields, "durationMinutes", "duration_minutes");
        int duration = positiveInt(durationField.value, durationField.key);
        RecurringTaskGroup group = new RecurringTaskGroup(title, startAt,
                duration * MILLIS_PER_MINUTE,
                recurringUnit(value(fields, "unit")),
                optionalPositiveInt(fields, 1, "interval"));
        applyRecurringPatch(group, fields, true);
        repository.upsertRecurringGroup(group);
    }

    private static void updateRecurringGroup(RepositoryAccess repository,
                                             Map<String, Object> fields)
            throws ExecutionException {
        String groupId = requiredString(value(fields, "groupId"), "groupId");
        RecurringTaskGroup group = repository.getRecurringGroup(groupId);
        if (group == null) throw new ExecutionException("周期组不存在：" + groupId);
        Map<String, Object> changes = requiredMap(value(fields, "changes"), "changes");
        applyRecurringPatch(group, changes, false);
        repository.upsertRecurringGroup(group);
    }

    private static void stopRecurringGroup(RepositoryAccess repository,
                                           Map<String, Object> fields)
            throws ExecutionException {
        String groupId = requiredString(value(fields, "groupId"), "groupId");
        RecurringTaskGroup group = repository.getRecurringGroup(groupId);
        if (group == null) throw new ExecutionException("周期组不存在：" + groupId);
        repository.upsertRecurringGroup(group.setActive(false));
    }

    private static void setRecurringOverride(RepositoryAccess repository,
                                             Map<String, Object> fields)
            throws ExecutionException {
        String groupId = requiredString(value(fields, "groupId"), "groupId");
        if (repository.getRecurringGroup(groupId) == null) {
            throw new ExecutionException("周期组不存在：" + groupId);
        }
        long occurrenceAt = positiveTimestamp(find(fields, "occurrenceAt", "occurrence_at"),
                "occurrenceAt");
        String operation = requiredString(value(fields, "operation"), "operation")
                .toLowerCase(Locale.ROOT);
        if ("clear".equals(operation) || "restore".equals(operation)) {
            repository.deleteRecurringOverride(
                    RecurringOccurrenceOverride.stableId(groupId, occurrenceAt));
            return;
        }
        RecurringOccurrenceOverride override;
        if ("skip".equals(operation) || "skipped".equals(operation)) {
            override = RecurringOccurrenceOverride.skipped(groupId, occurrenceAt);
        } else {
            Lookup start = find(fields, "startAt", "start_at");
            Lookup end = find(fields, "endAt", "end_at");
            long startAt = start.present ? positiveTimestamp(start, "startAt") : 0;
            long endAt = end.present ? positiveTimestamp(end, "endAt") : 0;
            if ((startAt == 0) != (endAt == 0) || (startAt > 0 && endAt <= startAt)) {
                throw new ExecutionException("周期例外需要完整且有效的开始、结束时间");
            }
            if ("reschedule".equals(operation) || "rescheduled".equals(operation)) {
                if (startAt == 0) throw new ExecutionException("改期需要 startAt 和 endAt");
                override = RecurringOccurrenceOverride.rescheduled(
                        groupId, occurrenceAt, startAt, endAt);
            } else if ("modify".equals(operation) || "modified".equals(operation)) {
                String title = optionalText(fields, "title");
                String description = optionalTextAllowEmpty(fields, "description");
                if (title == null && description == null && startAt == 0) {
                    throw new ExecutionException("修改本次至少需要内容或时间变更");
                }
                override = RecurringOccurrenceOverride.modified(groupId, occurrenceAt,
                        title, description, startAt, endAt);
            } else {
                throw new ExecutionException("operation 仅支持 skip、reschedule、modify、restore");
            }
        }
        repository.upsertRecurringOverride(override);
    }

    private static void applyRecurringPatch(RecurringTaskGroup group,
                                            Map<String, Object> fields,
                                            boolean creating) throws ExecutionException {
        if (!creating && fields.containsKey("title")) {
            group.setTitle(requiredString(value(fields, "title"), "title"));
        }
        if (fields.containsKey("description")) {
            group.setDescription(optionalTextAllowEmpty(fields, "description"));
        }
        long startAt = group.getAnchorStartAt();
        Lookup start = find(fields, "startAt", "start_at");
        if (start.present) startAt = positiveTimestamp(start, "startAt");
        long durationMillis = group.getDurationMillis();
        Lookup duration = find(fields, "durationMinutes", "duration_minutes");
        if (duration.present) {
            durationMillis = positiveInt(duration.value, duration.key) * MILLIS_PER_MINUTE;
        }
        RecurringTaskGroup.Unit unit = fields.containsKey("unit")
                ? recurringUnit(value(fields, "unit")) : group.getUnit();
        int interval = fields.containsKey("interval")
                ? positiveInt(value(fields, "interval"), "interval") : group.getInterval();
        group.setRule(startAt, durationMillis, unit, interval);
        boolean workdays = fields.containsKey("workdaysOnly")
                ? booleanValue(value(fields, "workdaysOnly"), "workdaysOnly")
                : group.isWorkdaysOnly();
        Collection<Integer> weekdays = fields.containsKey("weekdays")
                ? weekdayList(value(fields, "weekdays"), "weekdays") : group.getWeekdays();
        int ordinal = fields.containsKey("monthlyOrdinal")
                ? nonNegativeInt(value(fields, "monthlyOrdinal"), "monthlyOrdinal")
                : group.getMonthlyOrdinal();
        int monthlyWeekday = fields.containsKey("monthlyWeekday")
                ? nonNegativeInt(value(fields, "monthlyWeekday"), "monthlyWeekday")
                : group.getMonthlyWeekday();
        long endsAt = fields.containsKey("endsAt")
                ? nullableTimestamp(value(fields, "endsAt"), "endsAt") : group.getEndsAt();
        int maxOccurrences = fields.containsKey("maxOccurrences")
                ? nonNegativeInt(value(fields, "maxOccurrences"), "maxOccurrences")
                : group.getMaxOccurrences();
        try {
            group.setAdvancedRule(workdays, weekdays, ordinal, monthlyWeekday,
                    endsAt, maxOccurrences);
        } catch (IllegalArgumentException error) {
            throw new ExecutionException("周期规则无效：" + error.getMessage(), error);
        }
    }

    private static void createTask(RepositoryAccess repository, Map<String, Object> fields,
                                   LinkedHashMap<String, TaskItem> affected)
            throws ExecutionException {
        String title = requiredString(value(fields, "title"), "title");
        TaskItem task = new TaskItem(title).setSource(TaskItem.SOURCE_CHAT);
        applyPatch(repository, task, withoutField(fields, "subtasks"), true);
        if (task.requiresTimeArrangement() && !task.isScheduled()) {
            throw new ExecutionException("AI 创建日程需要完整的开始和结束时间；信息不足时应先澄清");
        }
        List<TaskItem> children = buildSubtasks(repository, task, optionalSubtasks(fields));
        for (TaskItem child : children) task.addChildTaskId(child.getId());
        TaskItem stored = repository.upsertTask(task);
        affected.put(stored.getId(), stored);
        for (TaskItem child : children) {
            TaskItem storedChild = repository.upsertTask(child);
            affected.put(storedChild.getId(), storedChild);
        }
    }

    private static void updateTask(RepositoryAccess repository, Map<String, Object> fields,
                                   LinkedHashMap<String, TaskItem> affected)
            throws ExecutionException {
        String taskId = taskId(fields);
        TaskItem task = requireTask(repository, taskId);
        Map<String, Object> changes = requiredMap(value(fields, "changes"), "changes");
        applyPatch(repository, task, changes, false);
        TaskItem stored = repository.upsertTask(task);
        affected.put(stored.getId(), stored);
    }

    private static void deleteTask(RepositoryAccess repository, Map<String, Object> fields,
                                   LinkedHashMap<String, TaskItem> affected)
            throws ExecutionException {
        String taskId = taskId(fields);
        TaskItem before = requireTask(repository, taskId);
        affected.put(before.getId(), before);
        for (TaskItem candidate : repository.getAllTasks()) {
            if (taskId.equals(candidate.getParentTaskId())
                    || candidate.getChildTaskIds().contains(taskId)) {
                affected.put(candidate.getId(), candidate);
            }
        }
        if (!repository.deleteTask(taskId)) throw new ExecutionException("任务不存在：" + taskId);
        refreshAffected(repository, affected);
    }

    private static void setStatus(RepositoryAccess repository, Map<String, Object> fields,
                                  LinkedHashMap<String, TaskItem> affected,
                                  TaskItem.Status status, boolean clearFocus)
            throws ExecutionException {
        String taskId = taskId(fields);
        requireTask(repository, taskId);
        TaskItem stored = repository.updateTaskStatus(taskId, status);
        affected.put(stored.getId(), stored);
        if (clearFocus && taskId.equals(repository.getCurrentFocus().getTaskId())) {
            repository.clearCurrentFocus();
        }
    }

    private static void completeTask(RepositoryAccess repository, Map<String, Object> fields,
                                     LinkedHashMap<String, TaskItem> affected)
            throws ExecutionException {
        String taskId = taskId(fields);
        TaskItem task = requireTask(repository, taskId);
        Lookup actual = find(fields, "actualDurationMinutes", "actual_minutes");
        if (actual.present) task.setActualDurationMinutes(nonNegativeInt(actual.value, actual.key));
        task.setStatus(TaskItem.Status.COMPLETED);
        TaskItem stored = repository.upsertTask(task);
        affected.put(stored.getId(), stored);
        if (taskId.equals(repository.getCurrentFocus().getTaskId())) repository.clearCurrentFocus();
    }

    private static void scheduleTask(RepositoryAccess repository, Map<String, Object> fields,
                                     LinkedHashMap<String, TaskItem> affected)
            throws ExecutionException {
        String taskId = taskId(fields);
        TaskItem task = requireTask(repository, taskId);
        if (task.getStatus().isTerminal()) {
            throw new ExecutionException("已结束任务不能排程：" + taskId);
        }
        long startAt = positiveTimestamp(find(fields, "startAt", "start_at", "scheduledStartAt"),
                "startAt");
        int duration = positiveInt(find(fields, "durationMinutes", "duration_minutes",
                "estimatedDurationMinutes").value, "durationMinutes");
        long endAt = addDuration(startAt, duration);
        boolean locked = optionalBoolean(fields, task.isLocked(), "locked");
        ensureScheduleAllowed(repository, task, startAt, endAt, locked,
                Collections.emptyList());
        task.setEstimatedDurationMinutes(duration)
                .setRequiresTimeArrangement(true)
                .schedule(startAt, endAt, locked);
        TaskItem stored = repository.upsertTask(task);
        affected.put(stored.getId(), stored);
    }

    private static void splitTask(RepositoryAccess repository, Map<String, Object> fields,
                                  LinkedHashMap<String, TaskItem> affected)
            throws ExecutionException {
        String taskId = taskId(fields);
        TaskItem parent = requireTask(repository, taskId);
        List<?> subtasks = requiredList(value(fields, "subtasks"), "subtasks");
        List<TaskItem> children = buildSubtasks(repository, parent, subtasks);
        for (TaskItem child : children) parent.addChildTaskId(child.getId());
        TaskItem storedParent = repository.upsertTask(parent);
        affected.put(storedParent.getId(), storedParent);
        for (TaskItem child : children) {
            TaskItem storedChild = repository.upsertTask(child);
            affected.put(storedChild.getId(), storedChild);
        }
    }

    private static List<TaskItem> buildSubtasks(RepositoryAccess repository, TaskItem parent,
                                                List<?> subtasks) throws ExecutionException {
        List<TaskItem> children = new ArrayList<>(subtasks.size());
        List<TaskItem> pendingPeers = new ArrayList<>();
        pendingPeers.add(parent);
        for (int index = 0; index < subtasks.size(); index++) {
            Object item = subtasks.get(index);
            Map<String, Object> childFields;
            if (item instanceof String) {
                childFields = new LinkedHashMap<>();
                childFields.put("title", item);
            } else {
                childFields = requiredMap(item, "subtasks[" + index + "]");
            }
            String title = requiredString(value(childFields, "title"),
                    "subtasks[" + index + "].title");
            TaskItem child = new TaskItem(title)
                    .setSource(TaskItem.SOURCE_CHAT)
                    .setParentTaskId(parent.getId())
                    .setPriority(parent.getPriority())
                    .setProject(parent.getProject())
                    .setTags(parent.getTags());
            applyPatch(repository, child, childFields, true, pendingPeers);
            child.setParentTaskId(parent.getId()).setSource(TaskItem.SOURCE_CHAT);
            children.add(child);
            pendingPeers.add(child);
        }
        return children;
    }

    private static List<?> optionalSubtasks(Map<String, Object> fields) throws ExecutionException {
        if (!fields.containsKey("subtasks")) return Collections.emptyList();
        return requiredList(value(fields, "subtasks"), "subtasks");
    }

    private static void prepareFocus(RepositoryAccess repository, Map<String, Object> fields,
                                     LinkedHashMap<String, TaskItem> affected)
            throws ExecutionException {
        String taskId = taskId(fields);
        TaskItem task = requireTask(repository, taskId);
        if (task.getStatus().isTerminal()) {
            throw new ExecutionException("已结束任务不能进入专注：" + taskId);
        }
        Lookup durationField = find(fields, "durationMinutes", "duration_minutes");
        int duration = durationField.present
                ? positiveInt(durationField.value, durationField.key)
                : repository.getFocusDurationMinutes();
        Lookup indexField = find(fields, "pomodoroIndex", "pomodoro_index");
        int pomodoroIndex;
        if (indexField.present) {
            pomodoroIndex = nonNegativeInt(indexField.value, indexField.key);
        } else {
            FocusState current = repository.getCurrentFocus();
            pomodoroIndex = taskId.equals(current.getTaskId())
                    ? Math.max(1, current.getPomodoroIndex() + 1) : 1;
        }
        repository.setCurrentFocus(FocusState.ready(taskId, FocusState.Mode.FOCUS,
                multiplyMinutes(duration), pomodoroIndex));
        affected.put(task.getId(), task);
    }

    private static void applyPatch(RepositoryAccess repository, TaskItem task,
                                   Map<String, Object> rawPatch, boolean creating)
            throws ExecutionException {
        applyPatch(repository, task, rawPatch, creating, Collections.emptyList());
    }

    private static void applyPatch(RepositoryAccess repository, TaskItem task,
                                   Map<String, Object> rawPatch, boolean creating,
                                   Collection<TaskItem> pendingPeers)
            throws ExecutionException {
        Map<String, Object> patch = flattenSchedule(rawPatch);
        rejectUnknownPatchFields(patch);

        Lookup title = find(patch, "title");
        if (title.present) task.setTitle(requiredString(title.value, title.key));

        Lookup description = find(patch, "description");
        if (description.present) task.setDescription(nullableString(description.value,
                description.key));

        Lookup priority = find(patch, "priority");
        if (priority.present) task.setPriority(parsePriority(priority.value));

        Lookup estimated = find(patch, "estimatedDurationMinutes", "estimatedMinutes",
                "estimated_minutes", "durationMinutes", "duration_minutes");
        if (estimated.present) {
            task.setEstimatedDurationMinutes(nonNegativeInt(estimated.value, estimated.key));
        }

        Lookup actual = find(patch, "actualDurationMinutes", "actual_minutes");
        if (actual.present) task.setActualDurationMinutes(nonNegativeInt(actual.value, actual.key));

        Lookup due = find(patch, "dueAt", "due_at", "deadline");
        if (due.present) task.setDueAt(nullableTimestamp(due.value, due.key));

        Lookup project = find(patch, "project");
        if (project.present) task.setProject(nullableString(project.value, project.key));

        Lookup tags = find(patch, "tags");
        if (tags.present) task.setTags(stringList(tags.value, tags.key));

        Lookup requiresTime = find(patch,
                "requiresTimeArrangement", "requires_time_arrangement");
        if (requiresTime.present) {
            task.setRequiresTimeArrangement(booleanValue(requiresTime.value, requiresTime.key));
        } else if (creating) {
            Lookup start = find(patch, "scheduledStartAt", "startAt", "start_at");
            boolean hasExplicitStart = start.present && start.value != null
                    && nullableTimestamp(start.value, start.key) > 0;
            // A conversational "remember this" creates a to-do. Only an explicit time block
            // turns a newly-created item into something that needs schedule placement.
            task.setRequiresTimeArrangement(hasExplicitStart);
        }

        applyCountdownPatch(task, patch);

        applySchedulePatch(repository, task, patch, creating, pendingPeers);

    }

    private static void applyCountdownPatch(TaskItem task, Map<String, Object> patch)
            throws ExecutionException {
        Lookup absolute = find(patch, "reminderAt", "reminder_at");
        Lookup minutes = find(patch, "countdownMinutes", "countdown_minutes");
        Lookup repeat = find(patch, "repeatCountdown", "repeat_countdown");
        if (absolute.present) {
            if (minutes.present || repeat.present && booleanValue(repeat.value, repeat.key))
                throw new ExecutionException("reminderAt 不可与倒计时或循环提醒同时设置");
            long target = nullableTimestamp(absolute.value, absolute.key);
            if (target == 0) { task.clearCountdown(); return; }
            if (target <= System.currentTimeMillis()) throw new ExecutionException("提醒时间已过，请选择新的时间");
            task.setCountdown(target, 0);
            return;
        }
        if (minutes.present) {
            if (minutes.value == null || nonNegativeInt(minutes.value, minutes.key) == 0) {
                if (repeat.present && booleanValue(repeat.value, repeat.key)) {
                    throw new ExecutionException("循环倒计时需要 countdownMinutes");
                }
                task.clearCountdown();
                return;
            }
            int durationMinutes = positiveInt(minutes.value, minutes.key);
            long interval = multiplyMinutes(durationMinutes);
            long target;
            try {
                target = Math.addExact(System.currentTimeMillis(), interval);
            } catch (ArithmeticException overflow) {
                throw new ExecutionException("倒计时时间超出范围", overflow);
            }
            boolean repeating = repeat.present
                    ? booleanValue(repeat.value, repeat.key) : task.isRepeatingCountdown();
            task.setCountdown(target, repeating ? interval : 0);
            return;
        }
        if (!repeat.present) return;
        boolean repeating = booleanValue(repeat.value, repeat.key);
        if (!task.hasCountdown()) {
            if (repeating) throw new ExecutionException("循环倒计时需要 countdownMinutes");
            return;
        }
        long interval = task.getCountdownIntervalMillis();
        if (repeating && interval <= 0) {
            interval = Math.max(MILLIS_PER_MINUTE,
                    task.getCountdownTargetAt() - System.currentTimeMillis());
        }
        task.setCountdown(task.getCountdownTargetAt(), repeating ? interval : 0);
    }

    private static void applySchedulePatch(RepositoryAccess repository, TaskItem task,
                                           Map<String, Object> patch, boolean creating,
                                           Collection<TaskItem> pendingPeers)
            throws ExecutionException {
        Lookup start = find(patch, "scheduledStartAt", "startAt", "start_at");
        Lookup end = find(patch, "scheduledEndAt", "endAt", "end_at");
        Lookup duration = find(patch, "durationMinutes", "duration_minutes");
        Lookup lockedField = find(patch, "locked");

        if (start.present) {
            long startAt = nullableTimestamp(start.value, start.key);
            if (startAt == 0) {
                ensureScheduleCanBeCleared(repository, task);
                task.clearSchedule();
                if (!find(patch, "requiresTimeArrangement",
                        "requires_time_arrangement").present) {
                    task.setRequiresTimeArrangement(false);
                }
                if (lockedField.present && booleanValue(lockedField.value, lockedField.key)) {
                    throw new ExecutionException("未排程任务不能锁定");
                }
                return;
            }

            long endAt;
            if (end.present && end.value != null) {
                endAt = parseTimestamp(end.value);
            } else if (duration.present) {
                endAt = addDuration(startAt, positiveInt(duration.value, duration.key));
            } else if (task.isScheduled()) {
                endAt = Math.addExact(startAt,
                        task.getScheduledEndAt() - task.getScheduledStartAt());
            } else if (task.getEstimatedDurationMinutes() > 0) {
                endAt = addDuration(startAt, task.getEstimatedDurationMinutes());
            } else {
                throw new ExecutionException("排程缺少 endAt 或 durationMinutes");
            }
            if (endAt <= startAt) throw new ExecutionException("排程结束时间必须晚于开始时间");
            if (duration.present) {
                long expected = addDuration(startAt, positiveInt(duration.value, duration.key));
                if (end.present && expected != endAt) {
                    throw new ExecutionException("endAt 与 durationMinutes 不一致");
                }
            }
            boolean locked = lockedField.present
                    ? booleanValue(lockedField.value, lockedField.key) : task.isLocked();
            ensureScheduleAllowed(repository, task, startAt, endAt, locked, pendingPeers);
            task.setRequiresTimeArrangement(true).schedule(startAt, endAt, locked);
            return;
        }

        if ((end.present || duration.present) && task.isScheduled()) {
            long endAt = end.present && end.value != null
                    ? parseTimestamp(end.value)
                    : addDuration(task.getScheduledStartAt(),
                    positiveInt(duration.value, duration.key));
            if (endAt <= task.getScheduledStartAt()) {
                throw new ExecutionException("排程结束时间必须晚于开始时间");
            }
            if (task.isLocked() && endAt != task.getScheduledEndAt()) {
                throw new ExecutionException("锁定任务不能调整排程");
            }
            boolean locked = lockedField.present
                    ? booleanValue(lockedField.value, lockedField.key) : task.isLocked();
            ensureScheduleAllowed(repository, task, task.getScheduledStartAt(), endAt, locked,
                    pendingPeers);
            task.setRequiresTimeArrangement(true)
                    .schedule(task.getScheduledStartAt(), endAt, locked);
            return;
        }
        if ((end.present || duration.present) && !task.isScheduled() && !creating) {
            throw new ExecutionException("修改排程结束时间前需要 startAt");
        }
        if (lockedField.present) {
            boolean locked = booleanValue(lockedField.value, lockedField.key);
            if (task.isLocked() && !locked) throw new ExecutionException("AI 不能解除任务锁定");
            task.setLocked(locked);
        }
    }

    static void ensureScheduleAllowed(RepositoryAccess repository, TaskItem task,
                                      long startAt, long endAt, boolean locked,
                                      Collection<TaskItem> pendingPeers)
            throws ExecutionException {
        if (task.isLocked() && (startAt != task.getScheduledStartAt()
                || endAt != task.getScheduledEndAt() || !locked)) {
            throw new ExecutionException("锁定任务不能调整排程或由 AI 解锁");
        }
        AppSettings settings = requireSettings(repository);
        if (task.isScheduled() && !task.isLocked()
                && !settings.isAllowAiRescheduleUnlockedTasks()
                && (startAt != task.getScheduledStartAt()
                || endAt != task.getScheduledEndAt())) {
            throw new ExecutionException("设置不允许 AI 调整已有未锁定任务的排程");
        }
        validateScheduleConstraints(repository, task, startAt, endAt, settings, pendingPeers);
    }

    private static void ensureScheduleCanBeCleared(RepositoryAccess repository, TaskItem task)
            throws ExecutionException {
        if (task.isLocked()) throw new ExecutionException("锁定任务不能清除排程");
        if (task.isScheduled()
                && !requireSettings(repository).isAllowAiRescheduleUnlockedTasks()) {
            throw new ExecutionException("设置不允许 AI 清除已有未锁定任务的排程");
        }
    }

    private static void validateScheduleConstraints(RepositoryAccess repository, TaskItem task,
                                                    long startAt, long endAt,
                                                    AppSettings settings,
                                                    Collection<TaskItem> pendingPeers)
            throws ExecutionException {
        if (startAt <= 0 || endAt <= startAt) {
            throw new ExecutionException("排程结束时间必须晚于开始时间");
        }
        TimeZone timeZone = TimeZone.getDefault();
        if (!isWithinDailyWindow(startAt, endAt, settings.getAvailableStartMinute(),
                settings.getAvailableEndMinute(), timeZone)) {
            throw new ExecutionException("排程超出设置的每日可用时间窗口");
        }
        for (AppSettings.TimeRange blocked : settings.getBlockedTimeRanges()) {
            if (overlapsDailyRange(startAt, endAt, blocked.getStartMinute(),
                    blocked.getEndMinute(), timeZone)) {
                String label = blocked.getLabel();
                throw new ExecutionException(label.isEmpty()
                        ? "排程与每日阻塞时间重叠"
                        : "排程与每日阻塞时间重叠：" + label);
            }
        }
        ensureNoTaskOverlap(task, startAt, endAt, repository.getAllTasks());
        ensureNoTaskOverlap(task, startAt, endAt, pendingPeers);
    }

    private static AppSettings requireSettings(RepositoryAccess repository)
            throws ExecutionException {
        AppSettings settings = repository.getSettings();
        if (settings == null) throw new ExecutionException("无法读取排程设置");
        return settings;
    }

    private static boolean isWithinDailyWindow(long startAt, long endAt, int windowStartMinute,
                                               int windowEndMinute, TimeZone timeZone) {
        if (windowStartMinute == 0 && windowEndMinute == AppSettings.MINUTES_PER_DAY) {
            return true;
        }
        for (int dayOffset = -1; dayOffset <= 0; dayOffset++) {
            long windowStart = localBoundary(startAt, dayOffset, windowStartMinute, timeZone);
            int endDayOffset = windowStartMinute < windowEndMinute
                    ? dayOffset : dayOffset + 1;
            long windowEnd = localBoundary(startAt, endDayOffset, windowEndMinute, timeZone);
            if (startAt >= windowStart && endAt <= windowEnd) return true;
        }
        return false;
    }

    private static boolean overlapsDailyRange(long startAt, long endAt, int rangeStartMinute,
                                              int rangeEndMinute, TimeZone timeZone) {
        for (int dayOffset = -1; dayOffset <= 1; dayOffset++) {
            long blockedStart = localBoundary(startAt, dayOffset, rangeStartMinute, timeZone);
            int endDayOffset = rangeStartMinute < rangeEndMinute
                    ? dayOffset : dayOffset + 1;
            long blockedEnd = localBoundary(startAt, endDayOffset, rangeEndMinute, timeZone);
            if (overlaps(startAt, endAt, blockedStart, blockedEnd)) return true;
        }
        return false;
    }

    private static long localBoundary(long reference, int dayOffset, int minuteOfDay,
                                      TimeZone timeZone) {
        Calendar calendar = Calendar.getInstance(timeZone);
        calendar.setTimeInMillis(reference);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.DAY_OF_MONTH, dayOffset);
        if (minuteOfDay == AppSettings.MINUTES_PER_DAY) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        } else {
            calendar.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
            calendar.set(Calendar.MINUTE, minuteOfDay % 60);
        }
        return calendar.getTimeInMillis();
    }

    private static void ensureNoTaskOverlap(TaskItem task, long startAt, long endAt,
                                            Collection<TaskItem> candidates)
            throws ExecutionException {
        if (candidates == null) return;
        for (TaskItem other : candidates) {
            if (other == null || task.getId().equals(other.getId())
                    || other.getStatus().isTerminal() || !other.isScheduled()) {
                continue;
            }
            long otherStart = other.getScheduledStartAt();
            long otherEnd = other.getScheduledEndAt();
            if (otherEnd <= otherStart && other.getEstimatedDurationMinutes() > 0) {
                try {
                    otherEnd = addDuration(otherStart,
                            other.getEstimatedDurationMinutes());
                } catch (ExecutionException ignored) {
                    otherEnd = Long.MAX_VALUE;
                }
            }
            boolean conflict = otherEnd > otherStart
                    ? overlaps(startAt, endAt, otherStart, otherEnd)
                    : startAt <= otherStart && endAt > otherStart;
            if (conflict) {
                throw new ExecutionException("排程与任务“" + other.getTitle() + "”重叠");
            }
        }
    }

    private static boolean overlaps(long firstStart, long firstEnd,
                                    long secondStart, long secondEnd) {
        return firstStart < secondEnd && firstEnd > secondStart;
    }

    private static Map<String, Object> flattenSchedule(Map<String, Object> raw)
            throws ExecutionException {
        Map<String, Object> flattened = new LinkedHashMap<>(raw);
        if (raw.containsKey("schedule")) {
            Object nested = raw.get("schedule");
            if (nested == null) {
                flattened.put("startAt", null);
            } else {
                Map<String, Object> schedule = requiredMap(nested, "schedule");
                for (Map.Entry<String, Object> entry : schedule.entrySet()) {
                    flattened.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            flattened.remove("schedule");
        }
        return flattened;
    }

    private static void validateActionFields(AiPlan.Action action) throws ExecutionException {
        Map<String, Object> fields = action.fields;
        switch (action.type) {
            case CREATE_TASK:
                rejectUnknown(fields, union(PATCH_KEYS, "type", "reason", "summary", "subtasks"));
                requiredString(value(fields, "title"), "title");
                validatePatchShape(withoutField(fields, "subtasks"));
                if (fields.containsKey("subtasks")) {
                    validateSubtasks(value(fields, "subtasks"), "subtasks");
                }
                break;
            case CREATE_RECURRING_GROUP:
                rejectUnknown(fields, union(RECURRING_PATCH_KEYS,
                        "type", "reason", "summary"));
                requiredString(value(fields, "title"), "title");
                positiveTimestamp(find(fields, "startAt", "start_at"), "startAt");
                Lookup createDuration = find(fields, "durationMinutes", "duration_minutes");
                if (!createDuration.present) throw new ExecutionException("缺少 durationMinutes");
                positiveInt(createDuration.value, createDuration.key);
                validateRecurringPatch(fields);
                break;
            case UPDATE_RECURRING_GROUP:
                rejectUnknown(fields, setOf("type", "groupId", "changes", "reason", "summary"));
                requiredString(value(fields, "groupId"), "groupId");
                Map<String, Object> recurringChanges = requiredMap(value(fields, "changes"),
                        "changes");
                if (recurringChanges.isEmpty()) throw new ExecutionException("changes 不能为空");
                rejectUnknown(recurringChanges, RECURRING_PATCH_KEYS);
                validateRecurringPatch(recurringChanges);
                break;
            case STOP_RECURRING_GROUP:
                rejectUnknown(fields, setOf("type", "groupId", "reason", "summary"));
                requiredString(value(fields, "groupId"), "groupId");
                break;
            case SET_RECURRING_OVERRIDE:
                rejectUnknown(fields, setOf("type", "groupId", "occurrenceAt", "occurrence_at",
                        "operation", "title", "description", "startAt", "start_at",
                        "endAt", "end_at", "reason", "summary"));
                requiredString(value(fields, "groupId"), "groupId");
                positiveTimestamp(find(fields, "occurrenceAt", "occurrence_at"), "occurrenceAt");
                requiredString(value(fields, "operation"), "operation");
                Lookup overrideStart = find(fields, "startAt", "start_at");
                Lookup overrideEnd = find(fields, "endAt", "end_at");
                if (overrideStart.present) positiveTimestamp(overrideStart, "startAt");
                if (overrideEnd.present) positiveTimestamp(overrideEnd, "endAt");
                break;
            case UPDATE_TASK:
                rejectUnknown(fields, setOf("type", "taskId", "task_id", "id", "changes",
                        "reason", "summary"));
                taskId(fields);
                Map<String, Object> changes = requiredMap(value(fields, "changes"), "changes");
                if (changes.isEmpty()) throw new ExecutionException("changes 不能为空");
                validatePatchShape(changes);
                break;
            case DELETE_TASK:
            case CANCEL_TASK:
                rejectUnknown(fields, setOf("type", "taskId", "task_id", "id", "reason",
                        "summary"));
                taskId(fields);
                break;
            case COMPLETE_TASK:
                rejectUnknown(fields, setOf("type", "taskId", "task_id", "id", "reason",
                        "summary", "actualDurationMinutes", "actual_minutes"));
                taskId(fields);
                Lookup actual = find(fields, "actualDurationMinutes", "actual_minutes");
                if (actual.present) nonNegativeInt(actual.value, actual.key);
                break;
            case SCHEDULE_TASK:
                rejectUnknown(fields, setOf("type", "taskId", "task_id", "id", "startAt",
                        "start_at", "scheduledStartAt", "durationMinutes", "duration_minutes",
                        "estimatedDurationMinutes", "locked", "reason", "summary"));
                taskId(fields);
                positiveTimestamp(find(fields, "startAt", "start_at", "scheduledStartAt"),
                        "startAt");
                Lookup scheduleDuration = find(fields, "durationMinutes", "duration_minutes",
                        "estimatedDurationMinutes");
                if (!scheduleDuration.present) throw new ExecutionException("缺少 durationMinutes");
                positiveInt(scheduleDuration.value, scheduleDuration.key);
                Lookup locked = find(fields, "locked");
                if (locked.present) booleanValue(locked.value, locked.key);
                break;
            case SPLIT_TASK:
                rejectUnknown(fields, setOf("type", "taskId", "task_id", "id", "subtasks",
                        "reason", "summary"));
                taskId(fields);
                List<?> subtasks = requiredList(value(fields, "subtasks"), "subtasks");
                validateSubtasks(subtasks, "subtasks");
                break;
            case START_FOCUS:
                rejectUnknown(fields, setOf("type", "taskId", "task_id", "id",
                        "durationMinutes", "duration_minutes", "pomodoroIndex", "pomodoro_index",
                        "reason", "summary"));
                taskId(fields);
                Lookup focusDuration = find(fields, "durationMinutes", "duration_minutes");
                if (focusDuration.present) positiveInt(focusDuration.value, focusDuration.key);
                Lookup pomodoro = find(fields, "pomodoroIndex", "pomodoro_index");
                if (pomodoro.present) nonNegativeInt(pomodoro.value, pomodoro.key);
                break;
            case SET_GREETING:
                rejectUnknown(fields, setOf("type", "text", "reason", "summary"));
                String greeting = requiredString(value(fields, "text"), "text");
                if (greeting.length() > AppSettings.MAX_GREETING_TEXT_LENGTH) {
                    throw new ExecutionException("text 不能超过 "
                            + AppSettings.MAX_GREETING_TEXT_LENGTH + " 个字符");
                }
                break;
            default:
                throw new ExecutionException("未知 action：" + action.type);
        }
    }

    private static void validatePatchShape(Map<String, Object> rawPatch) throws ExecutionException {
        Map<String, Object> patch = flattenSchedule(rawPatch);
        rejectUnknownPatchFields(patch);
        Lookup title = find(patch, "title");
        if (title.present) requiredString(title.value, title.key);
        Lookup description = find(patch, "description");
        if (description.present) nullableString(description.value, description.key);
        Lookup priority = find(patch, "priority");
        if (priority.present) parsePriority(priority.value);
        Lookup estimated = find(patch, "estimatedDurationMinutes", "estimatedMinutes",
                "estimated_minutes", "durationMinutes", "duration_minutes");
        if (estimated.present) nonNegativeInt(estimated.value, estimated.key);
        Lookup actual = find(patch, "actualDurationMinutes", "actual_minutes");
        if (actual.present) nonNegativeInt(actual.value, actual.key);
        Lookup due = find(patch, "dueAt", "due_at", "deadline");
        if (due.present) nullableTimestamp(due.value, due.key);
        Lookup project = find(patch, "project");
        if (project.present) nullableString(project.value, project.key);
        Lookup tags = find(patch, "tags");
        if (tags.present) stringList(tags.value, tags.key);
        Lookup requiresTime = find(patch,
                "requiresTimeArrangement", "requires_time_arrangement");
        if (requiresTime.present) booleanValue(requiresTime.value, requiresTime.key);
        Lookup countdown = find(patch, "countdownMinutes", "countdown_minutes");
        Lookup reminder = find(patch, "reminderAt", "reminder_at");
        if (reminder.present) {
            nullableTimestamp(reminder.value, reminder.key);
            if (countdown.present) throw new ExecutionException("reminderAt 不可与 countdownMinutes 同时设置");
        }
        if (countdown.present && countdown.value != null) {
            nonNegativeInt(countdown.value, countdown.key);
        }
        Lookup repeat = find(patch, "repeatCountdown", "repeat_countdown");
        if (repeat.present) booleanValue(repeat.value, repeat.key);
        if (reminder.present && repeat.present && booleanValue(repeat.value, repeat.key))
            throw new ExecutionException("绝对提醒时间不支持循环");
        if (countdown.present && countdown.value != null
                && nonNegativeInt(countdown.value, countdown.key) == 0
                && repeat.present && booleanValue(repeat.value, repeat.key)) {
            throw new ExecutionException("循环倒计时需要 countdownMinutes");
        }
        Lookup start = find(patch, "scheduledStartAt", "startAt", "start_at");
        Lookup end = find(patch, "scheduledEndAt", "endAt", "end_at");
        Lookup duration = find(patch, "durationMinutes", "duration_minutes");
        long startAt = start.present ? nullableTimestamp(start.value, start.key) : 0;
        long endAt = end.present && end.value != null ? parseTimestamp(end.value) : 0;
        if (startAt > 0 && endAt > 0 && endAt <= startAt) {
            throw new ExecutionException("排程结束时间必须晚于开始时间");
        }
        if (duration.present) {
            int minutes = positiveInt(duration.value, duration.key);
            if (startAt > 0 && endAt > 0 && addDuration(startAt, minutes) != endAt) {
                throw new ExecutionException("endAt 与 durationMinutes 不一致");
            }
        }
        Lookup locked = find(patch, "locked");
        if (locked.present) booleanValue(locked.value, locked.key);
    }

    private static void validateRecurringPatch(Map<String, Object> fields)
            throws ExecutionException {
        if (fields.containsKey("title")) requiredString(value(fields, "title"), "title");
        if (fields.containsKey("description")) nullableString(value(fields, "description"),
                "description");
        Lookup start = find(fields, "startAt", "start_at");
        if (start.present) positiveTimestamp(start, "startAt");
        Lookup duration = find(fields, "durationMinutes", "duration_minutes");
        if (duration.present) positiveInt(duration.value, duration.key);
        if (fields.containsKey("unit")) recurringUnit(value(fields, "unit"));
        if (fields.containsKey("interval")) positiveInt(value(fields, "interval"), "interval");
        if (fields.containsKey("workdaysOnly")) {
            booleanValue(value(fields, "workdaysOnly"), "workdaysOnly");
        }
        if (fields.containsKey("weekdays")) weekdayList(value(fields, "weekdays"), "weekdays");
        if (fields.containsKey("monthlyOrdinal")) {
            int ordinal = nonNegativeInt(value(fields, "monthlyOrdinal"), "monthlyOrdinal");
            if (ordinal > 5) throw new ExecutionException("monthlyOrdinal 必须是 0..5");
        }
        if (fields.containsKey("monthlyWeekday")) {
            int weekday = nonNegativeInt(value(fields, "monthlyWeekday"), "monthlyWeekday");
            if (weekday > 7) throw new ExecutionException("monthlyWeekday 必须是 0..7");
        }
        if (fields.containsKey("endsAt")) nullableTimestamp(value(fields, "endsAt"), "endsAt");
        if (fields.containsKey("maxOccurrences")) {
            nonNegativeInt(value(fields, "maxOccurrences"), "maxOccurrences");
        }
    }

    private static void validateSubtasks(Object rawSubtasks, String field)
            throws ExecutionException {
        List<?> subtasks = requiredList(rawSubtasks, field);
        if (subtasks.isEmpty()) throw new ExecutionException(field + " 不能为空");
        for (int index = 0; index < subtasks.size(); index++) {
            Object item = subtasks.get(index);
            if (item instanceof String) {
                requiredString(item, field + "[" + index + "]");
            } else {
                Map<String, Object> child = requiredMap(item, field + "[" + index + "]");
                requiredString(value(child, "title"), field + "[" + index + "].title");
                validatePatchShape(child);
            }
        }
    }

    private static String describeAction(AiPlan.Action action) throws ExecutionException {
        Map<String, Object> fields = action.fields;
        switch (action.type) {
            case CREATE_TASK:
                String create = "创建任务“" + requiredString(value(fields, "title"), "title") + "”";
                Lookup countdown = find(fields, "countdownMinutes", "countdown_minutes");
                if (countdown.present && countdown.value != null
                        && nonNegativeInt(countdown.value, countdown.key) > 0) {
                    int minutes = positiveInt(countdown.value, countdown.key);
                    boolean repeating = optionalBoolean(fields, false,
                            "repeatCountdown", "repeat_countdown");
                    create += repeating ? "，每 " + minutes + " 分钟循环提醒"
                            : "，确认执行后 " + minutes + " 分钟提醒";
                }
                Lookup reminder = find(fields, "reminderAt", "reminder_at");
                if (reminder.present && reminder.value != null)
                    create += "，提醒 " + formatTimestamp(nullableTimestamp(reminder.value, reminder.key));
                if (fields.containsKey("subtasks")) {
                    create += "并拆分为 "
                            + requiredList(value(fields, "subtasks"), "subtasks").size()
                            + " 个子任务";
                }
                return create;
            case UPDATE_TASK:
                Map<String, Object> changes = requiredMap(value(fields, "changes"), "changes");
                return "修改任务 " + taskId(fields) + "（" + joinKeys(changes.keySet()) + "）";
            case DELETE_TASK:
                return "删除任务 " + taskId(fields);
            case CANCEL_TASK:
                return "取消任务 " + taskId(fields);
            case SCHEDULE_TASK:
                long startAt = positiveTimestamp(find(fields, "startAt", "start_at",
                        "scheduledStartAt"), "startAt");
                Lookup duration = find(fields, "durationMinutes", "duration_minutes",
                        "estimatedDurationMinutes");
                return "安排任务 " + taskId(fields) + "：" + formatTimestamp(startAt)
                        + "，" + positiveInt(duration.value, duration.key) + " 分钟";
            case COMPLETE_TASK:
                return "标记任务 " + taskId(fields) + " 为已完成";
            case SPLIT_TASK:
                return "拆分任务 " + taskId(fields) + " 为 "
                        + requiredList(value(fields, "subtasks"), "subtasks").size() + " 个子任务";
            case START_FOCUS:
                Lookup focusDuration = find(fields, "durationMinutes", "duration_minutes");
                return focusDuration.present
                        ? "为任务 " + taskId(fields) + " 准备 "
                        + positiveInt(focusDuration.value, focusDuration.key) + " 分钟专注"
                        : "按默认时长为任务 " + taskId(fields) + " 准备专注";
            case SET_GREETING:
                return "把欢迎问候改为“" + requiredString(value(fields, "text"), "text") + "”";
            case CREATE_RECURRING_GROUP:
                return "创建周期日程“" + requiredString(value(fields, "title"), "title")
                        + "”并从 " + formatTimestamp(positiveTimestamp(
                        find(fields, "startAt", "start_at"), "startAt")) + " 开始";
            case UPDATE_RECURRING_GROUP:
                return "修改周期组 " + requiredString(value(fields, "groupId"), "groupId")
                        + "（" + joinKeys(requiredMap(value(fields, "changes"), "changes").keySet())
                        + "）";
            case STOP_RECURRING_GROUP:
                return "结束周期组 " + requiredString(value(fields, "groupId"), "groupId")
                        + "，保留历史任务";
            case SET_RECURRING_OVERRIDE:
                return "设置周期组 " + requiredString(value(fields, "groupId"), "groupId")
                        + " 的单次例外：" + requiredString(value(fields, "operation"), "operation");
            default:
                throw new ExecutionException("未知 action");
        }
    }

    public static long parseTimestamp(Object value) throws ExecutionException {
        if (value instanceof Number) {
            BigDecimal number;
            try {
                if (value instanceof BigDecimal) {
                    number = (BigDecimal) value;
                } else {
                    // Boxed primitive numbers are common when callers build plans directly.
                    number = new BigDecimal(value.toString());
                }
                long result = number.longValueExact();
                if (result < 0) throw new ExecutionException("时间戳不能为负数");
                return result;
            } catch (ArithmeticException | NumberFormatException error) {
                throw new ExecutionException("时间戳必须是 epoch milliseconds 整数", error);
            }
        }
        if (!(value instanceof String)) {
            throw new ExecutionException("时间必须是 epoch milliseconds 或 ISO-8601 字符串");
        }
        String raw = ((String) value).trim();
        if (raw.isEmpty()) throw new ExecutionException("时间不能为空");
        if (raw.matches("[0-9]+")) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException error) {
                throw new ExecutionException("epoch milliseconds 超出范围", error);
            }
        }
        String normalized = normalizeFraction(raw);
        if (!normalized.matches(".*(?:Z|[+-][0-9]{2}:[0-9]{2})$")) {
            throw new ExecutionException(
                    "时间字符串必须包含时区，例如 2026-08-12T17:30:00+08:00；也可以使用 epoch milliseconds");
        }
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mmXXX"
        };
        for (String pattern : patterns) {
            SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
            parser.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            Date parsed = parser.parse(normalized, position);
            if (parsed != null && position.getIndex() == normalized.length()) {
                return parsed.getTime();
            }
        }
        throw new ExecutionException(
                "无法解析带时区的 ISO-8601 时间：" + raw
                        + "；示例：2026-08-12T17:30:00+08:00");
    }

    public static int positiveInt(Object value, String field) throws ExecutionException {
        int result = integer(value, field);
        if (result <= 0) throw new ExecutionException(field + " 必须是正整数");
        return result;
    }

    static int nonNegativeInt(Object value, String field) throws ExecutionException {
        int result = integer(value, field);
        if (result < 0) throw new ExecutionException(field + " 不能为负数");
        return result;
    }

    static int parsePriority(Object value) throws ExecutionException {
        if (value instanceof String) {
            String normalized = ((String) value).trim().toLowerCase(Locale.ROOT);
            switch (normalized) {
                case "low": case "低": return TaskItem.PRIORITY_LOW;
                case "normal": case "medium": case "普通": case "中":
                    return TaskItem.PRIORITY_NORMAL;
                case "high": case "高": return TaskItem.PRIORITY_HIGH;
                case "urgent": case "critical": case "紧急": return TaskItem.PRIORITY_URGENT;
                default:
                    if (!normalized.matches("-?[0-9]+")) {
                        throw new ExecutionException("priority 无效：" + value);
                    }
            }
        }
        int priority = integer(value, "priority");
        if (priority < TaskItem.PRIORITY_LOW || priority > TaskItem.PRIORITY_URGENT) {
            throw new ExecutionException("priority 必须在 0 到 3 之间");
        }
        return priority;
    }

    static List<String> stringList(Object value, String field) throws ExecutionException {
        if (value == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        if (value instanceof String) {
            for (String part : ((String) value).split("[,，]")) {
                String item = part.trim();
                if (!item.isEmpty() && !result.contains(item)) result.add(item);
            }
            return result;
        }
        if (!(value instanceof List)) throw new ExecutionException(field + " 必须是字符串数组");
        for (Object item : (List<?>) value) {
            String text = requiredString(item, field + "[]");
            if (!result.contains(text)) result.add(text);
        }
        return result;
    }

    private static Collection<Integer> weekdayList(Object value, String field)
            throws ExecutionException {
        if (!(value instanceof List)) throw new ExecutionException(field + " 必须是数字数组");
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (Object item : (List<?>) value) {
            int weekday = integer(item, field + "[]");
            if (weekday < Calendar.SUNDAY || weekday > Calendar.SATURDAY) {
                throw new ExecutionException(field + " 使用 Calendar 的 1..7 星期值");
            }
            result.add(weekday);
        }
        return result;
    }

    private static RecurringTaskGroup.Unit recurringUnit(Object value)
            throws ExecutionException {
        String raw = requiredString(value, "unit").toLowerCase(Locale.ROOT);
        if ("days".equals(raw) || "day".equals(raw)) return RecurringTaskGroup.Unit.DAYS;
        if ("weeks".equals(raw) || "week".equals(raw)) return RecurringTaskGroup.Unit.WEEKS;
        if ("months".equals(raw) || "month".equals(raw)) return RecurringTaskGroup.Unit.MONTHS;
        throw new ExecutionException("unit 仅支持 days、weeks、months");
    }

    private static int optionalPositiveInt(Map<String, Object> fields, int fallback,
                                           String name) throws ExecutionException {
        return fields.containsKey(name) ? positiveInt(value(fields, name), name) : fallback;
    }

    private static String optionalText(Map<String, Object> fields, String name)
            throws ExecutionException {
        if (!fields.containsKey(name) || value(fields, name) == null) return null;
        return requiredString(value(fields, name), name);
    }

    private static String optionalTextAllowEmpty(Map<String, Object> fields, String name)
            throws ExecutionException {
        if (!fields.containsKey(name) || value(fields, name) == null) return null;
        return nullableString(value(fields, name), name);
    }

    private static int integer(Object value, String field) throws ExecutionException {
        BigDecimal number;
        if (value instanceof Number) {
            try {
                if (value instanceof BigDecimal) {
                    number = (BigDecimal) value;
                } else {
                    // Keep primitive JSON/UI numbers on the same strict integer path.
                    number = new BigDecimal(value.toString());
                }
            } catch (NumberFormatException error) {
                throw new ExecutionException(field + " 必须是整数", error);
            }
        } else if (value instanceof String) {
            try {
                number = new BigDecimal(((String) value).trim());
            } catch (NumberFormatException error) {
                throw new ExecutionException(field + " 必须是整数", error);
            }
        } else {
            throw new ExecutionException(field + " 必须是整数");
        }
        try {
            return number.intValueExact();
        } catch (ArithmeticException error) {
            throw new ExecutionException(field + " 必须是有效整数", error);
        }
    }

    private static long positiveTimestamp(Lookup lookup, String field) throws ExecutionException {
        if (!lookup.present) throw new ExecutionException("缺少 " + field);
        long timestamp = parseTimestamp(lookup.value);
        if (timestamp <= 0) throw new ExecutionException(field + " 必须大于零");
        return timestamp;
    }

    private static long nullableTimestamp(Object value, String field) throws ExecutionException {
        if (value == null) return 0;
        long timestamp = parseTimestamp(value);
        if (timestamp < 0) throw new ExecutionException(field + " 不能为负数");
        return timestamp;
    }

    private static boolean optionalBoolean(Map<String, Object> fields, boolean fallback,
                                           String... names) throws ExecutionException {
        Lookup lookup = find(fields, names);
        return lookup.present ? booleanValue(lookup.value, lookup.key) : fallback;
    }

    private static boolean booleanValue(Object value, String field) throws ExecutionException {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) {
            if ("true".equalsIgnoreCase(((String) value).trim())) return true;
            if ("false".equalsIgnoreCase(((String) value).trim())) return false;
        }
        throw new ExecutionException(field + " 必须是布尔值");
    }

    private static String taskId(Map<String, Object> fields) throws ExecutionException {
        Lookup lookup = find(fields, "taskId", "task_id", "id");
        if (!lookup.present) throw new ExecutionException("缺少 taskId");
        return requiredString(lookup.value, lookup.key);
    }

    private static TaskItem requireTask(RepositoryAccess repository, String id)
            throws ExecutionException {
        TaskItem task = repository.getTask(id);
        if (task == null) throw new ExecutionException("任务不存在：" + id);
        return task;
    }

    private static String requiredString(Object value, String field) throws ExecutionException {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new ExecutionException(field + " 必须是非空字符串");
        }
        return ((String) value).trim();
    }

    private static String nullableString(Object value, String field) throws ExecutionException {
        if (value == null) return null;
        if (!(value instanceof String)) throw new ExecutionException(field + " 必须是字符串或 null");
        return (String) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredMap(Object value, String field)
            throws ExecutionException {
        if (!(value instanceof Map)) throw new ExecutionException(field + " 必须是对象");
        return (Map<String, Object>) value;
    }

    private static List<?> requiredList(Object value, String field) throws ExecutionException {
        if (!(value instanceof List)) throw new ExecutionException(field + " 必须是数组");
        return (List<?>) value;
    }

    private static Object value(Map<String, Object> fields, String key) {
        return fields.get(key);
    }

    private static Map<String, Object> withoutField(Map<String, Object> fields, String key) {
        if (!fields.containsKey(key)) return fields;
        Map<String, Object> copy = new LinkedHashMap<>(fields);
        copy.remove(key);
        return copy;
    }

    private static Lookup find(Map<String, Object> fields, String... keys) {
        for (String key : keys) {
            if (fields.containsKey(key)) return new Lookup(true, key, fields.get(key));
        }
        return Lookup.ABSENT;
    }

    private static void rejectUnknownPatchFields(Map<String, Object> fields)
            throws ExecutionException {
        rejectUnknown(fields, PATCH_KEYS);
    }

    private static void rejectUnknown(Map<String, Object> fields, Collection<String> allowed)
            throws ExecutionException {
        for (String key : fields.keySet()) {
            if (!allowed.contains(key)) throw new ExecutionException("不支持的字段：" + key);
        }
    }

    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private static Set<String> union(Collection<String> base, String... values) {
        Set<String> result = new LinkedHashSet<>(base);
        result.addAll(Arrays.asList(values));
        return result;
    }

    private static long addDuration(long startAt, int minutes) throws ExecutionException {
        try {
            return Math.addExact(startAt, multiplyMinutes(minutes));
        } catch (ArithmeticException error) {
            throw new ExecutionException("排程时间超出范围", error);
        }
    }

    private static long multiplyMinutes(int minutes) throws ExecutionException {
        try {
            return Math.multiplyExact((long) minutes, MILLIS_PER_MINUTE);
        } catch (ArithmeticException error) {
            throw new ExecutionException("时长超出范围", error);
        }
    }

    private static String normalizeFraction(String value) {
        int separator = Math.max(value.indexOf('T'), value.indexOf(' '));
        int dot = value.indexOf('.', separator < 0 ? 0 : separator);
        if (dot < 0) return value;
        int end = dot + 1;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        int count = end - dot - 1;
        if (count == 0) return value;
        String fraction = value.substring(dot + 1, end);
        if (fraction.length() > 3) fraction = fraction.substring(0, 3);
        while (fraction.length() < 3) fraction += "0";
        return value.substring(0, dot + 1) + fraction + value.substring(end);
    }

    private static String formatTimestamp(long timestamp) {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        return formatter.format(new Date(timestamp));
    }

    private static String joinKeys(Collection<String> keys) {
        StringBuilder result = new StringBuilder();
        for (String key : keys) {
            if (result.length() > 0) result.append("、");
            result.append(key);
        }
        return result.toString();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static void refreshAffected(RepositoryAccess repository,
                                        LinkedHashMap<String, TaskItem> affected) {
        List<String> ids = new ArrayList<>(affected.keySet());
        for (String id : ids) {
            TaskItem current = repository.getTask(id);
            if (current != null) affected.put(id, current);
        }
    }

    interface RepositoryAccess {
        String createSnapshot();
        void restoreSnapshot(String snapshot);
        TaskItem upsertTask(TaskItem task);
        TaskItem getTask(String id);
        List<TaskItem> getAllTasks();
        AppSettings getSettings();
        default void saveSettings(AppSettings settings) {
            throw new UnsupportedOperationException("settings mutation is not supported");
        }
        boolean deleteTask(String id);
        TaskItem updateTaskStatus(String id, TaskItem.Status status);
        FocusState getCurrentFocus();
        void setCurrentFocus(FocusState focusState);
        void clearCurrentFocus();
        int getFocusDurationMinutes();
        default RecurringTaskGroup upsertRecurringGroup(RecurringTaskGroup group) {
            throw new UnsupportedOperationException("recurring groups are not supported");
        }
        default RecurringTaskGroup getRecurringGroup(String id) { return null; }
        default RecurringOccurrenceOverride upsertRecurringOverride(
                RecurringOccurrenceOverride override) {
            throw new UnsupportedOperationException("recurring overrides are not supported");
        }
        default boolean deleteRecurringOverride(String id) { return false; }
    }

    private static final class AppRepositoryAccess implements RepositoryAccess {
        private final AppRepository repository;

        AppRepositoryAccess(AppRepository repository) {
            this.repository = repository;
        }

        @Override
        public String createSnapshot() {
            return repository.exportJson(false);
        }

        @Override
        public void restoreSnapshot(String snapshot) {
            repository.importJson(snapshot, AppRepository.ImportMode.REPLACE);
        }

        @Override
        public TaskItem upsertTask(TaskItem task) {
            return repository.upsertTask(task);
        }

        @Override
        public TaskItem getTask(String id) {
            return repository.getTask(id);
        }

        @Override
        public List<TaskItem> getAllTasks() {
            return repository.getAllTasks();
        }

        @Override
        public AppSettings getSettings() {
            return repository.getSettings();
        }

        @Override
        public void saveSettings(AppSettings settings) {
            repository.saveSettings(settings);
        }

        @Override
        public boolean deleteTask(String id) {
            return repository.deleteTask(id);
        }

        @Override
        public TaskItem updateTaskStatus(String id, TaskItem.Status status) {
            return repository.updateTaskStatus(id, status);
        }

        @Override
        public FocusState getCurrentFocus() {
            return repository.getCurrentFocus();
        }

        @Override
        public void setCurrentFocus(FocusState focusState) {
            repository.setCurrentFocus(focusState);
        }

        @Override
        public void clearCurrentFocus() {
            repository.clearCurrentFocus();
        }

        @Override
        public int getFocusDurationMinutes() {
            return repository.getSettings().getFocusDurationMinutes();
        }

        @Override
        public RecurringTaskGroup upsertRecurringGroup(RecurringTaskGroup group) {
            return repository.upsertRecurringGroup(group);
        }

        @Override
        public RecurringTaskGroup getRecurringGroup(String id) {
            return repository.getRecurringGroup(id);
        }

        @Override
        public RecurringOccurrenceOverride upsertRecurringOverride(
                RecurringOccurrenceOverride override) {
            return repository.upsertRecurringOverride(override);
        }

        @Override
        public boolean deleteRecurringOverride(String id) {
            return repository.deleteRecurringOverride(id);
        }
    }

    public static final class ConfirmationDescription {
        public final String heading;
        public final String message;
        public final List<String> items;
        public final boolean destructive;
        public final boolean needsClarification;

        private ConfirmationDescription(String heading, String message, List<String> items,
                                        boolean destructive, boolean needsClarification) {
            this.heading = heading;
            this.message = message;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.destructive = destructive;
            this.needsClarification = needsClarification;
        }
    }

    public static final class ExecutionResult {
        public final List<TaskItem> affectedTasks;
        public final String summary;
        public final boolean destructive;
        /** Legacy test snapshot only; production undo must use the revision-guarded ticket. */
        @Deprecated public final String undoSnapshot;
        public final AppRepository.UndoTicket undoTicket;
        public final String sessionId;
        private final AppRepository owner;

        private ExecutionResult(List<TaskItem> affectedTasks, String summary, boolean destructive,
                                String undoSnapshot) {
            this(affectedTasks, summary, destructive, undoSnapshot, null, null, null);
        }

        private ExecutionResult(List<TaskItem> affectedTasks, String summary, boolean destructive,
                                String undoSnapshot, AppRepository owner, String sessionId,
                                AppRepository.UndoTicket undoTicket) {
            List<TaskItem> copies = new ArrayList<>();
            for (TaskItem task : affectedTasks) copies.add(task.copy());
            this.affectedTasks = Collections.unmodifiableList(copies);
            this.summary = summary;
            this.destructive = destructive;
            this.undoSnapshot = undoSnapshot;
            this.owner = owner;
            this.sessionId = sessionId;
            this.undoTicket = undoTicket;
        }

        public boolean canUndo() {
            return canUndo(owner);
        }

        public boolean canUndo(AppRepository repository) {
            return repository != null && repository == owner
                    && repository.canUndo(undoTicket, sessionId);
        }

        /** Null when available; the atomic undo call still rechecks to handle concurrent writes. */
        public String getUndoUnavailableReason(AppRepository repository) {
            if (canUndo(repository)) return null;
            return "无法撤销：没有本地变更、已撤销，或仓库数据/执行会话已改变";
        }

        /** One-shot revision-guarded restore; stale or foreign tickets never replace data. */
        public void undo(AppRepository repository) throws ExecutionException {
            if (repository == null) throw new IllegalArgumentException("repository 不能为空");
            try {
                if (!repository.undoProposalSession(undoTicket, sessionId))
                    throw new ExecutionException("无法撤销：执行会话或仓库数据版本已改变，或操作已撤销");
            } catch (RuntimeException error) {
                throw new ExecutionException("撤销失败：" + safeMessage(error), error);
            }
        }
    }

    public static final class ExecutionException extends Exception {
        public final int actionIndex;
        public final AiPlan.Action.Type actionType;

        ExecutionException(String message) {
            this(-1, null, message, null);
        }

        ExecutionException(String message, Throwable cause) {
            this(-1, null, message, cause);
        }

        ExecutionException(int actionIndex, AiPlan.Action.Type actionType, String message) {
            this(actionIndex, actionType, message, null);
        }

        ExecutionException(int actionIndex, AiPlan.Action.Type actionType, String message,
                           Throwable cause) {
            super(message, cause);
            this.actionIndex = actionIndex;
            this.actionType = actionType;
        }
    }

    private static final class Lookup {
        static final Lookup ABSENT = new Lookup(false, "", null);
        final boolean present;
        final String key;
        final Object value;

        Lookup(boolean present, String key, Object value) {
            this.present = present;
            this.key = key;
            this.value = value;
        }
    }
}
