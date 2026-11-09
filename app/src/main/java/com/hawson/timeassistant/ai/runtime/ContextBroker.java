package com.hawson.timeassistant.ai.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.hawson.timeassistant.ai.AiPlan;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.data.local.LocalIntelligenceStore;
import com.hawson.timeassistant.data.local.PreferencePolicy;
import com.hawson.timeassistant.data.local.PreferenceRuleEntity;
import com.hawson.timeassistant.data.local.ToolExecutionEntity;
import com.hawson.timeassistant.domain.FocusRecord;
import com.hawson.timeassistant.domain.RecurringTaskGroup;
import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.integration.calendar.CalendarBusyReader;
import com.hawson.timeassistant.proactive.AttentionIssueProvider;
import com.hawson.timeassistant.proactive.TodoPlanningCandidateProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Executes bounded allowlisted context reads off the UI thread. */
public final class ContextBroker implements AutoCloseable {
    public interface Callback {
        void onSuccess(ReadBatch batch);
        void onError(Exception error);
    }

    private final Context context;
    private final AppRepository repository;
    private final LocalIntelligenceStore intelligenceStore;
    private final PolicyEngine policyEngine;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public ContextBroker(Context context, AppRepository repository,
                         LocalIntelligenceStore intelligenceStore, PolicyEngine policyEngine) {
        this.context = context.getApplicationContext();
        this.repository = repository;
        this.intelligenceStore = intelligenceStore;
        this.policyEngine = policyEngine;
    }

    public void read(List<AiPlan.ReadRequest> requests, Callback callback) {
        executor.execute(() -> {
            try {
                JSONArray results = new JSONArray();
                List<String> sources = new ArrayList<>();
                for (AiPlan.ReadRequest request : requests) {
                    ToolExecutionEntity audit = new ToolExecutionEntity();
                    audit.toolId = request.type; audit.requestId = request.requestId;
                    try {
                        if (!policyEngine.mayRead(request.type)) {
                            throw new SecurityException("读取工具未启用或没有权限：" + request.type);
                        }
                        JSONObject data = execute(request);
                        results.put(new JSONObject().put("requestId", request.requestId)
                                .put("type", request.type).put("data", data));
                        audit.status = ToolExecutionEntity.SUCCEEDED;
                        audit.summary = data.optString("summary", "读取完成");
                        sources.add(sourceLabel(request.type));
                    } catch (Exception error) {
                        audit.status = ToolExecutionEntity.FAILED;
                        audit.error = safe(error);
                        throw error;
                    } finally {
                        audit.finishedAt = System.currentTimeMillis();
                        intelligenceStore.putToolExecution(audit);
                    }
                }
                ReadBatch batch = new ReadBatch(results.toString(), sources);
                main.post(() -> callback.onSuccess(batch));
            } catch (Exception error) { main.post(() -> callback.onError(error)); }
        });
    }

    private JSONObject execute(AiPlan.ReadRequest request) throws Exception {
        switch (request.type) {
            case "tasks.query": return queryTasks(request.args);
            case "tasks.get": return getTask(request.args);
            case "recurrence.query": return queryRecurrence(request.args);
            case "focus.summary": return focusSummary(request.args);
            case "preferences.resolve": return resolvePreferences(request.args);
            case "calendar.busy": return calendarBusy(request.args);
            case "attention.query": return new AttentionIssueProvider(repository)
                    .snapshot(System.currentTimeMillis()).toJson(20);
            case "todos.planning": return new TodoPlanningCandidateProvider(repository)
                    .snapshot(System.currentTimeMillis()).toJson(30);
            default: throw new IllegalArgumentException("未知读取工具：" + request.type);
        }
    }

