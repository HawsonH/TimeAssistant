package com.hawson.timeassistant.ui;

import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.FocusRecord;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.TaskItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable timer, task and history projection for the focus page. */
public final class FocusPageUiState {
    private final long revision;
    private final FocusState focusState;
    private final AppSettings settings;
    private final List<TaskItem> tasks;
    private final List<FocusRecord> history;

    public FocusPageUiState(long revision, FocusState focusState, AppSettings settings,
                            List<TaskItem> tasks, List<FocusRecord> history) {
        this.revision = revision;
        this.focusState = focusState == null ? FocusState.idle() : focusState;
        this.settings = settings.copy();
        List<TaskItem> taskCopy = new ArrayList<>();
        for (TaskItem task : tasks) taskCopy.add(task.copy());
        this.tasks = Collections.unmodifiableList(taskCopy);
        this.history = Collections.unmodifiableList(new ArrayList<>(history));
    }

    public long getRevision() { return revision; }
    public FocusState getFocusState() { return focusState; }
    public AppSettings getSettings() { return settings.copy(); }
    public List<TaskItem> getTasks() {
        List<TaskItem> copy = new ArrayList<>();
        for (TaskItem task : tasks) copy.add(task.copy());
        return Collections.unmodifiableList(copy);
    }
    public List<FocusRecord> getHistory() { return history; }
}
