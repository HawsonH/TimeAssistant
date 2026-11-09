package com.hawson.timeassistant.ui.schedule;

import com.hawson.timeassistant.data.CompletionUndoTicket;
import com.hawson.timeassistant.domain.TaskItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Monotonic presentation deadlines; completion has already been committed. */
public final class AttentionCompletionBuffer {
    public static final class Entry {
        public final CompletionUndoTicket ticket;
        public final String group;
        public final int position;
        public final long expiresAt;
        Entry(CompletionUndoTicket ticket, String group, int position, long expiresAt) {
            this.ticket = ticket; this.group = group; this.position = position; this.expiresAt = expiresAt;
        }
    }
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public void retain(CompletionUndoTicket ticket, String group, int position, long now, long timeout) {
        entries.put(ticket.taskId(), new Entry(ticket, group, position, now + timeout));
    }
    public Entry get(String id, long now) {
        Entry entry = entries.get(id);
        return entry != null && entry.expiresAt > now ? entry : null;
    }
    public void remove(String id) { entries.remove(id); }
    public void clear() { entries.clear(); }
    public List<Entry> entries(long now) {
        entries.values().removeIf(entry -> entry.expiresAt <= now);
        return new ArrayList<>(entries.values());
    }
    public long nextDeadline(long now) {
        long next = Long.MAX_VALUE;
        for (Entry entry : entries(now)) next = Math.min(next, entry.expiresAt);
        return next;
    }
    public List<TaskItem> merge(String group, List<TaskItem> source, List<TaskItem> all, long now) {
        List<TaskItem> result = new ArrayList<>(source);
        for (Entry entry : entries(now)) {
            TaskItem current = null;
            for (TaskItem task : all) if (task.getId().equals(entry.ticket.taskId())) { current = task; break; }
            if (!entry.ticket.matches(current)) { entries.remove(entry.ticket.taskId()); continue; }
            if (!entry.group.equals(group)) continue;
            result.removeIf(task -> task.getId().equals(entry.ticket.taskId()));
            result.add(Math.min(entry.position, result.size()), current);
        }
        return result;
    }
}