    private JSONObject queryTasks(Map<String, Object> args) throws Exception {
        long startAt = longArg(args, "startAt", 0);
        long endAt = longArg(args, "endAt", 0);
        if (startAt > 0 && endAt > 0 && endAt - startAt > 31L * 86_400_000L) {
            throw new IllegalArgumentException("任务查询范围不能超过 31 天");
        }
        int limit = Math.min(50, Math.max(1, intArg(args, "limit", 25)));
        String status = stringArg(args, "status");
        String project = stringArg(args, "project");
        String tag = stringArg(args, "tag");
        JSONArray items = new JSONArray();
        for (TaskItem task : repository.getAllTasks()) {
            long anchor = task.getScheduledStartAt() > 0 ? task.getScheduledStartAt()
                    : task.getPlannedDateAt() > 0 ? task.getPlannedDateAt() : task.getDueAt();
            if (startAt > 0 && (anchor == 0 || anchor < startAt)) continue;
            if (endAt > 0 && (anchor == 0 || anchor >= endAt)) continue;
            if (!status.isEmpty() && !status.equals(task.getStatus().storageValue())) continue;
            if (!project.isEmpty() && !project.equalsIgnoreCase(value(task.getProject()))) continue;
            if (!tag.isEmpty() && !containsIgnoreCase(task.getTags(), tag)) continue;
            items.put(taskJson(task));
            if (items.length() >= limit) break;
        }
        return new JSONObject().put("summary", "读取 " + items.length() + " 个任务")
                .put("items", items);
    }

    private JSONObject getTask(Map<String, Object> args) throws Exception {
        TaskItem task = repository.getTask(stringArg(args, "taskId"));
        return new JSONObject().put("summary", task == null ? "任务不存在" : "读取 1 个任务")
                .put("item", task == null ? JSONObject.NULL : taskJson(task));
    }

