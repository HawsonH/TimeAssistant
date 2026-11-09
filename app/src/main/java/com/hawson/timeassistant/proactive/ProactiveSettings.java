package com.hawson.timeassistant.proactive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Device-local configuration for condition-driven proactive AI check-ins. */
public final class ProactiveSettings {
    public boolean enabled;
    public boolean morningEnabled = true;
    public boolean middayEnabled = true;
    public boolean afternoonEnabled = true;
    public boolean eveningEnabled = true;
    public int morningMinute = 8 * 60 + 30;
    public int middayMinute = 12 * 60 + 30;
    public int afternoonMinute = 16 * 60 + 30;
    public int eveningMinute = 21 * 60;
    public boolean attentionTriggerEnabled = true;
    public boolean middayTodoPlanningEnabled = true;
    public boolean todoReviewEnabled = true;
    public String scheduleGeneration = "";
    private List<TodoReviewSlot> todoReviewSlots = new ArrayList<>(Collections.singletonList(
            new TodoReviewSlot("legacy-midday", 12 * 60 + 30, true)));
    public boolean backgroundAiEnabled = true;
    public boolean notificationEnabled = true;
    public int dailyLimit = 3;
    public int quietStartMinute = 22 * 60 + 30;
    public int quietEndMinute = 7 * 60;

    public ProactiveSettings copy() {
        ProactiveSettings copy = new ProactiveSettings();
        copy.enabled = enabled;
        copy.morningEnabled = morningEnabled;
        copy.middayEnabled = middayEnabled;
        copy.afternoonEnabled = afternoonEnabled;
        copy.eveningEnabled = eveningEnabled;
        copy.morningMinute = morningMinute;
        copy.middayMinute = middayMinute;
        copy.afternoonMinute = afternoonMinute;
        copy.eveningMinute = eveningMinute;
        copy.attentionTriggerEnabled = attentionTriggerEnabled;
        copy.middayTodoPlanningEnabled = middayTodoPlanningEnabled;
        copy.todoReviewEnabled = todoReviewEnabled;
        copy.scheduleGeneration = scheduleGeneration;
        copy.todoReviewSlots = new ArrayList<>(todoReviewSlots);
        copy.backgroundAiEnabled = backgroundAiEnabled;
        copy.notificationEnabled = notificationEnabled;
        copy.dailyLimit = dailyLimit;
        copy.quietStartMinute = quietStartMinute;
        copy.quietEndMinute = quietEndMinute;
        return copy;
    }

    public List<TodoReviewSlot> getTodoReviewSlots() {
        return Collections.unmodifiableList(new ArrayList<>(todoReviewSlots));
    }

    public void setTodoReviewSlots(List<TodoReviewSlot> slots) {
        if (slots == null || slots.isEmpty() || slots.size() > 6) {
            throw new IllegalArgumentException("configure 1 to 6 daily review slots");
        }
        Set<String> ids = new HashSet<>();
        Set<Integer> minutes = new HashSet<>();
        for (TodoReviewSlot slot : slots) {
            if (slot == null || !ids.add(slot.id)) throw new IllegalArgumentException("duplicate slot id");
            if (!minutes.add(slot.minuteOfDay)) throw new IllegalArgumentException("duplicate slot time");
        }
        todoReviewSlots = new ArrayList<>(slots);
    }

    public TodoReviewSlot addTodoReviewSlot(int minuteOfDay) {
        TodoReviewSlot slot = TodoReviewSlot.create(minuteOfDay);
        List<TodoReviewSlot> slots = new ArrayList<>(todoReviewSlots);
        slots.add(slot);
        setTodoReviewSlots(slots);
        return slot;
    }

    public void updateTodoReviewSlot(TodoReviewSlot replacement) {
        List<TodoReviewSlot> slots = new ArrayList<>(todoReviewSlots);
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).id.equals(replacement.id)) {
                slots.set(i, replacement);
                setTodoReviewSlots(slots);
                return;
            }
        }
        throw new IllegalArgumentException("unknown slot id");
    }

    public void removeTodoReviewSlot(String id) {
        List<TodoReviewSlot> slots = new ArrayList<>(todoReviewSlots);
        if (!slots.removeIf(slot -> slot.id.equals(id))) return;
        setTodoReviewSlots(slots);
    }

    public boolean hasTodoReviewAt(int minute) {
        if (!todoReviewEnabled) return false;
        for (TodoReviewSlot slot : todoReviewSlots) {
            if (slot.enabled && slot.minuteOfDay == minute) return true;
        }
        return false;
    }

    public void normalize() {
        morningMinute = minute(morningMinute, 8 * 60 + 30);
        middayMinute = minute(middayMinute, 12 * 60 + 30);
        afternoonMinute = minute(afternoonMinute, 16 * 60 + 30);
        eveningMinute = minute(eveningMinute, 21 * 60);
        quietStartMinute = minute(quietStartMinute, 22 * 60 + 30);
        quietEndMinute = minute(quietEndMinute, 7 * 60);
        dailyLimit = Math.max(1, Math.min(6, dailyLimit));
    }

    public boolean isRoutineEnabled(ProactiveRoutine routine) {
        switch (routine) {
            case MORNING: return morningEnabled;
            case MIDDAY: return middayEnabled;
            case AFTERNOON: return afternoonEnabled;
            case EVENING: return eveningEnabled;
            default: return false;
        }
    }

    public int minuteFor(ProactiveRoutine routine) {
        switch (routine) {
            case MORNING: return morningMinute;
            case MIDDAY: return middayMinute;
            case AFTERNOON: return afternoonMinute;
            case EVENING: return eveningMinute;
            default: return middayMinute;
        }
    }

    public boolean isQuietAt(int minuteOfDay) {
        if (quietStartMinute == quietEndMinute) return false;
        if (quietStartMinute < quietEndMinute) {
            return minuteOfDay >= quietStartMinute && minuteOfDay < quietEndMinute;
        }
        return minuteOfDay >= quietStartMinute || minuteOfDay < quietEndMinute;
    }

    private static int minute(int value, int fallback) {
        return value >= 0 && value < 24 * 60 ? value : fallback;
    }
}
