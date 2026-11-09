package com.hawson.timeassistant.proactive;

import com.hawson.timeassistant.domain.TaskItem;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AttentionIssueProviderTest {
    @Test public void endedScheduleIsConfirmationNotOverdue() {
        long now = 2_000_000L;
        TaskItem task = new TaskItem("刚结束的日程")
                .schedule(now - 120_000L, now - 60_000L, false);
        assertTrue(AttentionIssueProvider.requiresCompletionConfirmation(task, now));
        assertFalse(AttentionIssueProvider.requiresReflow(task, now));
    }

    @Test public void overdueTodoAndNeedsReschedulingAreAttentionIssues() {
        long now = 2_000_000L;
        TaskItem overdue = new TaskItem("过期待办").setDueAt(now - 1L);
        TaskItem reflow = new TaskItem("需重排").setStatus(TaskItem.Status.NEEDS_RESCHEDULING);
        assertTrue(AttentionIssueProvider.requiresReflow(overdue, now));
        assertTrue(AttentionIssueProvider.requiresReflow(reflow, now));
        assertFalse(AttentionIssueProvider.requiresCompletionConfirmation(reflow, now));
    }
}