    private JSONObject queryRecurrence(Map<String, Object> args) throws Exception {
        long startAt = longArg(args, "startAt", System.currentTimeMillis());
        long endAt = longArg(args, "endAt", startAt + 7L * 86_400_000L);
        if (endAt <= startAt || endAt - startAt > 31L * 86_400_000L) {
            throw new IllegalArgumentException("周期查询范围必须在 31 天内");
        }
        int limit = Math.min(50, Math.max(1, intArg(args, "limit", 25)));
        JSONArray items = new JSONArray();
        Calendar day = Calendar.getInstance(); day.setTimeInMillis(startAt);
        day.set(Calendar.HOUR_OF_DAY, 12); day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0);
        while (day.getTimeInMillis() < endAt && items.length() < limit) {
            for (RecurringTaskGroup group : repository.getAllRecurringGroups()) {
                long occurrence = group.occurrenceStartOnDay(day.getTimeInMillis());
                if (occurrence <= 0 || occurrence < startAt || occurrence >= endAt) continue;
                items.put(new JSONObject().put("groupId", group.getId())
                        .put("title", group.getTitle()).put("startAt", occurrence)
                        .put("endAt", occurrence + group.getDurationMillis()));
                if (items.length() >= limit) break;
            }
            day.add(Calendar.DAY_OF_YEAR, 1);
        }
        return new JSONObject().put("summary", "读取 " + items.length() + " 个周期实例")
                .put("items", items);
    }

    private JSONObject focusSummary(Map<String, Object> args) throws Exception {
        long endAt = longArg(args, "endAt", System.currentTimeMillis());
        long startAt = longArg(args, "startAt", endAt - 7L * 86_400_000L);
        if (endAt <= startAt || endAt - startAt > 90L * 86_400_000L) {
            throw new IllegalArgumentException("专注统计范围必须在 90 天内");
        }
        int count = 0, minutes = 0, interruptions = 0;
        for (FocusRecord record : repository.getFocusHistory()) {
            if (record.getCompletedAt() < startAt || record.getCompletedAt() >= endAt) continue;
            count++; minutes += record.getDurationMinutesRounded();
            interruptions += record.getInterruptionCount();
        }
        return new JSONObject().put("summary", "读取专注汇总")
                .put("count", count).put("totalMinutes", minutes)
                .put("interruptions", interruptions);
    }

    private JSONObject resolvePreferences(Map<String, Object> args) throws Exception {
        String project = stringArg(args, "project");
        List<String> tags = stringListArg(args, "tags");
        List<PreferenceRuleEntity> rules = PreferencePolicy.resolve(
                intelligenceStore.preferences(), project, tags, System.currentTimeMillis());
        JSONArray items = new JSONArray();
        for (PreferenceRuleEntity rule : rules) {
            items.put(new JSONObject().put("id", rule.id).put("type", rule.type)
                    .put("scopeType", rule.scopeType).put("scopeValue", rule.scopeValue)
                    .put("daysOfWeek", rule.daysOfWeek).put("startMinute", rule.startMinute)
                    .put("endMinute", rule.endMinute).put("origin", rule.origin));
        }
        com.hawson.timeassistant.domain.AppSettings settings = repository.getSettings();
        JSONArray blocked = new JSONArray();
        for (com.hawson.timeassistant.domain.AppSettings.TimeRange range
                : settings.getBlockedTimeRanges()) {
            blocked.put(new JSONObject().put("startMinute", range.getStartMinute())
                    .put("endMinute", range.getEndMinute()));
        }
        JSONObject hardConstraints = new JSONObject()
                .put("availableStartMinute", settings.getAvailableStartMinute())
                .put("availableEndMinute", settings.getAvailableEndMinute())
                .put("workStartMinute", settings.getWorkStartMinute())
                .put("workEndMinute", settings.getWorkEndMinute())
                .put("blockedWindows", blocked);
        return new JSONObject().put("summary", "读取 " + items.length() + " 条偏好")
                .put("hardConstraints", hardConstraints).put("items", items);
    }

    private JSONObject calendarBusy(Map<String, Object> args) throws Exception {
        long startAt = longArg(args, "startAt", 0);
        long endAt = longArg(args, "endAt", 0);
        List<CalendarBusyReader.BusyBlock> blocks = new CalendarBusyReader()
                .query(context.getContentResolver(), startAt, endAt);
        JSONArray items = new JSONArray();
        for (CalendarBusyReader.BusyBlock block : blocks) {
            items.put(new JSONObject().put("startAt", block.startAt)
                    .put("endAt", block.endAt).put("allDay", block.allDay));
        }
        return new JSONObject().put("summary", "读取 " + items.length() + " 个日历忙碌区间")
                .put("items", items);
    }

    private static JSONObject taskJson(TaskItem task) throws Exception {
        return new JSONObject().put("id", task.getId()).put("title", task.getTitle())
                .put("status", task.getStatus().storageValue())
                .put("scheduledStartAt", task.getScheduledStartAt())
                .put("scheduledEndAt", task.getScheduledEndAt())
                .put("plannedDateAt", task.getPlannedDateAt()).put("dueAt", task.getDueAt())
                .put("project", value(task.getProject())).put("tags", new JSONArray(task.getTags()))
                .put("requiresTimeArrangement", task.requiresTimeArrangement());
    }

    private static long longArg(Map<String, Object> args, String key, long fallback) {
        Object value = args.get(key);
        if (value instanceof BigDecimal) return ((BigDecimal) value).longValue();
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) try { return Long.parseLong(((String) value).trim()); }
        catch (NumberFormatException ignored) { }
        return fallback;
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        long value = longArg(args, key, fallback);
        return value > Integer.MAX_VALUE || value < Integer.MIN_VALUE ? fallback : (int) value;
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static List<String> stringListArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof List)) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) if (item instanceof String) result.add(((String) item).trim());
        return result;
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        for (String value : values) if (value.equalsIgnoreCase(expected)) return true;
        return false;
    }

    private static String value(String value) { return value == null ? "" : value; }
    private static String safe(Throwable error) {
        String value = error == null || error.getMessage() == null
                ? (error == null ? "unknown" : error.getClass().getSimpleName()) : error.getMessage();
        return value.length() > 240 ? value.substring(0, 240) : value;
    }
    private static String sourceLabel(String type) {
        switch (type) {
            case "tasks.query": case "tasks.get": return "任务";
            case "recurrence.query": return "周期日程";
            case "focus.summary": return "专注统计";
            case "preferences.resolve": return "用户偏好";
            case "calendar.busy": return "系统日历忙碌时间";
            case "attention.query": return "注意事项";
            case "todos.planning": return "待办整理候选";
            default: return type;
        }
    }

    @Override public void close() { executor.shutdownNow(); }

    public static final class ReadBatch {
        public final String json;
        public final List<String> sources;
        ReadBatch(String json, List<String> sources) {
            this.json = json;
            this.sources = Collections.unmodifiableList(new ArrayList<>(new HashSet<>(sources)));
        }
    }
}
