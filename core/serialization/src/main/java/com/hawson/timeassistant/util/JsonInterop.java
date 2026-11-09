/**
 * JSON 转换工具
 *
 * 把领域模型里的 Map / List 结构转成 Android 自带的 org.json 对象，
 * 供 SharedPreferences 快照和导入导出使用。
 */
package com.hawson.timeassistant.util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/** Converts portable Java maps/lists into Android's org.json objects. */
public final class JsonInterop {
    private JsonInterop() {}

    public static JSONObject toJsonObject(Map<String, ?> values) throws JSONException {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            json.put(entry.getKey(), toJsonValue(entry.getValue()));
        }
        return json;
    }

    private static Object toJsonValue(Object value) throws JSONException {
        if (value == null) return JSONObject.NULL;
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, ?> map = (Map<String, ?>) value;
            return toJsonObject(map);
        }
        if (value instanceof Iterable) {
            JSONArray array = new JSONArray();
            for (Object item : (Iterable<?>) value) array.put(toJsonValue(item));
            return array;
        }
        return value;
    }
}
