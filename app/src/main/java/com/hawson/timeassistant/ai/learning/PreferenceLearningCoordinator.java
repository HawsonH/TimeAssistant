package com.hawson.timeassistant.ai.learning;

import com.hawson.timeassistant.ai.AiClient;
import com.hawson.timeassistant.ai.AiPlan;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.data.local.BehaviorEventEntity;
import com.hawson.timeassistant.data.local.LocalIntegrationSettingsStore;
import com.hawson.timeassistant.data.local.LocalIntelligenceStore;
import com.hawson.timeassistant.data.local.PreferencePolicy;
import com.hawson.timeassistant.data.local.PreferenceRuleEntity;
import com.hawson.timeassistant.domain.ChatMessage;

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

/** Foreground-only daily analysis. It can create candidates, never active rules. */
public final class PreferenceLearningCoordinator {
    private static final long DAY = 86_400_000L;
    private final AppRepository repository;
    private final LocalIntelligenceStore store;
    private final LocalIntegrationSettingsStore settings;
    private AiClient.Request request;
    private boolean running;
    private long generation;

    public PreferenceLearningCoordinator(AppRepository repository, LocalIntelligenceStore store,
                                         LocalIntegrationSettingsStore settings) {
        this.repository = repository;
        this.store = store;
        this.settings = settings;
    }

    public boolean isRunning() { return running; }

    public void runIfDue(AiClient client) {
        long today = startOfDay(System.currentTimeMillis());
        if (running || !settings.isLearningEnabled() || settings.getLastAnalysisDay() == today) return;
        long token = ++generation;
        store.loadUnanalyzedEvents(System.currentTimeMillis() - 30L * DAY, 200, events -> {
            if (token != generation || running || settings.getLastAnalysisDay() == today
                    || !eligible(events)) return;
            running = true;
            String evidence = evidenceJson(events);
            List<AiClient.Message> messages = Collections.singletonList(AiClient.Message.user(evidence));
            request = client.chat(analysisPrompt(), messages, new AiClient.Callback<AiPlan>() {
                @Override public void onSuccess(AiPlan plan) {
                    if (token != generation) return;
                    request = null; running = false;
                    long completedAt = System.currentTimeMillis();
                    List<PreferenceRuleEntity> candidates = candidates(plan, events, completedAt);
                    List<String> eventIds = eventIds(events);
                    if (candidates.isEmpty()) {
                        store.markAnalyzed(eventIds, completedAt);
                        settings.markAnalysisComplete(today, completedAt);
                        return;
                    }
                    store.putPreferences(candidates, () -> {
                        store.markAnalyzed(eventIds, completedAt);
                        settings.markAnalysisComplete(today, completedAt);
                        appendCandidateMessage(candidates);
                    });
                }

                @Override public void onError(AiClient.AiError error) {
                    if (token != generation) return;
                    request = null; running = false;
                    settings.markAnalysisComplete(today, System.currentTimeMillis());
                }
            });
        });
    }

    /** User chat always takes priority; cancellation does not consume today's analysis. */
    public void cancelForUserRequest() {
        generation++;
        AiClient.Request active = request;
        if (active != null) active.cancel();
        request = null; running = false;
    }

    private void appendCandidateMessage(List<PreferenceRuleEntity> candidates) {
        try {
            JSONArray actions = new JSONArray();
            for (PreferenceRuleEntity rule : candidates) {
                actions.put(new JSONObject().put("type", "activate_preference")
                        .put("preferenceId", rule.id)
                        .put("summary", summary(rule))
                        .put("reason", rule.reason));
            }
            JSONObject payload = new JSONObject().put("message", "我发现了一些可能适合你的安排偏好。确认后才会生效。")
                    .put("needsClarification", false).put("readRequests", new JSONArray())
                    .put("actions", actions);
            repository.addChatMessage(new ChatMessage(ChatMessage.Role.ASSISTANT,
                    "我发现了一些可能适合你的安排偏好。确认后才会生效。",
                    payload.toString(), ChatMessage.ActionState.PENDING_CONFIRMATION,
                    ChatMessage.ProposalKind.PREFERENCE_CANDIDATE,
                    Collections.emptyList()).withConversationDay(startOfDay(System.currentTimeMillis())));
        } catch (Exception ignored) { }
    }

    private List<PreferenceRuleEntity> candidates(AiPlan plan, List<BehaviorEventEntity> events,
                                                   long now) {
        List<PreferenceRuleEntity> result = new ArrayList<>();
        Set<String> fingerprints = new HashSet<>();
        for (PreferenceRuleEntity existing : store.preferences()) {
            if ((PreferenceRuleEntity.STATUS_ACTIVE.equals(existing.status)
                    || PreferenceRuleEntity.STATUS_CANDIDATE.equals(existing.status)
                    || (PreferenceRuleEntity.STATUS_REJECTED.equals(existing.status)
                    && existing.rejectedUntil > now))) {
                fingerprints.add(PreferencePolicy.fingerprint(existing));
            }
        }
        int distinctDays = distinctDays(events);
        for (AiPlan.Action action : plan.actions) {
            if (action.type != AiPlan.Action.Type.CREATE_PREFERENCE || result.size() >= 3) continue;
            PreferenceRuleEntity rule = fromAction(action, events, now);
            if (!PreferencePolicy.isCandidateEligible(rule, distinctDays, events.size(), now)) continue;
            if (!fingerprints.add(PreferencePolicy.fingerprint(rule))) continue;
            result.add(rule);
        }
        return result;
    }

