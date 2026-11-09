package com.hawson.timeassistant.ui;

import com.hawson.timeassistant.domain.ChatMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.List;

/** Immutable bubble state. Session capabilities are never inferred from persisted success. */
public final class ProposalPresentation {
    public enum Outcome { PENDING, SUCCEEDED, PARTIAL, FAILED, CANCELLED, REVERTED, HANDED_OFF, EMPTY }

    public final String messageId;
    public final int actionCount;
    public final List<Integer> pendingIndexes;
    public final List<Integer> retryIndexes;
    public final boolean historical;
    public final boolean busy;
    public final boolean focusPrepared;
    public final Outcome outcome;
    public final String status;
    public final String outcomeText;
    public final boolean undoAvailable;
    public final String undoUnavailableReason;
    public final boolean viewAvailable;
    private final List<String> rowStatuses;

    public static ProposalPresentation from(ChatMessage message, int count, boolean historical) {
        List<Integer> pending = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ChatMessage.ActionItemState item = message.getActionItemState(index);
            if (item == ChatMessage.ActionItemState.PENDING || item == ChatMessage.ActionItemState.FAILED) pending.add(index);
        }
        return new ProposalPresentation(message, count, pending, historical, null, false, "", false, false);
    }

    public ProposalPresentation(ChatMessage message, int count, List<Integer> pending,
                                boolean historical, String sessionOutcome, boolean canUndo,
                                String undoReason, boolean busy, boolean focusPrepared) {
        if (count < 0) throw new IllegalArgumentException("action count must not be negative");
        this.messageId = message.getId();
        this.actionCount = count;
        this.historical = historical;
        this.busy = !historical && busy;
        this.focusPrepared = !historical && focusPrepared;
        List<Integer> unresolved = new ArrayList<>();
        List<Integer> retries = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        int[] totals = new int[ChatMessage.ActionItemState.values().length];
        for (int index = 0; index < count; index++) {
            ChatMessage.ActionItemState item = message.getActionItemState(index);
            totals[item.ordinal()]++;
            statuses.add(label(item));
            // The coordinator owns retry eligibility; successful steps can never be resubmitted.
            if (pending.contains(index) && (item == ChatMessage.ActionItemState.PENDING
                    || item == ChatMessage.ActionItemState.FAILED)) {
                unresolved.add(index);
                if (item == ChatMessage.ActionItemState.FAILED) retries.add(index);
            }
        }
        this.pendingIndexes = Collections.unmodifiableList(unresolved);
        this.retryIndexes = Collections.unmodifiableList(retries);
        this.rowStatuses = Collections.unmodifiableList(statuses);
        this.outcome = outcome(totals, count);
        this.status = this.busy ? "处理中…" : status(outcome);
        String persisted = summary(totals);
        this.outcomeText = !historical && sessionOutcome != null && !sessionOutcome.trim().isEmpty()
                ? sessionOutcome : outcome == Outcome.PENDING || outcome == Outcome.EMPTY ? "" : persisted;
        boolean executed = totals[ChatMessage.ActionItemState.EXECUTED.ordinal()] > 0;
        this.undoAvailable = !historical && !this.busy && canUndo && executed;
        this.undoUnavailableReason = historical ? "历史记录只读，无法撤销"
                : this.busy ? "操作进行中，暂不可撤销"
                : this.undoAvailable ? "" : undoReason == null || undoReason.trim().isEmpty()
                ? executed ? "当前会话没有可用的撤销记录" : "没有可撤销的本地修改" : undoReason;
        this.viewAvailable = executed || totals[ChatMessage.ActionItemState.HANDED_OFF.ordinal()] > 0
                || this.focusPrepared;
    }

    public String rowStatus(int index) { return rowStatuses.get(index); }

    public String viewLabel() { return focusPrepared ? "进入专注" : "查看安排"; }

    public String confirmationLabel(Collection<Integer> selected) {
        int retries = 0;
        int count = 0;
        for (Integer index : pendingIndexes) {
            if (!selected.contains(index)) continue;
            count++;
            if (retryIndexes.contains(index)) retries++;
        }
        return (retries == 0 ? "确认 " : retries == count ? "重试 " : "确认/重试 ") + count + " 项";
    }

    private static Outcome outcome(int[] totals, int count) {
        if (count == 0) return Outcome.EMPTY;
        if (totals[ChatMessage.ActionItemState.PENDING.ordinal()] == count) return Outcome.PENDING;
        for (ChatMessage.ActionItemState item : ChatMessage.ActionItemState.values()) {
            if (totals[item.ordinal()] != count) continue;
            switch (item) {
                case EXECUTED: return Outcome.SUCCEEDED;
                case HANDED_OFF: return Outcome.HANDED_OFF;
                case CANCELLED: return Outcome.CANCELLED;
                case REVERTED: return Outcome.REVERTED;
                case FAILED: return Outcome.FAILED;
                default: return Outcome.PENDING;
            }
        }
        return Outcome.PARTIAL;
    }

    private static String summary(int[] totals) {
        StringBuilder text = new StringBuilder();
        for (ChatMessage.ActionItemState item : ChatMessage.ActionItemState.values()) {
            int count = totals[item.ordinal()];
            if (count == 0) continue;
            if (text.length() > 0) text.append("；");
            text.append(label(item)).append(' ').append(count).append(" 项");
        }
        return text.toString();
    }

    private static String status(Outcome outcome) {
        switch (outcome) {
            case SUCCEEDED: return "已执行";
            case PARTIAL: return "部分处理";
            case FAILED: return "执行失败";
            case CANCELLED: return "已取消";
            case REVERTED: return "已撤销";
            case HANDED_OFF: return "已提交，请核对结果";
            case EMPTY: return "暂无安排";
            default: return "待确认";
        }
    }

    private static String label(ChatMessage.ActionItemState item) {
        switch (item) {
            case EXECUTED: return "已执行";
            case HANDED_OFF: return "已提交，请核对结果";
            case CANCELLED: return "已取消";
            case REVERTED: return "已撤销";
            case FAILED: return "执行失败";
            default: return "待确认";
        }
    }
}
