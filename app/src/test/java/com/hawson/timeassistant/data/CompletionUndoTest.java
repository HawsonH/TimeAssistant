package com.hawson.timeassistant.data;

import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.ui.schedule.AttentionCompletionBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class CompletionUndoTest {
    private final AppRepository repository = new AppRepository(() -> { });

    @Test public void completionCommitsImmediatelyAndUndoRestoresOriginalStatusOnlyOnce() {
        TaskItem task = repository.upsertTask(new TaskItem("test").setStatus(TaskItem.Status.NEEDS_RESCHEDULING));
        CompletionUndoTicket ticket = repository.completeTaskForUndo(task.getId(), task.getUpdatedAt());
        assertNotNull(ticket); assertEquals(TaskItem.Status.COMPLETED, repository.getTask(task.getId()).getStatus());
        repository.upsertTask(new TaskItem("unrelated"));
        assertTrue(repository.undoTaskCompletion(ticket));
        TaskItem restored = repository.getTask(task.getId());
        assertEquals(task.getStatus(), restored.getStatus());
        assertEquals(task.getStatusChangedAt(), restored.getStatusChangedAt());
        assertEquals(task.getCompletedAt(), restored.getCompletedAt());
        assertTrue(restored.getUpdatedAt() > task.getUpdatedAt());
        assertFalse(repository.undoTaskCompletion(ticket));
    }

    @Test public void editDeletionAndForeignRepositoryCannotBeOverwritten() {
        TaskItem task = repository.upsertTask(new TaskItem("test"));
        CompletionUndoTicket ticket = repository.completeTaskForUndo(task.getId(), task.getUpdatedAt());
        repository.upsertTask(repository.getTask(task.getId()).setDescription("concurrent"));
        assertFalse(repository.undoTaskCompletion(ticket));
        assertEquals("concurrent", repository.getTask(task.getId()).getDescription());
        assertFalse(new AppRepository(() -> { }).undoTaskCompletion(ticket));
        repository.deleteTask(task.getId());
        assertFalse(repository.undoTaskCompletion(ticket));
        assertNull(repository.getTask(task.getId()));
    }

    @Test public void simultaneousUndoCanSucceedOnlyOnce() throws Exception {
        TaskItem task = repository.upsertTask(new TaskItem("test"));
        CompletionUndoTicket ticket = repository.completeTaskForUndo(task.getId(), task.getUpdatedAt());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        Runnable undo = () -> {
            try { start.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
            if (repository.undoTaskCompletion(ticket)) success.incrementAndGet();
        };
        Thread first = new Thread(undo), second = new Thread(undo);
        first.start(); second.start(); start.countDown(); first.join(2_000); second.join(2_000);
        assertFalse(first.isAlive()); assertFalse(second.isAlive()); assertEquals(1, success.get());
    }

    @Test public void bufferHasIndependentMonotonicDeadlinesAndOriginalPositions() {
        TaskItem first = repository.upsertTask(new TaskItem("first"));
        TaskItem second = repository.upsertTask(new TaskItem("second"));
        AttentionCompletionBuffer buffer = new AttentionCompletionBuffer();
        CompletionUndoTicket one = repository.completeTaskForUndo(first.getId(), first.getUpdatedAt());
        buffer.retain(one, "group", 0, 100, 5_000);
        assertEquals(1, buffer.merge("group", Collections.emptyList(), repository.getAllTasks(), 5_099).size());
        CompletionUndoTicket two = repository.completeTaskForUndo(second.getId(), second.getUpdatedAt());
        buffer.retain(two, "group", 1, 1_100, 5_000);
        assertEquals(Arrays.asList(first.getId(), second.getId()), Arrays.asList(
                buffer.merge("group", Collections.emptyList(), repository.getAllTasks(), 5_000).get(0).getId(),
                buffer.merge("group", Collections.emptyList(), repository.getAllTasks(), 5_000).get(1).getId()));
        assertNull(buffer.get(first.getId(), 5_100)); assertNotNull(buffer.get(second.getId(), 5_100));
        assertEquals(1, buffer.merge("group", Collections.emptyList(), repository.getAllTasks(), 5_100).size());
        assertTrue(buffer.entries(6_100).isEmpty());
    }

    @Test public void staleCompletionCannotBeRetainedAndFailedCommitKeepsOriginalTask() {
        TaskItem task = repository.upsertTask(new TaskItem("test"));
        assertNull(repository.completeTaskForUndo(task.getId(), task.getUpdatedAt() - 1));
        CompletionUndoTicket ticket = repository.completeTaskForUndo(task.getId(), task.getUpdatedAt());
        AttentionCompletionBuffer buffer = new AttentionCompletionBuffer(); buffer.retain(ticket, "g", 0, 0, 5_000);
        repository.upsertTask(repository.getTask(task.getId()).setTitle("changed"));
        assertTrue(buffer.merge("g", Collections.emptyList(), repository.getAllTasks(), 1).isEmpty());
    }
}
