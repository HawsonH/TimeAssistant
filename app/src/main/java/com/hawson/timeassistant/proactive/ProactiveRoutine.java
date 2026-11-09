package com.hawson.timeassistant.proactive;

import java.util.Locale;

public enum ProactiveRoutine {
    MORNING("morning", "晨间整理"),
    MIDDAY("midday", "午间整理"),
    AFTERNOON("afternoon", "下午检查"),
    EVENING("evening", "晚间收尾"),
    TODO_REVIEW("todo_review", "待办整理"),
    CHECK_IN("check_in", "主动整理");

    public final String storageValue;
    public final String label;

    ProactiveRoutine(String storageValue, String label) {
        this.storageValue = storageValue;
        this.label = label;
    }

    public static ProactiveRoutine fromStorage(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        for (ProactiveRoutine routine : values()) {
            if (routine.storageValue.equals(value) || routine.name().toLowerCase(Locale.ROOT).equals(value)) {
                return routine;
            }
        }
        return MIDDAY;
    }
}
