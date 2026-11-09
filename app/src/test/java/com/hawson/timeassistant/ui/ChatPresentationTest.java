package com.hawson.timeassistant.ui;

import com.hawson.timeassistant.domain.ChatMessage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ChatPresentationTest {
    private ChatMessage pending() {
        return new ChatMessage(ChatMessage.Role.ASSISTANT, "建议", "{}",
                ChatMessage.ActionState.PENDING_CONFIRMATION);
    }

    @Test public void partialOutcomeKeepsOnlyCoordinatorEligibleRetryIndexes() {
        ChatMessage message = pending()
                .withActionItemStates(Collections.singletonList(0), ChatMessage.ActionItemState.EXECUTED, 4)
                .withActionItemStates(Collections.singletonList(1), ChatMessage.ActionItemState.FAILED, 4)
                .withActionItemStates(Collections.singletonList(2), ChatMessage.ActionItemState.CANCELLED, 4);
        ProposalPresentation model = new ProposalPresentation(message, 4, Arrays.asList(0, 1, 2, 3),
                false, "部分完成，外部操作未完成", true, "", false, false);
        assertEquals(Arrays.asList(1, 3), model.pendingIndexes);
        assertEquals(Collections.singletonList(1), model.retryIndexes);
        assertEquals("确认/重试 2 项", model.confirmationLabel(Arrays.asList(1, 3)));
        assertEquals("重试 1 项", model.confirmationLabel(Collections.singletonList(1)));
        assertEquals("确认 1 项", model.confirmationLabel(Collections.singletonList(3)));
        assertEquals(ProposalPresentation.Outcome.PARTIAL, model.outcome);
        assertEquals("部分完成，外部操作未完成", model.outcomeText);
        assertEquals("执行失败", model.rowStatus(1));
        assertTrue(model.undoAvailable);
        ProposalPresentation excludedFailure = new ProposalPresentation(message, 4,
                Collections.singletonList(3), false, null, false, "", false, false);
        assertEquals(Collections.singletonList(3), excludedFailure.pendingIndexes);
        assertTrue(excludedFailure.retryIndexes.isEmpty());
    }

    @Test public void historicalIgnoresAllSessionCapabilitiesAndSummaries() {
        ChatMessage message = pending()
                .withActionItemStates(Collections.singletonList(0), ChatMessage.ActionItemState.REVERTED, 2)
                .withActionItemStates(Collections.singletonList(1), ChatMessage.ActionItemState.HANDED_OFF, 2);
        ProposalPresentation model = new ProposalPresentation(message, 2, Collections.emptyList(),
                true, "stale session summary", true, "stale reason", true, true);
        assertEquals(ProposalPresentation.Outcome.PARTIAL, model.outcome);
        assertTrue(model.outcomeText.contains("已撤销 1 项"));
        assertTrue(model.outcomeText.contains("已提交，请核对结果 1 项"));
        assertFalse(model.outcomeText.contains("stale"));
        assertFalse(model.undoAvailable);
        assertFalse(model.busy);
        assertFalse(model.focusPrepared);
        assertEquals("查看安排", model.viewLabel());
        assertTrue(model.viewAvailable);
    }

    @Test public void persistedSuccessDoesNotGrantUndoAndPreservesInvalidReason() {
        ChatMessage message = pending().withActionState(ChatMessage.ActionState.EXECUTED);
        ProposalPresentation restored = ProposalPresentation.from(message, 2, false);
        assertEquals(ProposalPresentation.Outcome.SUCCEEDED, restored.outcome);
        assertFalse(restored.undoAvailable);
        assertEquals("已执行 2 项", restored.outcomeText);
        ProposalPresentation invalid = new ProposalPresentation(message, 2, Collections.emptyList(),
                false, null, false, "安排已被其他操作修改", false, false);
        assertEquals("安排已被其他操作修改", invalid.undoUnavailableReason);
        assertTrue(invalid.viewAvailable);
    }

    @Test public void busyBlocksUndoButRetainsFocusDestination() {
        ChatMessage message = pending().withActionState(ChatMessage.ActionState.EXECUTED);
        ProposalPresentation model = new ProposalPresentation(message, 1, Collections.emptyList(),
                false, "专注已准备", true, "", true, true);
        assertTrue(model.busy);
        assertFalse(model.undoAvailable);
        assertEquals("处理中…", model.status);
        assertEquals("进入专注", model.viewLabel());
        assertEquals("操作进行中，暂不可撤销", model.undoUnavailableReason);
    }

    @Test public void legacyTerminalMessagesAndAllPersistedItemStatesHaveOutcomes() {
        assertEquals(ProposalPresentation.Outcome.CANCELLED,
                ProposalPresentation.from(pending().withActionState(ChatMessage.ActionState.CANCELLED), 2, true).outcome);
        assertEquals(ProposalPresentation.Outcome.FAILED,
                ProposalPresentation.from(pending().withActionState(ChatMessage.ActionState.FAILED), 2, true).outcome);
        for (ChatMessage.ActionItemState state : ChatMessage.ActionItemState.values()) {
            ChatMessage message = pending().withActionItemStates(Collections.singletonList(0), state, 1);
            ProposalPresentation model = ProposalPresentation.from(message, 1, true);
            assertNotNull(model.rowStatus(0));
            if (state != ChatMessage.ActionItemState.PENDING) assertFalse(model.outcomeText.isEmpty());
            assertFalse(model.undoAvailable);
        }
        assertEquals(ProposalPresentation.Outcome.EMPTY, ProposalPresentation.from(pending(), 0, false).outcome);
    }

    @Test public void handoffNeverImpliesLocalUndo() {
        ChatMessage message = pending().withActionItemStates(Collections.singletonList(0),
                ChatMessage.ActionItemState.HANDED_OFF, 1);
        ProposalPresentation model = new ProposalPresentation(message, 1, Collections.emptyList(),
                false, null, true, "", false, false);
        assertEquals(ProposalPresentation.Outcome.HANDED_OFF, model.outcome);
        assertFalse(model.undoAvailable);
        assertTrue(model.viewAvailable);
    }

    @Test public void pendingIndexesAreAnImmutableOrderedSnapshot() {
        List<Integer> pending = new ArrayList<>(Arrays.asList(2, 0, 0, 8, -1));
        ProposalPresentation model = new ProposalPresentation(pending(), 3, pending, false,
                null, false, "", false, false);
        pending.clear();
        assertEquals(Arrays.asList(0, 2), model.pendingIndexes);
        try {
            model.pendingIndexes.add(1);
            fail("presentation must be immutable");
        } catch (UnsupportedOperationException expected) { }
        assertEquals(ProposalPresentation.Outcome.PENDING, model.outcome);
        assertEquals("", model.outcomeText);
    }
}