    private static PreferenceRuleEntity fromAction(AiPlan.Action action,
                                                   List<BehaviorEventEntity> events, long now) {
        PreferenceRuleEntity rule = new PreferenceRuleEntity();
        rule.type = string(action.fields, "preferenceType", PreferenceRuleEntity.TYPE_PREFERRED_WINDOW);
        rule.scopeType = string(action.fields, "scopeType", PreferenceRuleEntity.SCOPE_GLOBAL);
        rule.scopeValue = string(action.fields, "scopeValue", "");
        rule.daysOfWeek = csv(action.fields.get("daysOfWeek"));
        rule.startMinute = integer(action.fields.get("startMinute"), -1);
        rule.endMinute = integer(action.fields.get("endMinute"), -1);
        rule.confidence = decimal(action.fields.get("confidence"), 0.75d);
        rule.origin = PreferenceRuleEntity.ORIGIN_INFERRED;
        rule.status = PreferenceRuleEntity.STATUS_CANDIDATE;
        rule.reason = string(action.fields, "reason", "根据近期改期与操作结果推断");
        rule.createdAt = now; rule.updatedAt = now; rule.expiresAt = now + 30L * DAY;
        rule.evidenceRefs = join(eventIds(events));
        return rule;
    }

    private static boolean eligible(List<BehaviorEventEntity> events) {
        return events != null && events.size() >= 3 && distinctDays(events) >= 2;
    }

    private static int distinctDays(List<BehaviorEventEntity> events) {
        Set<Long> days = new HashSet<>();
        for (BehaviorEventEntity event : events) days.add(event.localDay);
        return days.size();
    }

    private static String evidenceJson(List<BehaviorEventEntity> events) {
        JSONArray values = new JSONArray();
        for (BehaviorEventEntity event : events) {
            try {
                values.put(new JSONObject().put("id", event.id).put("type", event.type)
                        .put("localDay", event.localDay).put("oldStartMinute", event.oldStartMinute)
                        .put("oldEndMinute", event.oldEndMinute).put("newStartMinute", event.newStartMinute)
                        .put("newEndMinute", event.newEndMinute).put("project", event.project)
                        .put("tags", event.tags).put("outcome", event.outcome));
            } catch (Exception ignored) { }
        }
        return new JSONObject(Collections.singletonMap("events", values)).toString();
    }

    private static String analysisPrompt() {
        return "分析脱敏行为事件，只提出可解释的时间窗口偏好候选。禁止推测标题或内容。"
                + "最多返回 3 个 create_preference action；证据不足则 actions=[]。"
                + "preferenceType 只能是 preferred_window/avoid_window，scopeType 只能是 global/project/tag，"
                + "startMinute/endMinute 为 0..1439，confidence 至少 0.70，可选 daysOfWeek(1=周一)。"
                + "不要返回 readRequests。";
    }

    private static String summary(PreferenceRuleEntity rule) {
        return (PreferenceRuleEntity.TYPE_AVOID_WINDOW.equals(rule.type) ? "避开 " : "偏好 ")
                + minute(rule.startMinute) + "–" + minute(rule.endMinute)
                + (rule.scopeValue.isEmpty() ? "" : " · " + rule.scopeValue);
    }

    private static String minute(int value) { return String.format(java.util.Locale.CHINA, "%02d:%02d", value / 60, value % 60); }
    private static String string(Map<String, Object> fields, String key, String fallback) {
        Object value = fields.get(key); return value instanceof String && !((String) value).trim().isEmpty()
                ? ((String) value).trim() : fallback;
    }
    private static int integer(Object value, int fallback) {
        if (value instanceof BigDecimal) return ((BigDecimal) value).intValue();
        if (value instanceof Number) return ((Number) value).intValue();
        return fallback;
    }
    private static double decimal(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }
    private static String csv(Object value) {
        if (!(value instanceof List)) return "";
        List<String> items = new ArrayList<>();
        for (Object item : (List<?>) value) items.add(String.valueOf(integer(item, -1)));
        return join(items);
    }
    private static List<String> eventIds(List<BehaviorEventEntity> events) {
        List<String> ids = new ArrayList<>(); for (BehaviorEventEntity event : events) ids.add(event.id); return ids;
    }
    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) { if (result.length() > 0) result.append(','); result.append(value); }
        return result.toString();
    }
    private static long startOfDay(long value) {
        Calendar calendar = Calendar.getInstance(); calendar.setTimeInMillis(value);
        calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
