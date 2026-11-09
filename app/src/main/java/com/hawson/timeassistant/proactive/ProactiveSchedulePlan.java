package com.hawson.timeassistant.proactive;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Pure scheduling rules shared by the coordinator and worker input validation. */
public final class ProactiveSchedulePlan {
    private ProactiveSchedulePlan() { }

    public static List<Integer> minutes(ProactiveSettings settings) {
        TreeSet<Integer> result = new TreeSet<>();
        if (!settings.enabled) return new ArrayList<>();
        if (settings.attentionTriggerEnabled) {
            for (ProactiveRoutine routine : ProactiveRoutine.values()) {
                if (settings.isRoutineEnabled(routine)) result.add(settings.minuteFor(routine));
            }
        }
        if (settings.todoReviewEnabled) {
            for (TodoReviewSlot slot : settings.getTodoReviewSlots()) {
                if (slot.enabled) result.add(slot.minuteOfDay);
            }
        }
        return new ArrayList<>(result);
    }

    public static boolean isCurrent(ProactiveSettings settings, String generation,
                                    int minute, boolean manual) {
        if (!settings.enabled || generation == null || generation.isEmpty()
                || !generation.equals(settings.scheduleGeneration)) return false;
        return manual ? settings.todoReviewEnabled || settings.attentionTriggerEnabled
                : minutes(settings).contains(minute);
    }

    public static ProactiveRoutine attentionRoutineAt(ProactiveSettings settings, int minute) {
        if (!settings.attentionTriggerEnabled) return null;
        for (ProactiveRoutine routine : ProactiveRoutine.values()) {
            if (settings.isRoutineEnabled(routine) && settings.minuteFor(routine) == minute) return routine;
        }
        return null;
    }
}
