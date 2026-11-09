package com.hawson.timeassistant.ai.runtime;

import android.content.Context;

import com.hawson.timeassistant.data.local.LocalIntegrationSettingsStore;
import com.hawson.timeassistant.integration.calendar.CalendarAccessPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Determines which host reads/actions may be exposed for the current local permission state. */
public final class PolicyEngine {
    private final Context context;
    private final LocalIntegrationSettingsStore settings;

    public PolicyEngine(Context context, LocalIntegrationSettingsStore settings) {
        this.context = context.getApplicationContext();
        this.settings = settings;
    }

    public boolean mayRead(String toolId) {
        if ("calendar.busy".equals(toolId)) return CalendarAccessPolicy.canRead(
                context, settings.isCalendarExperimentEnabled());
        return "tasks.query".equals(toolId) || "tasks.get".equals(toolId)
                || "recurrence.query".equals(toolId) || "focus.summary".equals(toolId)
                || "preferences.resolve".equals(toolId)
                || "attention.query".equals(toolId) || "todos.planning".equals(toolId);
    }

    public boolean mayComposeCalendarEvent() {
        return CalendarAccessPolicy.canRead(context, settings.isCalendarExperimentEnabled());
    }

    public List<String> availableReadTools() {
        List<String> tools = new ArrayList<>();
        Collections.addAll(tools, "tasks.query", "tasks.get", "recurrence.query",
                "focus.summary", "preferences.resolve", "attention.query", "todos.planning");
        if (mayRead("calendar.busy")) tools.add("calendar.busy");
        return Collections.unmodifiableList(tools);
    }
}
