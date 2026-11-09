/**
 * 聊天消息
 *
 * 用户与 AI 的对话记录，可附带待确认的结构化操作提案。
 */
package com.hawson.timeassistant.domain;

import com.hawson.timeassistant.util.JsonInterop;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Immutable persisted chat message, optionally carrying a structured action proposal. */
public final class ChatMessage {
    public static final class Attachment {
        private final String name;
        private final String mimeType;
        private final long size;

        public Attachment(String name, String mimeType, long size) {
            this.name = requireText(name, "attachment name");
            this.mimeType = mimeType == null || mimeType.trim().isEmpty()
                    ? "application/octet-stream" : mimeType.trim();
            this.size = Math.max(0, size);
        }

        public String getName() { return name; }
        public String getMimeType() { return mimeType; }
        public long getSize() { return size; }
        public boolean isImage() { return mimeType.startsWith("image/"); }

        Map<String, Object> toJsonValue() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("name", name);
            json.put("mimeType", mimeType);
            json.put("size", size);
            return json;
        }
    }

    public enum Role {
        USER("user"), ASSISTANT("assistant"), SYSTEM("system"), TOOL("tool");

        private final String storageValue;

        Role(String storageValue) {
            this.storageValue = storageValue;
        }

        public String storageValue() { return storageValue; }

        public static Role fromStorage(String raw) {
            if (raw != null) {
                String normalized = raw.trim().toLowerCase(Locale.ROOT);
                for (Role role : values()) {
                    if (role.storageValue.equals(normalized)
                            || role.name().toLowerCase(Locale.ROOT).equals(normalized)) return role;
                }
            }
            return ASSISTANT;
        }
    }

    public enum ActionState {
        NONE("none"),
        PENDING_CONFIRMATION("pending_confirmation"),
        CONFIRMED("confirmed"),
        CANCELLED("cancelled"),
        EXECUTED("executed"),
        FAILED("failed");

        private final String storageValue;

        ActionState(String storageValue) {
            this.storageValue = storageValue;
        }

        public String storageValue() { return storageValue; }

        public static ActionState fromStorage(String raw) {
            if (raw != null) {
                String normalized = raw.trim().toLowerCase(Locale.ROOT);
                for (ActionState state : values()) {
                    if (state.storageValue.equals(normalized)
                            || state.name().toLowerCase(Locale.ROOT).equals(normalized)) return state;
                }
            }
            return NONE;
        }
    }

    public enum ProposalKind {
        TASK_PLAN("task_plan"), PREFERENCE_CANDIDATE("preference_candidate"),
        EXTERNAL_HANDOFF("external_handoff"), PROACTIVE_PLAN("proactive_plan");
        private final String storageValue;
        ProposalKind(String storageValue) { this.storageValue = storageValue; }
        public String storageValue() { return storageValue; }
        public static ProposalKind fromStorage(String raw) {
            if (raw != null) for (ProposalKind kind : values()) {
                if (kind.storageValue.equalsIgnoreCase(raw) || kind.name().equalsIgnoreCase(raw)) return kind;
            }
            return TASK_PLAN;
        }
    }

    /** Persisted result of one action inside a structured AI proposal. */
    public enum ActionItemState {
        PENDING("pending"),
        EXECUTED("executed"),
        HANDED_OFF("handed_off"),
        CANCELLED("cancelled"),
        REVERTED("reverted"),
        FAILED("failed");

        private final String storageValue;

        ActionItemState(String storageValue) {
            this.storageValue = storageValue;
        }

        public String storageValue() { return storageValue; }

        public static ActionItemState fromStorage(String raw) {
            if (raw != null) {
                String normalized = raw.trim().toLowerCase(Locale.ROOT);
                for (ActionItemState state : values()) {
                    if (state.storageValue.equals(normalized)
                            || state.name().toLowerCase(Locale.ROOT).equals(normalized)) return state;
                }
            }
            return PENDING;
        }
    }

    private final String id;
    private final Role role;
    private final String content;
    private final String actionPayload;
    private final ActionState actionState;
    private final ProposalKind proposalKind;
    private final List<ActionItemState> actionItemStates;
    private final List<Attachment> attachments;
    private final List<String> contextSources;
    private final long conversationDay;
    private final long createdAt;
    private final long updatedAt;

    public ChatMessage(Role role, String content) {
        this(UUID.randomUUID().toString(), role, content, null, ActionState.NONE,
                ProposalKind.TASK_PLAN, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                startOfLocalDay(System.currentTimeMillis()),
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    public ChatMessage(Role role, String content, String actionPayload, ActionState actionState) {
        this(UUID.randomUUID().toString(), role, content, actionPayload, actionState,
                ProposalKind.TASK_PLAN, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                startOfLocalDay(System.currentTimeMillis()),
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    public ChatMessage(Role role, String content, String actionPayload, ActionState actionState,
                       List<Attachment> attachments) {
        this(UUID.randomUUID().toString(), role, content, actionPayload, actionState,
                ProposalKind.TASK_PLAN, Collections.emptyList(), attachments, Collections.emptyList(),
                startOfLocalDay(System.currentTimeMillis()),
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    public ChatMessage(Role role, String content, String actionPayload, ActionState actionState,
                       ProposalKind proposalKind, List<Attachment> attachments) {
        this(UUID.randomUUID().toString(), role, content, actionPayload, actionState,
                proposalKind, Collections.emptyList(), attachments, Collections.emptyList(),
                startOfLocalDay(System.currentTimeMillis()),
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    private ChatMessage(
            String id,
            Role role,
            String content,
            String actionPayload,
            ActionState actionState,
            ProposalKind proposalKind,
            List<ActionItemState> actionItemStates,
            List<Attachment> attachments,
            List<String> contextSources,
            long conversationDay,
            long createdAt,
            long updatedAt
    ) {
        this.id = requireText(id, "id");
        this.role = role == null ? Role.ASSISTANT : role;
        this.content = content == null ? "" : content;
        this.actionPayload = normalizePayload(actionPayload);
        this.actionState = actionState == null ? ActionState.NONE : actionState;
        this.proposalKind = proposalKind == null ? ProposalKind.TASK_PLAN : proposalKind;
        this.actionItemStates = Collections.unmodifiableList(actionItemStates == null
                ? Collections.emptyList() : new ArrayList<>(actionItemStates));
        this.attachments = Collections.unmodifiableList(attachments == null
                ? Collections.emptyList() : new ArrayList<>(attachments));
        this.contextSources = Collections.unmodifiableList(contextSources == null
                ? Collections.emptyList() : new ArrayList<>(contextSources));
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
        this.conversationDay = conversationDay > 0
                ? startOfLocalDay(conversationDay) : startOfLocalDay(this.createdAt);
        this.updatedAt = Math.max(this.createdAt, updatedAt);
        if (this.content.trim().isEmpty() && this.actionPayload == null && this.attachments.isEmpty()) {
            throw new IllegalArgumentException("content, actionPayload and attachments cannot all be empty");
        }
        if (this.actionPayload == null && this.actionState != ActionState.NONE) {
            throw new IllegalArgumentException("actionState requires actionPayload");
        }
    }

    public String getId() { return id; }
    public Role getRole() { return role; }
    public String getContent() { return content; }
    public String getActionPayload() { return actionPayload; }
    public ActionState getActionState() { return actionState; }
    public ProposalKind getProposalKind() { return proposalKind; }
    public List<ActionItemState> getActionItemStates() { return actionItemStates; }
    public List<Attachment> getAttachments() { return attachments; }
    public List<String> getContextSources() { return contextSources; }
    public long getConversationDay() { return conversationDay; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public boolean hasAction() { return actionPayload != null; }

    /** Returns a backward-compatible state for proposals saved before item states existed. */
    public ActionItemState getActionItemState(int index) {
        if (index < 0) throw new IllegalArgumentException("action index must not be negative");
        if (index < actionItemStates.size()) return actionItemStates.get(index);
        return defaultItemState(actionState);
    }

    public ChatMessage withActionState(ActionState state) {
        List<ActionItemState> nextItemStates = new ArrayList<>(actionItemStates);
        ActionItemState replacement = terminalItemState(state);
        if (replacement != null) {
            for (int index = 0; index < nextItemStates.size(); index++) {
                if (nextItemStates.get(index) == ActionItemState.PENDING) {
                    nextItemStates.set(index, replacement);
                }
            }
        }
        long nextUpdatedAt = updatedAt == Long.MAX_VALUE
                ? Long.MAX_VALUE : Math.max(System.currentTimeMillis(), updatedAt + 1);
        return new ChatMessage(id, role, content, actionPayload, state, proposalKind, nextItemStates, attachments, contextSources,
                conversationDay, createdAt, nextUpdatedAt);
    }

    /** Updates selected proposal actions while preserving the audit state of the other actions. */
    public ChatMessage withActionItemStates(List<Integer> indexes, ActionItemState state,
                                            int actionCount) {
        if (actionPayload == null) throw new IllegalStateException("action item state requires actionPayload");
        if (actionCount <= 0) throw new IllegalArgumentException("actionCount must be positive");
        if (indexes == null || indexes.isEmpty()) return this;
        ActionItemState normalizedState = state == null ? ActionItemState.PENDING : state;
        List<ActionItemState> nextItemStates = normalizedItemStates(actionCount);
        for (Integer value : indexes) {
            int index = value == null ? -1 : value;
            if (index < 0 || index >= actionCount) {
                throw new IllegalArgumentException("action index is outside proposal bounds: " + index);
            }
            nextItemStates.set(index, normalizedState);
        }
        long nextUpdatedAt = updatedAt == Long.MAX_VALUE
                ? Long.MAX_VALUE : Math.max(System.currentTimeMillis(), updatedAt + 1);
        return new ChatMessage(id, role, content, actionPayload,
                summarizeActionState(nextItemStates), proposalKind, nextItemStates, attachments, contextSources,
                conversationDay, createdAt, nextUpdatedAt);
    }

    /** Cancels only unresolved items when a later AI proposal supersedes this proposal. */
    public ChatMessage cancelPendingActionItems() {
        if (actionItemStates.isEmpty()) return withActionState(ActionState.CANCELLED);
        List<ActionItemState> nextItemStates = new ArrayList<>(actionItemStates);
        boolean changed = false;
        for (int index = 0; index < nextItemStates.size(); index++) {
            if (nextItemStates.get(index) == ActionItemState.PENDING) {
                nextItemStates.set(index, ActionItemState.CANCELLED);
                changed = true;
            }
        }
        if (!changed) return this;
        long nextUpdatedAt = updatedAt == Long.MAX_VALUE
                ? Long.MAX_VALUE : Math.max(System.currentTimeMillis(), updatedAt + 1);
        return new ChatMessage(id, role, content, actionPayload,
                summarizeActionState(nextItemStates), proposalKind, nextItemStates, attachments, contextSources,
                conversationDay, createdAt, nextUpdatedAt);
    }

    /** Assigns the conversation session day without changing the message creation timestamp. */
    public ChatMessage withConversationDay(long value) {
        return new ChatMessage(id, role, content, actionPayload, actionState,
                proposalKind, actionItemStates, attachments, contextSources, value, createdAt, updatedAt);
    }

    public ChatMessage withContextSources(List<String> sources) {
        return new ChatMessage(id, role, content, actionPayload, actionState, proposalKind,
                actionItemStates, attachments, sources, conversationDay, createdAt, updatedAt);
    }

    public JSONObject actionPayloadAsJson() throws JSONException {
        if (actionPayload == null) return null;
        return new JSONObject(actionPayload);
    }

    public JSONObject toJson() throws JSONException {
        return JsonInterop.toJsonObject(toJsonValue());
    }

    Map<String, Object> toJsonValue() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("role", role.storageValue());
        json.put("content", content);
        json.put("actionPayload", actionPayload);
        json.put("actionState", actionState.storageValue());
        json.put("proposalKind", proposalKind.storageValue());
        List<Object> itemStateValues = new ArrayList<>();
        for (ActionItemState state : actionItemStates) {
            itemStateValues.add(state.storageValue());
        }
        json.put("actionItemStates", itemStateValues);
        List<Object> attachmentValues = new ArrayList<>();
        for (Attachment attachment : attachments) attachmentValues.add(attachment.toJsonValue());
        json.put("attachments", attachmentValues);
        json.put("contextSources", new ArrayList<>(contextSources));
        json.put("conversationDay", conversationDay);
        json.put("createdAt", createdAt);
        json.put("updatedAt", updatedAt);
        return Collections.unmodifiableMap(json);
    }

    public static ChatMessage fromJson(JSONObject json) throws JSONException {
        if (json == null) throw new JSONException("chat message JSON must not be null");
        String id = json.optString("id", UUID.randomUUID().toString());
        String content = json.optString("content", "");
        String payload = null;
        if (json.has("actionPayload") && !json.isNull("actionPayload")) {
            Object rawPayload = json.opt("actionPayload");
            payload = rawPayload instanceof JSONObject
                    ? rawPayload.toString()
                    : json.optString("actionPayload", null);
        }
        List<Attachment> attachments = new ArrayList<>();
        JSONArray attachmentArray = json.optJSONArray("attachments");
        if (attachmentArray != null) {
            for (int index = 0; index < attachmentArray.length(); index++) {
                JSONObject item = attachmentArray.optJSONObject(index);
                if (item == null) continue;
                String name = item.optString("name", "附件").trim();
                if (name.isEmpty()) name = "附件";
                attachments.add(new Attachment(name,
                        item.optString("mimeType", "application/octet-stream"),
                        item.optLong("size", 0)));
            }
        }
        List<ActionItemState> actionItemStates = new ArrayList<>();
        JSONArray actionItemStateArray = json.optJSONArray("actionItemStates");
        if (actionItemStateArray != null) {
            for (int index = 0; index < actionItemStateArray.length(); index++) {
                actionItemStates.add(ActionItemState.fromStorage(
                        actionItemStateArray.optString(index, ActionItemState.PENDING.storageValue())));
            }
        }
        List<String> contextSources = new ArrayList<>();
        JSONArray contextSourcesArray = json.optJSONArray("contextSources");
        if (contextSourcesArray != null) {
            for (int index = 0; index < contextSourcesArray.length(); index++) {
                String source = contextSourcesArray.optString(index, "").trim();
                if (!source.isEmpty() && !contextSources.contains(source)) contextSources.add(source);
            }
        }
        try {
            long createdAt = json.optLong("createdAt", System.currentTimeMillis());
            return new ChatMessage(
                    id,
                    Role.fromStorage(json.optString("role", Role.ASSISTANT.storageValue())),
                    content,
                    payload,
                    ActionState.fromStorage(json.optString("actionState", ActionState.NONE.storageValue())),
                    ProposalKind.fromStorage(json.optString("proposalKind", ProposalKind.TASK_PLAN.storageValue())),
                    actionItemStates,
                    attachments,
                    contextSources,
                    json.optLong("conversationDay", startOfLocalDay(createdAt)),
                    createdAt,
                    json.optLong("updatedAt", createdAt)
            );
        } catch (IllegalArgumentException exception) {
            throw new JSONException("invalid chat message '" + id + "': " + exception.getMessage());
        }
    }

    private List<ActionItemState> normalizedItemStates(int actionCount) {
        List<ActionItemState> result = new ArrayList<>(actionCount);
        for (int index = 0; index < actionCount; index++) {
            result.add(index < actionItemStates.size()
                    ? actionItemStates.get(index) : defaultItemState(actionState));
        }
        return result;
    }

    private static ActionItemState defaultItemState(ActionState state) {
        ActionItemState terminal = terminalItemState(state);
        return terminal == null ? ActionItemState.PENDING : terminal;
    }

    private static ActionItemState terminalItemState(ActionState state) {
        if (state == ActionState.CONFIRMED || state == ActionState.EXECUTED) {
            return ActionItemState.EXECUTED;
        }
        if (state == ActionState.CANCELLED) return ActionItemState.CANCELLED;
        if (state == ActionState.FAILED) return ActionItemState.FAILED;
        return null;
    }

    private static ActionState summarizeActionState(List<ActionItemState> states) {
        boolean pending = false;
        boolean executed = false;
        boolean failed = false;
        boolean reverted = false;
        boolean handedOff = false;
        for (ActionItemState state : states) {
            pending |= state == ActionItemState.PENDING;
            executed |= state == ActionItemState.EXECUTED;
            failed |= state == ActionItemState.FAILED;
            reverted |= state == ActionItemState.REVERTED;
            handedOff |= state == ActionItemState.HANDED_OFF;
        }
        if (pending) return ActionState.PENDING_CONFIRMATION;
        if (failed) return ActionState.FAILED;
        if (executed) return ActionState.EXECUTED;
        if (handedOff) return ActionState.EXECUTED;
        if (reverted) return ActionState.CANCELLED;
        return ActionState.CANCELLED;
    }

    private static long startOfLocalDay(long value) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(value > 0 ? value : System.currentTimeMillis());
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static String normalizePayload(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String requireText(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        return normalized;
    }
}
