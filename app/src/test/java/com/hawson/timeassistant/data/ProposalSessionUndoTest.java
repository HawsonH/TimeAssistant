package com.hawson.timeassistant.data;

import com.hawson.timeassistant.ai.AiPlan;
import com.hawson.timeassistant.ai.TaskActionExecutor;
import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.ChatMessage;
import com.hawson.timeassistant.domain.RecurringOccurrenceOverride;
import com.hawson.timeassistant.domain.RecurringTaskGroup;
import com.hawson.timeassistant.domain.TaskItem;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ProposalSessionUndoTest {
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicBoolean failCommit = new AtomicBoolean();
    private final AppRepository repository = new AppRepository(() -> {
        if (failCommit.get()) throw new IllegalStateException("commit failed");
        commits.incrementAndGet();
    });

    @Test
    public void localActionsAndOwnStatesCommitOnceAndUndoAtomicallyMarksReverted() throws Exception {
        ChatMessage message = proposal();
        AtomicInteger events = new AtomicInteger();
        AppRepository.ChangeListener executionObserver = change -> {
            assertEquals(1, repository.getAllTasks().size());
            assertEquals(ChatMessage.ActionItemState.EXECUTED,
                    repository.getChatMessage(message.getId()).getActionItemState(0));
            assertEquals(ChatMessage.ActionItemState.PENDING,
                    repository.getChatMessage(message.getId()).getActionItemState(1));
            events.incrementAndGet();
        };
        repository.addChangeListener(executionObserver);
        int before = commits.get();
        TaskActionExecutor.ExecutionResult result = execute(message);
        assertEquals(before + 1, commits.get());
        assertEquals(2, events.get());
        assertTrue(result.canUndo(repository));
        assertNull(result.getUndoUnavailableReason(repository));
        repository.removeChangeListener(executionObserver);
        // A separate observer verifies undo never exposes restored data with EXECUTED audit state.
        AtomicInteger undoEvents = new AtomicInteger();
        AppRepository.ChangeListener observer = change -> {
            assertTrue(repository.getAllTasks().isEmpty());
            assertEquals(ChatMessage.ActionItemState.REVERTED,
                    repository.getChatMessage(message.getId()).getActionItemState(0));
            assertEquals(ChatMessage.ActionItemState.PENDING,
                    repository.getChatMessage(message.getId()).getActionItemState(1));
            undoEvents.incrementAndGet();
        };
        repository.addChangeListener(observer);
        result.undo(repository);
        assertEquals(1, undoEvents.get());
        assertEquals(before + 2, commits.get());
        assertTrue(repository.getAllTasks().isEmpty());
        assertEquals(ChatMessage.ActionItemState.REVERTED,
                repository.getChatMessage(message.getId()).getActionItemState(0));
        assertFalse(result.canUndo(repository));
        assertNotNull(result.getUndoUnavailableReason(repository));
        expectUndoRejected(result);
    }

    @Test
    public void asyncHostStateWriteInvalidatesWithoutResealing() throws Exception {
        ChatMessage message = proposal();
        TaskActionExecutor.ExecutionResult result = execute(message);
        repository.updateChatActionItemStates(message.getId(), Collections.singletonList(1),
                ChatMessage.ActionItemState.HANDED_OFF, 2);
        assertFalse(result.canUndo(repository));
        int before = commits.get();
        expectUndoRejected(result);
        assertEquals(before, commits.get());
        assertEquals(1, repository.getAllTasks().size());
        assertEquals(ChatMessage.ActionItemState.HANDED_OFF,
                repository.getChatMessage(message.getId()).getActionItemState(1));
    }

    @Test
    public void unrelatedWriteAfterAvailabilityCheckCannotBeOverwritten() throws Exception {
        TaskActionExecutor.ExecutionResult result = execute(proposal());
        assertTrue(result.canUndo(repository));
        TaskItem other = repository.upsertTask(new TaskItem("concurrent user task"));
        expectUndoRejected(result);
        assertNotNull(repository.getTask(other.getId()));
        assertEquals(2, repository.getAllTasks().size());
    }

    @Test
    public void ticketRequiresSameRepositoryAndSession() throws Exception {
        TaskActionExecutor.ExecutionResult result = execute(proposal());
        AppRepository other = new AppRepository(() -> {});
        assertFalse(result.canUndo(other));
        assertFalse(other.undoProposalSession(result.undoTicket, result.sessionId));
        assertFalse(repository.undoProposalSession(result.undoTicket, "different-session"));
        assertTrue(result.canUndo(repository));
        result.undo(repository);
    }

    @Test
    public void laterSessionPermanentlyInvalidatesEarlierTicket() throws Exception {
        TaskActionExecutor.ExecutionResult first = execute(proposal());
        TaskActionExecutor.ExecutionResult second = TaskActionExecutor.executeConfirmed(repository, createPlan());
        assertFalse(first.canUndo(repository));
        second.undo(repository);
        assertEquals(1, repository.getAllTasks().size());
        assertFalse(first.canUndo(repository));
    }

    @Test
    public void failedActionRollsBackWithoutCommitOrEvents() throws Exception {
        ChatMessage message = proposal();
        AtomicInteger events = new AtomicInteger();
        repository.addChangeListener(change -> events.incrementAndGet());
        int before = commits.get();
        AiPlan plan = AiPlan.parse("{\"message\":\"test\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"create_task\",\"title\":\"temporary\"},"
                + "{\"type\":\"delete_task\",\"taskId\":\"missing\"}]}");
        try {
            TaskActionExecutor.executeConfirmed(repository, plan, message.getId(), Arrays.asList(0, 1), 2);
            fail("expected missing task failure");
        } catch (TaskActionExecutor.ExecutionException expected) {
            assertEquals(1, expected.actionIndex);
        }
        assertTrue(repository.getAllTasks().isEmpty());
        assertEquals(ChatMessage.ActionItemState.PENDING,
                repository.getChatMessage(message.getId()).getActionItemState(0));
        assertEquals(before, commits.get());
        assertEquals(0, events.get());
    }

    @Test
    public void checkedFailureReleasesBoundaryAndKeepsEarlierTicketValid() throws Exception {
        TaskActionExecutor.ExecutionResult result = execute(proposal());
        int before = commits.get();
        try {
            repository.runProposalSession("failed-session", () -> {
                repository.upsertTask(new TaskItem("temporary"));
                throw new java.io.IOException("local failure");
            });
            fail("expected checked failure");
        } catch (java.io.IOException expected) {
            assertEquals("local failure", expected.getMessage());
        }
        assertEquals(before, commits.get());
        assertEquals(1, repository.getAllTasks().size());
        assertTrue(result.canUndo(repository));
        result.undo(repository);
    }

    @Test
    public void undoPreservesPreviouslyResolvedOtherIndexes() throws Exception {
        ChatMessage message = proposal();
        repository.updateChatActionItemStates(message.getId(), Collections.singletonList(1),
                ChatMessage.ActionItemState.HANDED_OFF, 2);
        TaskActionExecutor.ExecutionResult result = execute(message);
        long executedAt = repository.getChatMessage(message.getId()).getUpdatedAt();
        result.undo(repository);
        ChatMessage reverted = repository.getChatMessage(message.getId());
        assertTrue(reverted.getUpdatedAt() > executedAt);
        assertEquals(ChatMessage.ActionItemState.REVERTED, reverted.getActionItemState(0));
        assertEquals(ChatMessage.ActionItemState.HANDED_OFF, reverted.getActionItemState(1));
    }

    @Test
    public void failedCommitRollsBackDataAndOwnStatesWithoutEvents() throws Exception {
        ChatMessage message = proposal();
        AtomicInteger events = new AtomicInteger();
        repository.addChangeListener(change -> events.incrementAndGet());
        int before = commits.get();
        failCommit.set(true);
        try {
            execute(message);
            fail("expected commit failure");
        } catch (TaskActionExecutor.ExecutionException expected) {
            assertTrue(expected.getMessage().contains("commit failed"));
        }
        assertEquals(before, commits.get());
        assertEquals(0, events.get());
        assertTrue(repository.getAllTasks().isEmpty());
        assertEquals(ChatMessage.ActionItemState.PENDING,
                repository.getChatMessage(message.getId()).getActionItemState(0));
    }

    @Test
    public void failedUndoCommitPreservesExecutedStateAndTicketForRetry() throws Exception {
        ChatMessage message = proposal();
        TaskActionExecutor.ExecutionResult result = execute(message);
        AtomicInteger events = new AtomicInteger();
        repository.addChangeListener(change -> events.incrementAndGet());
        failCommit.set(true);
        try {
            result.undo(repository);
            fail("expected undo commit failure");
        } catch (TaskActionExecutor.ExecutionException expected) {
            assertTrue(expected.getMessage().contains("commit failed"));
        }
        assertTrue(result.canUndo(repository));
        assertEquals(1, repository.getAllTasks().size());
        assertEquals(ChatMessage.ActionItemState.EXECUTED,
                repository.getChatMessage(message.getId()).getActionItemState(0));
        assertEquals(0, events.get());
        failCommit.set(false);
        result.undo(repository);
        assertEquals(1, events.get());
    }

    @Test
    public void duplicateConfirmedExecutionDoesNotReplayActions() throws Exception {
        ChatMessage message = proposal();
        TaskActionExecutor.ExecutionResult first = execute(message);
        int before = commits.get();
        try {
            execute(message);
            fail("expected executed proposal rejection");
        } catch (TaskActionExecutor.ExecutionException expected) {
            assertTrue(expected.getMessage().contains("可重试"));
        }
        assertEquals(before, commits.get());
        assertEquals(1, repository.getAllTasks().size());
        assertTrue(first.canUndo(repository));
    }

    @Test
    public void failedItemsCanBeRetriedAndUndoMarksReverted() throws Exception {
        ChatMessage message = proposal();
        repository.updateChatActionItemStates(message.getId(), Collections.singletonList(0),
                ChatMessage.ActionItemState.FAILED, 2);
        TaskActionExecutor.ExecutionResult result = execute(message);
        assertTrue(result.canUndo(repository));
        result.undo(repository);
        assertEquals(ChatMessage.ActionItemState.REVERTED,
                repository.getChatMessage(message.getId()).getActionItemState(0));
    }

    @Test
    public void undoCreateEmitsTombstoneNewerThanExecutedPeerVersion() throws Exception {
        TaskActionExecutor.ExecutionResult result = TaskActionExecutor.executeConfirmed(repository, createPlan());
        String id = repository.getAllTasks().get(0).getId();
        long executedVersion = repository.getTask(id).getUpdatedAt();
        result.undo(repository);
        assertTrue(taskTombstones().get(id) > executedVersion);
        assertNull(repository.getTask(id));
    }

    @Test
    public void undoDeleteAndEditAdvanceVersionsBeyondExecutedPeerVersions() throws Exception {
        TaskItem original = repository.upsertTask(new TaskItem("original"));
        AiPlan delete = AiPlan.parse("{\"message\":\"test\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"delete_task\",\"taskId\":\"" + original.getId() + "\"}]}");
        TaskActionExecutor.ExecutionResult deleted = TaskActionExecutor.executeConfirmed(repository, delete);
        long deletedVersion = taskTombstones().get(original.getId());
        deleted.undo(repository);
        assertTrue(repository.getTask(original.getId()).getUpdatedAt() > deletedVersion);
        assertFalse(taskTombstones().containsKey(original.getId()));
        assertEquals("original", repository.getTask(original.getId()).getTitle());

        AiPlan edit = AiPlan.parse("{\"message\":\"test\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"update_task\",\"taskId\":\"" + original.getId()
                + "\",\"changes\":{\"title\":\"edited\"}}]}");
        TaskActionExecutor.ExecutionResult edited = TaskActionExecutor.executeConfirmed(repository, edit);
        long editedVersion = repository.getTask(original.getId()).getUpdatedAt();
        edited.undo(repository);
        assertTrue(repository.getTask(original.getId()).getUpdatedAt() > editedVersion);
        assertEquals("original", repository.getTask(original.getId()).getTitle());
    }

    @Test
    public void realWriteLockExcludesConcurrentWriterUntilSessionCommits() throws Exception {
        CountDownLatch localWritten = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FutureTask<AppRepository.ProposalSessionResult<Void>> session = new FutureTask<>(() ->
                repository.runProposalSession("session", () -> {
                    repository.upsertTask(new TaskItem("local"));
                    localWritten.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                    return null;
                }));
        new Thread(session).start();
        assertTrue(localWritten.await(5, TimeUnit.SECONDS));
        FutureTask<TaskItem> writer = new FutureTask<>(() -> repository.upsertTask(new TaskItem("other")));
        new Thread(writer).start();
        try {
            writer.get(100, TimeUnit.MILLISECONDS);
            fail("writer entered local transaction");
        } catch (TimeoutException expected) {
            assertEquals(0, commits.get());
        } finally {
            release.countDown();
        }
        AppRepository.ProposalSessionResult<Void> result = session.get(5, TimeUnit.SECONDS);
        TaskItem other = writer.get(5, TimeUnit.SECONDS);
        assertFalse(repository.undoProposalSession(result.undoTicket, "session"));
        assertNotNull(repository.getTask(other.getId()));
        assertEquals(2, repository.getAllTasks().size());
    }

    @Test
    public void listenerRunsOutsideWriteLockAndCanInvalidateTicketBeforeReturn() throws Exception {
        AtomicBoolean once = new AtomicBoolean();
        repository.addChangeListener(change -> {
            if (!once.compareAndSet(false, true)) return;
            FutureTask<TaskItem> writer = new FutureTask<>(() -> repository.upsertTask(new TaskItem("listener")));
            new Thread(writer).start();
            try {
                writer.get(5, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError("listener held repository write lock", failure);
            }
        });
        TaskActionExecutor.ExecutionResult result = TaskActionExecutor.executeConfirmed(repository, createPlan());
        assertFalse(result.canUndo(repository));
        assertEquals(2, repository.getAllTasks().size());
    }

    @Test
    public void credentialsAreAbsentFromTicketAndPreservedOnUndo() throws Exception {
        repository.saveSettings(repository.getSettings().setApiKey("local-secret"));
        TaskActionExecutor.ExecutionResult result = execute(proposal());
        java.lang.reflect.Field field = AppRepository.UndoTicket.class.getDeclaredField("before");
        field.setAccessible(true);
        Object snapshot = field.get(result.undoTicket);
        java.lang.reflect.Field settings = snapshot.getClass().getDeclaredField("settings");
        settings.setAccessible(true);
        assertEquals("", ((AppSettings) settings.get(snapshot)).getApiKey());
        result.undo(repository);
        assertEquals("local-secret", repository.getSettings().getApiKey());
    }

    @Test
    public void emptyPlanDoesNotIssueTicketOrWrite() throws Exception {
        TaskActionExecutor.ExecutionResult result = TaskActionExecutor.executeConfirmed(repository,
                AiPlan.parse("{\"message\":\"test\",\"needsClarification\":false,\"actions\":[]}"));
        assertNull(result.undoTicket);
        assertFalse(result.canUndo(repository));
        assertEquals(0, commits.get());
    }

    @Test
    public void undoRetainsDeletionsOfItemsCreatedAndDeletedInsideSession() throws Exception {
        TaskItem task = new TaskItem("temporary");
        RecurringTaskGroup group = new RecurringTaskGroup("temporary series", 1_000L,
                60_000L, RecurringTaskGroup.Unit.DAYS, 1);
        RecurringOccurrenceOverride override = RecurringOccurrenceOverride.skipped(group.getId(), 1_000L);
        AppRepository.ProposalSessionResult<Void> session = repository.runProposalSession("transient", () -> {
            repository.upsertTask(task);
            repository.deleteTask(task.getId());
            repository.upsertRecurringGroup(group);
            repository.upsertRecurringOverride(override);
            repository.deleteRecurringGroup(group.getId());
            return null;
        });
        long taskDeletion = tombstones("taskTombstones").get(task.getId());
        long groupDeletion = tombstones("recurringGroupTombstones").get(group.getId());
        long overrideDeletion = tombstones("recurringOverrideTombstones").get(override.getId());
        assertTrue(repository.undoProposalSession(session.undoTicket, "transient"));
        assertEquals(Long.valueOf(taskDeletion), tombstones("taskTombstones").get(task.getId()));
        assertEquals(Long.valueOf(groupDeletion), tombstones("recurringGroupTombstones").get(group.getId()));
        assertEquals(Long.valueOf(overrideDeletion), tombstones("recurringOverrideTombstones").get(override.getId()));
        assertTrue(repository.getAllTasks().isEmpty());
        assertTrue(repository.getAllRecurringGroups().isEmpty());
        assertTrue(repository.getAllRecurringOverrides().isEmpty());
    }

    @Test
    public void concurrentUndoConsumesTicketOnceAndCannotClobberListenerWrite() throws Exception {
        TaskActionExecutor.ExecutionResult result = TaskActionExecutor.executeConfirmed(repository, createPlan());
        AtomicBoolean once = new AtomicBoolean();
        TaskItem later = new TaskItem("after undo");
        repository.addChangeListener(change -> {
            if (once.compareAndSet(false, true)) repository.upsertTask(later);
        });
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> undo = () -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return repository.undoProposalSession(result.undoTicket, result.sessionId);
        };
        FutureTask<Boolean> first = new FutureTask<>(undo);
        FutureTask<Boolean> second = new FutureTask<>(undo);
        new Thread(first).start();
        new Thread(second).start();
        start.countDown();
        assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        assertNotNull(repository.getTask(later.getId()));
        assertEquals(1, repository.getAllTasks().size());
        assertEquals(3, commits.get());
    }

    @Test
    public void rejectedNestedSessionDoesNotDiscardOuterChanges() throws Exception {
        AppRepository.ProposalSessionResult<Void> session = repository.runProposalSession("outer", () -> {
            repository.upsertTask(new TaskItem("before nested"));
            try {
                repository.runProposalSession("inner", () -> {
                    fail("nested operation must not run");
                    return null;
                });
                fail("nested session must be rejected");
            } catch (IllegalStateException expected) {
                assertEquals("nested proposal session", expected.getMessage());
            }
            repository.upsertTask(new TaskItem("after nested"));
            return null;
        });
        assertEquals(1, commits.get());
        assertEquals(2, repository.getAllTasks().size());
        assertTrue(repository.undoProposalSession(session.undoTicket, "outer"));
        assertTrue(repository.getAllTasks().isEmpty());
    }

    @Test
    public void interruptedIoWaitObservesOutcomeAndRestoresInterruptFlag() throws Exception {
        java.lang.reflect.Method io = AppRepository.class.getDeclaredMethod(
                "executeOnIoBlocking", java.util.concurrent.Callable.class);
        io.setAccessible(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interruptionSent = new CountDownLatch(1);
        AtomicBoolean interruptedAfterReturn = new AtomicBoolean();
        FutureTask<Integer> caller = new FutureTask<>(() -> {
            try {
                return (Integer) io.invoke(repository, (java.util.concurrent.Callable<Integer>) () -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    assertTrue(interruptionSent.await(5, TimeUnit.SECONDS));
                    return 7;
                });
            } finally {
                interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
            }
        });
        Thread thread = new Thread(caller);
        thread.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            thread.interrupt();
            interruptionSent.countDown();
            try {
                caller.get(100, TimeUnit.MILLISECONDS);
                fail("I/O outcome is still unknown");
            } catch (TimeoutException expected) {
                // The repository must keep waiting despite caller interruption.
            }
        } finally {
            release.countDown();
        }
        assertEquals(Integer.valueOf(7), caller.get(5, TimeUnit.SECONDS));
        assertTrue(interruptedAfterReturn.get());
    }

    @Test
    public void commandWaitingForWriteLockCannotBlockPersistenceQueue() throws Exception {
        java.lang.reflect.Method io = AppRepository.class.getDeclaredMethod(
                "executeOnIoBlocking", java.util.concurrent.Callable.class);
        io.setAccessible(true);
        CountDownLatch commandEntered = new CountDownLatch(1);
        CountDownLatch commandFinished = new CountDownLatch(1);
        FutureTask<Void> session = new FutureTask<>(() -> {
            repository.runProposalSession("queue-order", () -> {
                repository.executeAsync(repo -> {
                    commandEntered.countDown();
                    repo.upsertTask(new TaskItem("queued command"));
                }, error -> commandFinished.countDown());
                assertTrue(commandEntered.await(5, TimeUnit.SECONDS));
                assertEquals(7, io.invoke(repository, (java.util.concurrent.Callable<Integer>) () -> 7));
                repository.upsertTask(new TaskItem("local"));
                return null;
            });
            return null;
        });
        Thread thread = new Thread(session);
        thread.setDaemon(true);
        thread.start();
        session.get(5, TimeUnit.SECONDS);
        assertTrue(commandFinished.await(5, TimeUnit.SECONDS));
        assertEquals(2, repository.getAllTasks().size());
        assertEquals(2, commits.get());
    }

    private ChatMessage proposal() {
        return repository.addChatMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, "proposal",
                "{}", ChatMessage.ActionState.PENDING_CONFIRMATION));
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Long> taskTombstones() throws Exception {
        return tombstones("taskTombstones");
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Long> tombstones(String name) throws Exception {
        java.lang.reflect.Field field = AppRepository.class.getDeclaredField(name);
        field.setAccessible(true);
        return new java.util.LinkedHashMap<>((java.util.Map<String, Long>) field.get(repository));
    }

    private TaskActionExecutor.ExecutionResult execute(ChatMessage message) throws Exception {
        return TaskActionExecutor.executeConfirmed(repository, createPlan(), message.getId(),
                Collections.singletonList(0), 2);
    }

    private static AiPlan createPlan() throws Exception {
        return AiPlan.parse("{\"message\":\"test\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"create_task\",\"title\":\"local\"}]}");
    }

    private void expectUndoRejected(TaskActionExecutor.ExecutionResult result) throws Exception {
        try {
            result.undo(repository);
            fail("expected stale/consumed undo rejection");
        } catch (TaskActionExecutor.ExecutionException expected) {
            assertTrue(expected.getMessage().contains("无法撤销"));
        }
    }
}
