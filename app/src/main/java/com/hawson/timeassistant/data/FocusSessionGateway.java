package com.hawson.timeassistant.data;

import android.os.Looper;

import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.TaskItem;

/** Background-only gateway used by the foreground service and alarm receiver. */
public final class FocusSessionGateway {
    private final AppRepository repository;

    public FocusSessionGateway(AppRepository repository) {
        if (repository == null) throw new IllegalArgumentException("repository must not be null");
        this.repository = repository;
    }

    public FocusSnapshot load() {
        requireBackgroundThread();
        FocusState focus = repository.getCurrentFocus();
        TaskItem task = focus.getTaskId() == null ? null : repository.getTask(focus.getTaskId());
        return new FocusSnapshot(focus, repository.getSettings(), task);
    }

    public CompletionSnapshot completeIfDue(long now) {
        requireBackgroundThread();
        AppRepository.FocusCompletion completion = repository.completeCurrentFocusNaturally(now);
        return new CompletionSnapshot(completion, load());
    }

    private static void requireBackgroundThread() {
        if (Looper.getMainLooper() == Looper.myLooper()) {
            throw new IllegalStateException("FocusSessionGateway must be used off the main thread");
        }
    }

    public static final class FocusSnapshot {
        private final FocusState focus;
        private final AppSettings settings;
        private final TaskItem task;

        private FocusSnapshot(FocusState focus, AppSettings settings, TaskItem task) {
            this.focus = focus;
            this.settings = settings;
            this.task = task;
        }

        public FocusState getFocus() { return focus; }
        public AppSettings getSettings() { return settings.copy(); }
        public TaskItem getTask() { return task == null ? null : task.copy(); }
    }

    public static final class CompletionSnapshot {
        private final AppRepository.FocusCompletion completion;
        private final FocusSnapshot snapshot;

        private CompletionSnapshot(AppRepository.FocusCompletion completion, FocusSnapshot snapshot) {
            this.completion = completion;
            this.snapshot = snapshot;
        }

        public AppRepository.FocusCompletion getCompletion() { return completion; }
        public FocusSnapshot getSnapshot() { return snapshot; }
    }
}
