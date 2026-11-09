package com.hawson.timeassistant;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.TaskItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

@RunWith(AndroidJUnit4.class)
public class AppRepositoryFocusCompletionTest {
    private AppRepository repository;

    @Before public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        repository = AppRepository.get(context);
        repository.clearAll();
    }

    @After public void tearDown() {
        repository.clearAll();
    }

    @Test public void applicationUsesOneWritableRepositoryInstance() {
        Context context = ApplicationProvider.getApplicationContext();
        assertSame(repository, AppRepository.get(context));
    }

    @Test public void duplicateNaturalFocusCompletionCreatesOneRecord() {
        long now = System.currentTimeMillis();
        TaskItem task = repository.upsertTask(new TaskItem("写作任务"));
        FocusState running = FocusState.ready(task.getId(), FocusState.Mode.FOCUS, 1_000L, 1)
                .startOrResume(now - 2_000L);
        repository.setCurrentFocus(running);

        AppRepository.FocusCompletion first = repository.completeCurrentFocusNaturally(now);
        AppRepository.FocusCompletion duplicate = repository.completeCurrentFocusNaturally(now + 1L);

        assertTrue(first.wasSettledNow());
        assertEquals(AppRepository.FocusCompletionKind.FOCUS_AWAITING_FEEDBACK,
                first.getKind());
        assertEquals(AppRepository.FocusCompletionKind.ALREADY_SETTLED, duplicate.getKind());
        assertEquals(1, repository.getFocusHistory().size());
        assertEquals("写作任务",
                repository.getFocusHistory().get(0).getTaskTitleSnapshot());
        assertEquals(1, repository.getTask(task.getId()).getActualDurationMinutes());
        assertTrue(repository.getCurrentFocus().isNaturalCompletionRecorded());

        String recordId = repository.getFocusHistory().get(0).getId();
        repository.deleteFocusRecord(recordId);
        AppRepository.FocusCompletion afterDeletion =
                repository.completeCurrentFocusNaturally(now + 2L);
        assertEquals(AppRepository.FocusCompletionKind.ALREADY_SETTLED,
                afterDeletion.getKind());
        assertEquals(0, repository.getFocusHistory().size());
        assertEquals(1, repository.getTask(task.getId()).getActualDurationMinutes());
    }

    @Test public void completedBreakCanAdvanceAndStartNextPomodoro() {
        AppSettings settings = repository.getSettings()
                .setFocusDurations(25, 5, 15)
                .setFocusAutomation(true, false, true, false);
        repository.saveSettings(settings);
        long now = System.currentTimeMillis();
        repository.setCurrentFocus(FocusState.ready(null, FocusState.Mode.SHORT_BREAK,
                1_000L, 2).startOrResume(now - 2_000L));

        AppRepository.FocusCompletion completion = repository.completeCurrentFocusNaturally(now);

        assertEquals(AppRepository.FocusCompletionKind.BREAK_RUNNING, completion.getKind());
        FocusState next = repository.getCurrentFocus();
        assertEquals(FocusState.Mode.FOCUS, next.getMode());
        assertEquals(FocusState.State.RUNNING, next.getState());
        assertEquals(3, next.getPomodoroIndex());
    }

    @Test public void readyTimerCannotBeSettledWithoutStarting() {
        long now = System.currentTimeMillis();
        FocusState ready = FocusState.ready(null, FocusState.Mode.FOCUS, 60_000L, 1);
        repository.setCurrentFocus(ready);
        assertEquals(AppRepository.FocusCompletionKind.NOT_DUE,
                repository.completeCurrentFocusNaturally(now).getKind());
        assertEquals(AppRepository.FocusCompletionKind.NOT_DUE,
                repository.endCurrentFocus(ready.getSessionId(), now, false, "").getKind());
        assertEquals(FocusState.State.READY, repository.getCurrentFocus().getState());
        assertEquals(0, repository.getFocusHistory().size());
    }

    @Test public void legacyUnstartedPausedSnapshotMigratesWithoutChangingSession() throws Exception {
        FocusState ready = FocusState.ready(null, FocusState.Mode.FOCUS, 60_000L, 1);
        org.json.JSONObject legacy = ready.toJson().put("state", "paused");
        FocusState restored = FocusState.fromJson(legacy);
        assertEquals(FocusState.State.READY, restored.getState());
        assertEquals(ready.getSessionId(), restored.getSessionId());
        FocusState paused = ready.startOrResume(100_000L).pause(110_000L);
        assertEquals(FocusState.State.PAUSED, FocusState.fromJson(paused.toJson()).getState());
    }

    @Test public void manualEndOfPausedTimerIsAtomicAndExcludesPauseTime() {
        long now = System.currentTimeMillis();
        TaskItem task = repository.upsertTask(new TaskItem("手动结束"));
        FocusState paused = FocusState.ready(task.getId(), FocusState.Mode.FOCUS, 60_000L, 1)
                .startOrResume(now - 30_000L).pause(now - 20_000L);
        repository.setCurrentFocus(paused);
        AppRepository.FocusCompletion ended =
                repository.endCurrentFocus(paused.getSessionId(), now, true, "电话");
        assertEquals(AppRepository.FocusCompletionKind.FOCUS_ENDED, ended.getKind());
        assertEquals(FocusState.State.IDLE, repository.getCurrentFocus().getState());
        assertEquals(10_000L, ended.getRecord().getDurationMillis());
        assertEquals("interrupted", ended.getRecord().getOutcome());
        assertEquals(1, repository.getTask(task.getId()).getActualDurationMinutes());
        repository.endCurrentFocus(paused.getSessionId(), now + 1L, true, "重复");
        repository.completeCurrentFocusNaturally(now + 2L);
        assertEquals(1, repository.getFocusHistory().size());
        assertEquals(1, repository.getTask(task.getId()).getActualDurationMinutes());
    }

    @Test public void manualEndAtDeadlineUsesNaturalSettlementExactlyOnce() {
        long now = System.currentTimeMillis();
        FocusState running = FocusState.ready(null, FocusState.Mode.FOCUS, 1_000L, 1)
                .startOrResume(now - 2_000L);
        repository.setCurrentFocus(running);
        AppRepository.FocusCompletion end =
                repository.endCurrentFocus(running.getSessionId(), now, true, "不应覆盖到时结果");
        assertEquals(AppRepository.FocusCompletionKind.FOCUS_AWAITING_FEEDBACK, end.getKind());
        assertEquals("completed", end.getRecord().getOutcome());
        assertEquals(1_000L, end.getRecord().getDurationMillis());
        repository.completeCurrentFocusNaturally(now + 1);
        assertEquals(1, repository.getFocusHistory().size());
    }

    @Test public void staleEndAndCancelCannotReplaceNewSession() {
        long now = System.currentTimeMillis();
        FocusState previous = FocusState.ready(null, FocusState.Mode.FOCUS, 60_000L, 1);
        FocusState next = FocusState.ready(null, FocusState.Mode.FOCUS, 60_000L, 2)
                .startOrResume(now);
        repository.setCurrentFocus(next);
        repository.endCurrentFocus(previous.getSessionId(), now + 1, false, "");
        assertFalse(repository.cancelCurrentFocus(previous.getSessionId(), now + 2));
        assertEquals(next.getSessionId(), repository.getCurrentFocus().getSessionId());
        assertEquals(FocusState.State.RUNNING, repository.getCurrentFocus().getState());
        assertEquals(0, repository.getFocusHistory().size());
    }

    @Test public void cancelLeavesNoRecordAndCreatesNewReadySession() {
        long now = System.currentTimeMillis();
        FocusState running = FocusState.ready(null, FocusState.Mode.FOCUS, 60_000L, 1)
                .startOrResume(now - 1_000L);
        repository.setCurrentFocus(running);
        assertTrue(repository.cancelCurrentFocus(running.getSessionId(), now));
        assertEquals(FocusState.State.READY, repository.getCurrentFocus().getState());
        assertFalse(running.getSessionId().equals(repository.getCurrentFocus().getSessionId()));
        assertEquals(0, repository.getFocusHistory().size());
    }

    @Test public void endingBreakNeverCreatesFocusHistory() {
        long now = System.currentTimeMillis();
        FocusState running = FocusState.ready(null, FocusState.Mode.SHORT_BREAK, 60_000L, 0)
                .startOrResume(now - 1_000L);
        repository.setCurrentFocus(running);
        repository.endCurrentFocus(running.getSessionId(), now, false, "");
        assertEquals(FocusState.State.IDLE, repository.getCurrentFocus().getState());
        assertEquals(0, repository.getFocusHistory().size());
    }
}
