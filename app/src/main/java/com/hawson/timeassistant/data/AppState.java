package com.hawson.timeassistant.data;

import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.ChatMessage;
import com.hawson.timeassistant.domain.FocusRecord;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.RecurringOccurrenceOverride;
import com.hawson.timeassistant.domain.RecurringTaskGroup;
import com.hawson.timeassistant.domain.ReminderEvent;
import com.hawson.timeassistant.domain.TaskItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable UI projection emitted after a committed repository revision. */
public final class AppState {
    private final long revision;
    private final List<TaskItem> tasks;
    private final List<RecurringTaskGroup> recurringGroups;
    private final List<RecurringOccurrenceOverride> recurringOverrides;
    private final List<ReminderEvent> reminderEvents;
    private final List<ChatMessage> chatMessages;
    private final List<FocusRecord> focusRecords;
    private final AppSettings settings;
    private final FocusState currentFocus;
    private final String recoveryMessage;
    private final boolean writesBlocked;

    AppState(long revision, List<TaskItem> tasks, List<RecurringTaskGroup> recurringGroups,
             List<RecurringOccurrenceOverride> recurringOverrides,
             List<ReminderEvent> reminderEvents, List<ChatMessage> chatMessages,
             List<FocusRecord> focusRecords, AppSettings settings, FocusState currentFocus,
             String recoveryMessage, boolean writesBlocked) {
        this.revision = revision;
        this.tasks = copiesOfTasks(tasks);
        this.recurringGroups = copiesOfGroups(recurringGroups);
        this.recurringOverrides = copiesOfOverrides(recurringOverrides);
        this.reminderEvents = copiesOfReminders(reminderEvents);
        this.chatMessages = Collections.unmodifiableList(new ArrayList<>(chatMessages));
        this.focusRecords = Collections.unmodifiableList(new ArrayList<>(focusRecords));
        this.settings = settings == null ? new AppSettings() : settings.copy();
        this.currentFocus = currentFocus == null ? FocusState.idle() : currentFocus;
        this.recoveryMessage = recoveryMessage;
        this.writesBlocked = writesBlocked;
    }

    public long getRevision() { return revision; }
    public List<TaskItem> getTasks() { return copiesOfTasks(tasks); }
    public List<RecurringTaskGroup> getRecurringGroups() { return copiesOfGroups(recurringGroups); }
    public List<RecurringOccurrenceOverride> getRecurringOverrides() {
        return copiesOfOverrides(recurringOverrides);
    }
    public List<ReminderEvent> getReminderEvents() { return copiesOfReminders(reminderEvents); }
    public List<ChatMessage> getChatMessages() { return chatMessages; }
    public List<FocusRecord> getFocusRecords() { return focusRecords; }
    public AppSettings getSettings() { return settings.copy(); }
    public FocusState getCurrentFocus() { return currentFocus; }
    public String getRecoveryMessage() { return recoveryMessage; }
    public boolean isWritesBlocked() { return writesBlocked; }

    private static List<TaskItem> copiesOfTasks(List<TaskItem> source) {
        List<TaskItem> copy = new ArrayList<>();
        if (source != null) for (TaskItem item : source) if (item != null) copy.add(item.copy());
        return Collections.unmodifiableList(copy);
    }

    private static List<RecurringTaskGroup> copiesOfGroups(List<RecurringTaskGroup> source) {
        List<RecurringTaskGroup> copy = new ArrayList<>();
        if (source != null) for (RecurringTaskGroup item : source) if (item != null) copy.add(item.copy());
        return Collections.unmodifiableList(copy);
    }

    private static List<RecurringOccurrenceOverride> copiesOfOverrides(
            List<RecurringOccurrenceOverride> source) {
        List<RecurringOccurrenceOverride> copy = new ArrayList<>();
        if (source != null) for (RecurringOccurrenceOverride item : source) {
            if (item != null) copy.add(item.copy());
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<ReminderEvent> copiesOfReminders(List<ReminderEvent> source) {
        List<ReminderEvent> copy = new ArrayList<>();
        if (source != null) for (ReminderEvent item : source) if (item != null) copy.add(item.copy());
        return Collections.unmodifiableList(copy);
    }
}
