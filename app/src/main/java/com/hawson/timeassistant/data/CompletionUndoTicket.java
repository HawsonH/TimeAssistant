package com.hawson.timeassistant.data;

import com.hawson.timeassistant.domain.TaskItem;

/** Process-local, single-use authority to undo exactly one task completion. */
public final class CompletionUndoTicket {
    final Object owner;
    final TaskItem before;
    private final long completedVersion;
    boolean consumed;

    CompletionUndoTicket(Object owner, TaskItem before, TaskItem completed) {
        this.owner = owner;
        this.before = before.copy();
        completedVersion = completed.getUpdatedAt();
    }

    public String taskId() { return before.getId(); }

    public boolean matches(TaskItem current) {
        return !consumed && current != null && taskId().equals(current.getId())
                && current.getStatus() == TaskItem.Status.COMPLETED
                && current.getUpdatedAt() == completedVersion;
    }
}
