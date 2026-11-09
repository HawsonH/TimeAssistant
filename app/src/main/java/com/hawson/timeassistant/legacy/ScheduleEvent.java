/**
 * 旧版日程事件（遗留）
 *
 * 早期独立日程条目的数据模型，新数据已统一为 TaskItem，仅用于历史迁移。
 */
package com.hawson.timeassistant.legacy;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public class ScheduleEvent {
    public final String id;
    public final String title;
    public final long startAt;
    public final int durationMinutes;
    public final int color;
    public final String category;

    public ScheduleEvent(String title, long startAt, int durationMinutes, int color, String category) {
        this(UUID.randomUUID().toString(), title, startAt, durationMinutes, color, category);
    }

    private ScheduleEvent(String id, String title, long startAt, int durationMinutes, int color, String category) {
        this.id = id;
        this.title = title;
        this.startAt = startAt;
        this.durationMinutes = durationMinutes;
        this.color = color;
        this.category = category;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("title", title);
        json.put("startAt", startAt);
        json.put("durationMinutes", durationMinutes);
        json.put("color", color);
        json.put("category", category);
        return json;
    }

    public static ScheduleEvent fromJson(JSONObject json) throws JSONException {
        return new ScheduleEvent(
                json.getString("id"),
                json.getString("title"),
                json.getLong("startAt"),
                json.getInt("durationMinutes"),
                json.getInt("color"),
                json.optString("category", "日程")
        );
    }
}
