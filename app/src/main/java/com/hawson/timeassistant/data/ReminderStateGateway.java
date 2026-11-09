package com.hawson.timeassistant.data;

import android.os.Looper;

import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.TaskItem;

import java.util.List;

/** Background-only snapshot gateway for alarm receivers, workers and rescheduling. */
public final class ReminderStateGateway {
    private final AppRepository repository;

    public ReminderStateGateway(AppRepository repository) {
        if (repository == null) throw new IllegalArgumentException("repository must not be null");
        this.repository = repository;
    }

    public ReminderSnapshot load() {
        if (Looper.getMainLooper() == Looper.myLooper()) {
            throw new IllegalStateException("ReminderStateGateway must be used off the main thread");
        }
        return new ReminderSnapshot(repository.getAllTasks(), repository.getSettings(),
                repository.getCurrentFocus());
    }

    public static final class ReminderSnapshot {
        private final List<TaskItem> tasks;
        private final AppSettings settings;
        private final FocusState focus;

        private ReminderSnapshot(List<TaskItem> tasks, AppSettings settings, FocusState focus) {
            this.tasks = tasks;
            this.settings = settings;
            this.focus = focus;
        }

        public List<TaskItem> getTasks() { return tasks; }
        public AppSettings getSettings() { return settings.copy(); }
        public FocusState getFocus() { return focus; }
    }
}
