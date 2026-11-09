package com.hawson.timeassistant.ui;

import com.hawson.timeassistant.domain.RecurringOccurrenceOverride;
import com.hawson.timeassistant.domain.RecurringTaskGroup;
import com.hawson.timeassistant.domain.TaskItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable task and recurrence projection for the schedule page. */
public final class SchedulePageUiState {
    private final long revision;
    private final List<TaskItem> tasks;
    private final List<RecurringTaskGroup> recurringGroups;
    private final List<RecurringOccurrenceOverride> recurringOverrides;

    public SchedulePageUiState(long revision, List<TaskItem> tasks,
                               List<RecurringTaskGroup> recurringGroups,
                               List<RecurringOccurrenceOverride> recurringOverrides) {
        this.revision = revision;
        this.tasks = copyTasks(tasks);
        this.recurringGroups = copyGroups(recurringGroups);
        this.recurringOverrides = copyOverrides(recurringOverrides);
    }

    public long getRevision() { return revision; }
    public List<TaskItem> getTasks() { return copyTasks(tasks); }
    public List<RecurringTaskGroup> getRecurringGroups() { return copyGroups(recurringGroups); }
    public List<RecurringOccurrenceOverride> getRecurringOverrides() {
        return copyOverrides(recurringOverrides);
    }

    private static List<TaskItem> copyTasks(List<TaskItem> input) {
        List<TaskItem> output = new ArrayList<>();
        for (TaskItem item : input) output.add(item.copy());
        return Collections.unmodifiableList(output);
    }

    private static List<RecurringTaskGroup> copyGroups(List<RecurringTaskGroup> input) {
        List<RecurringTaskGroup> output = new ArrayList<>();
        for (RecurringTaskGroup item : input) output.add(item.copy());
        return Collections.unmodifiableList(output);
    }

    private static List<RecurringOccurrenceOverride> copyOverrides(
            List<RecurringOccurrenceOverride> input) {
        List<RecurringOccurrenceOverride> output = new ArrayList<>();
        for (RecurringOccurrenceOverride item : input) output.add(item.copy());
        return Collections.unmodifiableList(output);
    }
}
