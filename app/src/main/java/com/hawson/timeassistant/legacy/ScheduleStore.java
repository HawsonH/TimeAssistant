/**
 * 旧版日程存储（遗留）
 *
 * 早期基于 SharedPreferences 的日程列表，已由 AppRepository 取代。
 */
package com.hawson.timeassistant.legacy;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ScheduleStore {
    private static final String PREFS = "time_assistant_schedule";
    private static final String KEY_EVENTS = "events";
    private final SharedPreferences preferences;
    private final List<ScheduleEvent> events = new ArrayList<>();

    public ScheduleStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public void add(ScheduleEvent event) {
        events.add(event);
        sort();
        save();
    }

    public List<ScheduleEvent> eventsOn(Calendar day) {
        List<ScheduleEvent> result = new ArrayList<>();
        for (ScheduleEvent event : events) {
            Calendar eventDay = Calendar.getInstance();
            eventDay.setTimeInMillis(event.startAt);
            if (sameDay(day, eventDay)) result.add(event);
        }
        Collections.sort(result, Comparator.comparingLong(item -> item.startAt));
        return result;
    }

    private void load() {
        String raw = preferences.getString(KEY_EVENTS, null);
        if (raw == null) return;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                events.add(ScheduleEvent.fromJson(array.getJSONObject(i)));
            }
            sort();
        } catch (JSONException ignored) {
            events.clear();
        }
    }

    private void save() {
        JSONArray array = new JSONArray();
        for (ScheduleEvent event : events) {
            try {
                array.put(event.toJson());
            } catch (JSONException ignored) {
                // A single malformed event should not block the rest of the schedule.
            }
        }
        preferences.edit().putString(KEY_EVENTS, array.toString()).apply();
    }

    private void sort() {
        Collections.sort(events, Comparator.comparingLong(item -> item.startAt));
    }

    private static boolean sameDay(Calendar first, Calendar second) {
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }

}
