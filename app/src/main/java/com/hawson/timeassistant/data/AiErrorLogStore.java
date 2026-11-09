package com.hawson.timeassistant.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.hawson.timeassistant.ai.AiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stores sanitized AI failures locally so the chat UI can stay calm and user-oriented. */
public final class AiErrorLogStore {
    private static final String PREFERENCES = "ai_error_log";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 30;

    private final SharedPreferences preferences;

    public AiErrorLogStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public synchronized void append(Throwable error, String baseUrl, String model,
                                    boolean hadAttachments) {
        JSONArray existing = readArray();
        JSONArray updated = new JSONArray();
        JSONObject entry = new JSONObject();
        try {
            entry.put("createdAt", System.currentTimeMillis());
            entry.put("kind", kindOf(error));
            entry.put("httpStatus", httpStatusOf(error));
            entry.put("baseUrl", clean(baseUrl));
            entry.put("model", clean(model));
            entry.put("hadAttachments", hadAttachments);
            entry.put("detail", detailedMessage(error));
            updated.put(entry);
            for (int index = 0; index < existing.length() && updated.length() < MAX_ENTRIES; index++) {
                JSONObject old = existing.optJSONObject(index);
                if (old != null) updated.put(old);
            }
            preferences.edit().putString(KEY_ENTRIES, updated.toString()).apply();
        } catch (Exception ignored) {
            // Error reporting must never make the original request failure worse.
        }
    }

    public synchronized List<Entry> getEntries() {
        JSONArray values = readArray();
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) continue;
            entries.add(new Entry(
                    value.optLong("createdAt", 0),
                    value.optString("kind", "UNKNOWN"),
                    value.optInt("httpStatus", -1),
                    value.optString("baseUrl", ""),
                    value.optString("model", ""),
                    value.optBoolean("hadAttachments", false),
                    value.optString("detail", "未知错误")
            ));
        }
        return Collections.unmodifiableList(entries);
    }

    public synchronized int size() {
        return readArray().length();
    }

    public synchronized void clear() {
        preferences.edit().remove(KEY_ENTRIES).apply();
    }

    private JSONArray readArray() {
        try {
            return new JSONArray(preferences.getString(KEY_ENTRIES, "[]"));
        } catch (Exception ignored) {
            preferences.edit().remove(KEY_ENTRIES).apply();
            return new JSONArray();
        }
    }

    private static String kindOf(Throwable error) {
        if (error instanceof AiClient.AiError) {
            return ((AiClient.AiError) error).getKind().name();
        }
        return error == null ? "UNKNOWN" : error.getClass().getSimpleName();
    }

    private static int httpStatusOf(Throwable error) {
        return error instanceof AiClient.AiError
                ? ((AiClient.AiError) error).getHttpStatus() : -1;
    }

    private static String detailedMessage(Throwable error) {
        if (error == null) return "未知错误";
        StringBuilder detail = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 4; depth++) {
            if (detail.length() > 0) detail.append("\n由 ");
            detail.append(current.getClass().getSimpleName());
            String message = clean(current.getMessage());
            if (!message.isEmpty()) detail.append("：").append(message);
            current = current.getCause();
        }
        String value = detail.toString();
        return value.length() > 2_000 ? value.substring(0, 2_000) + "…" : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Entry {
        public final long createdAt;
        public final String kind;
        public final int httpStatus;
        public final String baseUrl;
        public final String model;
        public final boolean hadAttachments;
        public final String detail;

        Entry(long createdAt, String kind, int httpStatus, String baseUrl, String model,
              boolean hadAttachments, String detail) {
            this.createdAt = createdAt;
            this.kind = kind;
            this.httpStatus = httpStatus;
            this.baseUrl = baseUrl;
            this.model = model;
            this.hadAttachments = hadAttachments;
            this.detail = detail;
        }
    }
}
