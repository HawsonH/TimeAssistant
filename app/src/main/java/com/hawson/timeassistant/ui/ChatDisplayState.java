package com.hawson.timeassistant.ui;

import android.os.Bundle;
import java.util.*;

/** Presentation choices are separate from persisted messages and action execution. */
final class ChatDisplayState {
    final Set<String> expandedPlans = new HashSet<>();
    final Set<String> selectingPlans = new HashSet<>();
    final Set<String> expandedDays = new HashSet<>();
    final Map<String, Set<Integer>> selections = new HashMap<>();

    Bundle save() {
        Bundle saved = new Bundle();
        saved.putStringArrayList("plans", new ArrayList<>(expandedPlans));
        saved.putStringArrayList("selecting", new ArrayList<>(selectingPlans));
        saved.putStringArrayList("days", new ArrayList<>(expandedDays));
        Bundle selected = new Bundle();
        for (Map.Entry<String, Set<Integer>> item : selections.entrySet()) {
            selected.putIntegerArrayList(item.getKey(), new ArrayList<>(item.getValue()));
        }
        saved.putBundle("selected", selected);
        return saved;
    }

    void restore(Bundle saved) {
        if (saved == null) return;
        restoreSet(expandedPlans, saved.getStringArrayList("plans"));
        restoreSet(selectingPlans, saved.getStringArrayList("selecting"));
        restoreSet(expandedDays, saved.getStringArrayList("days"));
        Bundle selected = saved.getBundle("selected");
        if (selected == null) return;
        for (String key : selected.keySet()) {
            ArrayList<Integer> values = selected.getIntegerArrayList(key);
            if (values != null) selections.put(key, new HashSet<>(values));
        }
    }

    private void restoreSet(Set<String> target, List<String> values) {
        if (values != null) target.addAll(values);
    }
}
