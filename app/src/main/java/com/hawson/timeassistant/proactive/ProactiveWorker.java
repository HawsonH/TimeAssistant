package com.hawson.timeassistant.proactive;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.hawson.timeassistant.ai.AiClient;
import com.hawson.timeassistant.ai.AiPlan;
import com.hawson.timeassistant.ai.runtime.ContextBroker;
import com.hawson.timeassistant.ai.runtime.PolicyEngine;
import com.hawson.timeassistant.ai.runtime.PromptCompiler;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.data.local.LocalAiDao;
import com.hawson.timeassistant.data.local.LocalAiDatabase;
import com.hawson.timeassistant.data.local.LocalIntegrationSettingsStore;
import com.hawson.timeassistant.data.local.LocalIntelligenceStore;
import com.hawson.timeassistant.data.local.ProactiveRunEntity;
import com.hawson.timeassistant.domain.ChatMessage;
import com.hawson.timeassistant.domain.FocusState;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Evaluates conditions first and only invokes the model when a useful check-in exists. */
public final class ProactiveWorker extends Worker {
    public ProactiveWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        try {
            return ProactiveRunGate.run(this::runCheck);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.success();
        }
    }

    private Result runCheck() {
        Context context = getApplicationContext();
        LocalIntegrationSettingsStore localSettings = new LocalIntegrationSettingsStore(context);
        ProactiveSettings settings = localSettings.getProactiveSettings();
        boolean manual = getInputData().getBoolean(ProactiveScheduleCoordinator.INPUT_MANUAL, false);
        int triggerMinute = getInputData().getInt(ProactiveScheduleCoordinator.INPUT_MINUTE, -1);
        String generation = getInputData().getString(ProactiveScheduleCoordinator.INPUT_GENERATION);
        if (!isCurrent()) return Result.success();
        ProactiveRoutine attentionRoutine = manual ? null
                : ProactiveSchedulePlan.attentionRoutineAt(settings, triggerMinute);
        ProactiveRoutine routine = manual ? ProactiveRoutine.CHECK_IN
                : attentionRoutine == null ? ProactiveRoutine.TODO_REVIEW : attentionRoutine;
        if (!manual) ProactiveScheduleCoordinator.scheduleTomorrow(context, generation, triggerMinute,
                getInputData().getLong(ProactiveScheduleCoordinator.INPUT_SCHEDULED_AT, System.currentTimeMillis()));

        long now = System.currentTimeMillis();
        long day = startOfDay(now);
        ProactiveRunEntity run = new ProactiveRunEntity();
        run.routineType = routine.storageValue;
        run.localDay = day;
        run.scheduledAt = getInputData().getLong(
                ProactiveScheduleCoordinator.INPUT_SCHEDULED_AT, now);
        LocalAiDao dao = LocalAiDatabase.get(context).dao();

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        int minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        if (settings.isQuietAt(minute)) {
            finish(dao, run, ProactiveRunEntity.SKIPPED_QUIET, "处于免打扰时间", null);
            return Result.success();
        }

        AppRepository snapshotRepository = AppRepository.get(context);
        if (ProactiveMessageBus.isUserAiBusy()) {
            finish(dao, run, ProactiveRunEntity.DEFERRED_USER_AI, "用户正在与 AI 对话，延后检查", null);
            ProactiveScheduleCoordinator.deferCheck(context, generation, triggerMinute, manual);
            return Result.success();
        }
        if (snapshotRepository.getCurrentFocus().getState() == FocusState.State.RUNNING) {
            finish(dao, run, ProactiveRunEntity.DEFERRED_FOCUS, "专注进行中，延后检查", null);
            ProactiveScheduleCoordinator.deferCheck(context, generation, triggerMinute, manual);
            return Result.success();
        }

        AttentionIssueProvider.Snapshot attention =
                new AttentionIssueProvider(snapshotRepository).snapshot(now);
        TodoPlanningCandidateProvider.Snapshot todos =
                new TodoPlanningCandidateProvider(snapshotRepository).snapshot(now);
        boolean useAttention = (manual ? settings.attentionTriggerEnabled : attentionRoutine != null)
                && !attention.isEmpty();
        boolean useTodos = settings.todoReviewEnabled
                && (manual || settings.hasTodoReviewAt(triggerMinute)) && !todos.isEmpty();
        if (!useAttention && !useTodos) {
            finish(dao, run, ProactiveRunEntity.SKIPPED_EMPTY, "没有需要主动整理的事项", null);
            return Result.success();
        }

        String fingerprint = AttentionIssueProvider.sha256(
                (useAttention ? attention.fingerprint() : "") + '|'
                        + (useTodos ? todos.fingerprint() : ""));
        run.fingerprint = fingerprint;
        if (dao.proactiveFingerprintCount(day, fingerprint) > 0) {
            finish(dao, run, ProactiveRunEntity.SKIPPED_DUPLICATE, "相同内容今天已提醒", null);
            return Result.success();
        }
        int generated = dao.proactiveGeneratedCount(day);
        if (generated >= settings.dailyLimit) {
            finish(dao, run, ProactiveRunEntity.SKIPPED_LIMIT, "已达到今天的主动消息上限", null);
            return Result.success();
        }

        if (!isCurrent()) return Result.success();
        dao.putProactiveRun(run);
        Generated generatedMessage = settings.backgroundAiEnabled
                ? generateWithAi(context, snapshotRepository, routine, attention, todos,
                useAttention, useTodos) : localSummary(routine, attention, todos, useAttention, useTodos);
        if (!isCurrent()) {
            finish(dao, run, "skipped_stale", "Configuration changed", null);
            return Result.success();
        }
        if (ProactiveMessageBus.isUserAiBusy()) {
            finish(dao, run, ProactiveRunEntity.DEFERRED_USER_AI, "用户开始了对话，本次结果不展示", null);
            ProactiveScheduleCoordinator.deferCheck(context, generation, triggerMinute, manual);
            return Result.success();
        }
        if (generatedMessage == null) {
            finish(dao, run, ProactiveRunEntity.FAILED, "生成失败", "AI 未返回可用结果");
            return Result.success();
        }

        String prefix = routine.label + "｜";
        String content = generatedMessage.message.startsWith(prefix)
                ? generatedMessage.message : prefix + generatedMessage.message;
        ChatMessage message = new ChatMessage(ChatMessage.Role.ASSISTANT, content,
                generatedMessage.payload,
                generatedMessage.payload == null ? ChatMessage.ActionState.NONE
                        : ChatMessage.ActionState.PENDING_CONFIRMATION,
                ChatMessage.ProposalKind.PROACTIVE_PLAN, Collections.emptyList())
                .withContextSources(generatedMessage.sources).withConversationDay(day);
        synchronized (LocalIntegrationSettingsStore.PROACTIVE_LOCK) {
            if (!isCurrent() || startOfDay(System.currentTimeMillis()) != day) {
                finish(dao, run, "skipped_stale", "Configuration or day changed", null);
                return Result.success();
            }
            boolean busy = ProactiveMessageBus.isUserAiBusy();
            if (busy || AppRepository.get(context).getCurrentFocus().getState() == FocusState.State.RUNNING) {
                ProactiveScheduleCoordinator.deferCheck(context, generation, triggerMinute, manual);
                finish(dao, run, busy ? ProactiveRunEntity.DEFERRED_USER_AI : ProactiveRunEntity.DEFERRED_FOCUS,
                        "Deferred before publication", null);
                return Result.success();
            }
            Calendar publishTime = Calendar.getInstance();
            if (settings.isQuietAt(publishTime.get(Calendar.HOUR_OF_DAY) * 60
                    + publishTime.get(Calendar.MINUTE))) {
                finish(dao, run, ProactiveRunEntity.SKIPPED_QUIET, "Quiet before publication", null);
                return Result.success();
            }
            if (dao.proactiveGeneratedCount(day) >= settings.dailyLimit) {
                finish(dao, run, ProactiveRunEntity.SKIPPED_LIMIT, "Daily limit reached", null);
                return Result.success();
            }
            if (dao.proactiveFingerprintCount(day, fingerprint) > 0) {
                finish(dao, run, ProactiveRunEntity.SKIPPED_DUPLICATE, "Already published", null);
                return Result.success();
            }
            if (!ProactiveMessageBus.publish(message)) {
                try {
                    // Reload before appending to avoid writing a stale full-repository snapshot.
                    AppRepository live = AppRepository.get(context);
                    if (message.hasAction()) live.cancelPendingChatActions(
                            day, ChatMessage.ProposalKind.PROACTIVE_PLAN);
                    live.addChatMessage(message);
                } catch (RuntimeException error) {
                    finish(dao, run, ProactiveRunEntity.FAILED, "无法保存主动消息", safe(error));
                    return Result.success();
                }
            }
            run.chatMessageId = message.getId();
            if (settings.notificationEnabled) {
                run.notificationSentAt = ProactiveNotification.show(context, routine, concise(content), run.id);
            }
            finish(dao, run, ProactiveRunEntity.GENERATED,
                    "注意 " + (useAttention ? attention.count() : 0)
                            + " 项，待办 " + (useTodos ? todos.count() : 0) + " 项", null);
        }
        return Result.success();
    }

    private boolean isCurrent() {
        if (isStopped()) return false;
        return ProactiveSchedulePlan.isCurrent(
                new LocalIntegrationSettingsStore(getApplicationContext()).getProactiveSettings(),
                getInputData().getString(ProactiveScheduleCoordinator.INPUT_GENERATION),
                getInputData().getInt(ProactiveScheduleCoordinator.INPUT_MINUTE, -1),
                getInputData().getBoolean(ProactiveScheduleCoordinator.INPUT_MANUAL, false));
    }

    private Generated generateWithAi(Context context, AppRepository repository,
                                     ProactiveRoutine routine,
                                     AttentionIssueProvider.Snapshot attention,
                                     TodoPlanningCandidateProvider.Snapshot todos,
                                     boolean useAttention, boolean useTodos) {
        com.hawson.timeassistant.AiServices ai =
                ((com.hawson.timeassistant.TimeAssistantApplication) context.getApplicationContext()).getAiServices();
        if (!ai.configured()) {
            return localSummary(routine, attention, todos, useAttention, useTodos);
        }

        LocalIntelligenceStore intelligence = new LocalIntelligenceStore(context);
        LocalIntegrationSettingsStore integrations = new LocalIntegrationSettingsStore(context);
        PolicyEngine policy = new PolicyEngine(context, integrations);
        ContextBroker broker = new ContextBroker(context, repository, intelligence, policy);
        PromptCompiler compiler = new PromptCompiler(repository, intelligence, policy);
        AiClient client;
        try { client = ai.client(); }
        catch (RuntimeException error) {
            broker.close(); intelligence.close();
            return localSummary(routine, attention, todos, useAttention, useTodos);
        }
        ProactiveAiSession runtime = new ProactiveAiSession(client, broker, this::isCurrent);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Generated> value = new AtomicReference<>();
        String prompt = "这是应用内部的" + routine.label + "，不是用户新建任务的请求。"
                + "当前注意事项=" + (useAttention ? attention.count() : 0)
                + "，可整理待办=" + (useTodos ? todos.count() : 0) + "。"
                + (useAttention ? "先调用 attention.query。" : "")
                + (useTodos ? "先调用 todos.planning，并在需要排期时读取偏好与日历忙碌时间。" : "")
                + "给出简短、有帮助、不过度催促的中文消息。只能提议 schedule_task、update_task 或 complete_task；"
                + "待办转日程必须 schedule_task 修改原 TaskItem，禁止创建新任务，禁止删除、取消、开始专注或修改偏好。"
                + "信息不足时只询问用户，不生成 action。所有 action 都要等待用户确认。";
        try {
            runtime.run(compiler.compile(), Collections.singletonList(
                    AiClient.Message.user(prompt)), new ProactiveAiSession.Callback() {
                @Override public void onSuccess(AiPlan plan, List<String> sources) {
                    if (actionsAllowed(plan.actions, attention, todos)) {
                        value.set(new Generated(plan.message,
                                plan.actions.isEmpty() ? null : plan.rawJson, sources));
                    }
                    done.countDown();
                }
                @Override public void onError() { done.countDown(); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(250);
            while (!done.await(250, TimeUnit.MILLISECONDS)) {
                if (!isCurrent() || System.nanoTime() >= deadline) {
                    runtime.cancel();
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            runtime.cancel();
        } finally {
            runtime.cancel(); client.close(); broker.close(); intelligence.close();
        }
        Generated result = value.get();
        return result == null ? localSummary(routine, attention, todos, useAttention, useTodos) : result;
    }

    private static boolean actionsAllowed(List<AiPlan.Action> actions,
                                          AttentionIssueProvider.Snapshot attention,
                                          TodoPlanningCandidateProvider.Snapshot todos) {
        Set<String> schedulable = new HashSet<>();
        Set<String> completable = new HashSet<>();
        for (com.hawson.timeassistant.domain.TaskItem task : attention.overdue) {
            schedulable.add(task.getId());
        }
        for (com.hawson.timeassistant.domain.TaskItem task : attention.incompleteTime) {
            schedulable.add(task.getId());
        }
        for (com.hawson.timeassistant.domain.TaskItem task : todos.tasks) {
            schedulable.add(task.getId());
        }
        for (com.hawson.timeassistant.domain.TaskItem task : attention.awaitingConfirmation) {
            completable.add(task.getId());
        }
        for (AiPlan.Action action : actions) {
            Object rawTaskId = action.get("taskId");
            String taskId = rawTaskId instanceof String ? ((String) rawTaskId).trim() : "";
            if (taskId.isEmpty()) return false;
            switch (action.getType()) {
                case SCHEDULE_TASK:
                case UPDATE_TASK:
                    if (!schedulable.contains(taskId) && !completable.contains(taskId)) return false;
                    break;
                case COMPLETE_TASK:
                    if (!completable.contains(taskId)) return false;
                    break;
                default:
                    return false;
            }
        }
        return true;
    }

    private static Generated localSummary(ProactiveRoutine routine,
                                          AttentionIssueProvider.Snapshot attention,
                                          TodoPlanningCandidateProvider.Snapshot todos,
                                          boolean useAttention, boolean useTodos) {
        StringBuilder text = new StringBuilder();
        if (useAttention) text.append("注意页有 ").append(attention.count()).append(" 项需要处理");
        if (useTodos) {
            if (text.length() > 0) text.append("；");
            text.append("还有 ").append(todos.count()).append(" 个待办可以一起梳理");
        }
        text.append("。打开对话后可以继续调整，任何改动都会先等你确认。");
        return new Generated(text.toString(), null, Collections.emptyList());
    }

    private static void finish(LocalAiDao dao, ProactiveRunEntity run, String status,
                               String summary, String error) {
        run.status = status;
        run.summary = summary == null ? "" : summary;
        run.error = error == null ? "" : error;
        run.finishedAt = System.currentTimeMillis();
        dao.putProactiveRun(run);
    }

    private static String concise(String value) {
        String clean = value == null ? "有事项可以整理" : value.replace('\n', ' ').trim();
        return clean.length() <= 120 ? clean : clean.substring(0, 117) + "…";
    }

    private static String safe(Throwable error) {
        String value = error == null || error.getMessage() == null
                ? (error == null ? "unknown" : error.getClass().getSimpleName()) : error.getMessage();
        return value.length() <= 240 ? value : value.substring(0, 240);
    }

    private static long startOfDay(long value) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(value);
        day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0);
        return day.getTimeInMillis();
    }

    private static final class Generated {
        final String message;
        final String payload;
        final List<String> sources;
        Generated(String message, String payload, List<String> sources) {
            this.message = message == null || message.trim().isEmpty() ? "有事项可以一起整理。" : message.trim();
            this.payload = payload == null || payload.trim().isEmpty() ? null : payload;
            this.sources = sources == null ? Collections.emptyList() : sources;
        }
    }
}
