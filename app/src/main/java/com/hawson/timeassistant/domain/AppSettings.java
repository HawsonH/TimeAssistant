/**
 * 应用设置
 *
 * AI 服务、专注时长、可用时间段、提醒开关与同步元数据等用户偏好。
 */
package com.hawson.timeassistant.domain;

import com.hawson.timeassistant.util.JsonInterop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persisted application settings. API keys can be omitted from exported or synchronized JSON. */
public final class AppSettings {
    public static final int MINUTES_PER_DAY = 24 * 60;
    public static final String DEFAULT_GREETING_TEXT = "今天也从容一点";
    public static final int MAX_GREETING_TEXT_LENGTH = 32;

    public static final class TimeRange {
        private final int startMinute;
        private final int endMinute;
        private final String label;

        public TimeRange(int startMinute, int endMinute, String label) {
            validateMinute(startMinute, false, "startMinute");
            validateMinute(endMinute, true, "endMinute");
            if (startMinute == endMinute) {
                throw new IllegalArgumentException("time range must have a non-zero duration");
            }
            this.startMinute = startMinute;
            this.endMinute = endMinute;
            this.label = label == null ? "" : label.trim();
        }

        public int getStartMinute() { return startMinute; }
        public int getEndMinute() { return endMinute; }
        public String getLabel() { return label; }

        /** Supports overnight ranges, for example 22:00-07:00. */
        public boolean contains(int minuteOfDay) {
            validateMinute(minuteOfDay, false, "minuteOfDay");
            if (startMinute < endMinute) return minuteOfDay >= startMinute && minuteOfDay < endMinute;
            return minuteOfDay >= startMinute || minuteOfDay < endMinute;
        }

        public JSONObject toJson() throws JSONException {
            return JsonInterop.toJsonObject(toJsonValue());
        }

        Map<String, Object> toJsonValue() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("startMinute", startMinute);
            json.put("endMinute", endMinute);
            json.put("label", label);
            return Collections.unmodifiableMap(json);
        }

        public static TimeRange fromJson(JSONObject json) throws JSONException {
            if (json == null) throw new JSONException("time range JSON must not be null");
            try {
                return new TimeRange(
                        json.getInt("startMinute"),
                        json.getInt("endMinute"),
                        json.optString("label", "")
                );
            } catch (IllegalArgumentException exception) {
                throw new JSONException("invalid time range: " + exception.getMessage());
            }
        }
    }

    private String baseUrl;
    private String apiKey;
    private String model;
    private String greetingText;

    private int focusDurationMinutes;
    private int shortBreakDurationMinutes;
    private int longBreakDurationMinutes;
    private int focusRoundsPerCycle;
    private boolean autoStartNextRound;
    private boolean autoStartBreak;
    private boolean autoStartNextPomodoro;
    private boolean breaksDisabled;
    private boolean focusCountUpEnabled;
    private boolean focusEndSoundEnabled;
    private boolean focusEndVibrationEnabled;
    private boolean ambientSoundEnabled;

    private int availableStartMinute;
    private int availableEndMinute;
    private int workStartMinute;
    private int workEndMinute;
    private final List<TimeRange> blockedTimeRanges;
    private boolean allowAiRescheduleUnlockedTasks;

    private boolean scheduleReminderEnabled;
    private boolean taskDeadlineReminderEnabled;
    private boolean focusReminderEnabled;
    private boolean overdueTaskReminderEnabled;
    private boolean dailyReviewReminderEnabled;

    private String deviceId;
    private String pairingCode;
    private String pairedDeviceName;
    private String syncEndpoint;
    private boolean syncEnabled;
    private long lastSyncAt;
    private long updatedAt;

    public AppSettings() {
        baseUrl = "https://api.openai.com/v1";
        apiKey = "";
        model = "";
        greetingText = DEFAULT_GREETING_TEXT;
        focusDurationMinutes = 25;
        shortBreakDurationMinutes = 5;
        longBreakDurationMinutes = 15;
        focusRoundsPerCycle = 4;
        autoStartNextRound = false;
        autoStartBreak = false;
        autoStartNextPomodoro = false;
        breaksDisabled = false;
        focusCountUpEnabled = false;
        focusEndSoundEnabled = true;
        focusEndVibrationEnabled = true;
        ambientSoundEnabled = false;
        availableStartMinute = 7 * 60;
        availableEndMinute = 23 * 60;
        workStartMinute = 9 * 60;
        workEndMinute = 18 * 60;
        blockedTimeRanges = new ArrayList<>();
        allowAiRescheduleUnlockedTasks = true;
        scheduleReminderEnabled = true;
        taskDeadlineReminderEnabled = true;
        focusReminderEnabled = true;
        overdueTaskReminderEnabled = true;
        dailyReviewReminderEnabled = false;
        deviceId = UUID.randomUUID().toString();
        pairingCode = "";
        pairedDeviceName = "";
        syncEndpoint = "";
        syncEnabled = false;
        lastSyncAt = 0;
        updatedAt = System.currentTimeMillis();
    }

    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public String getGreetingText() { return greetingText; }
    public int getFocusDurationMinutes() { return focusDurationMinutes; }
    public int getShortBreakDurationMinutes() { return shortBreakDurationMinutes; }
    public int getLongBreakDurationMinutes() { return longBreakDurationMinutes; }
    public int getFocusRoundsPerCycle() { return focusRoundsPerCycle; }
    public int getShortBreakRoundsPerCycle() { return Math.max(0, focusRoundsPerCycle - 1); }
    public int getLongBreakRoundsPerCycle() { return 1; }
    public boolean isLastFocusInCycle(int pomodoroIndex) {
        return Math.max(1, pomodoroIndex) % focusRoundsPerCycle == 0;
    }
    public boolean isAutoStartNextRound() { return autoStartNextRound; }
    public boolean isAutoAdvanceFocusSegment() { return autoStartNextRound; }
    public boolean isAutoStartBreak() { return autoStartBreak; }
    public boolean isAutoStartNextPomodoro() { return autoStartNextPomodoro; }
    public boolean isBreaksDisabled() { return breaksDisabled; }
    public boolean isFocusCountUpEnabled() { return focusCountUpEnabled; }
    public boolean isFocusEndSoundEnabled() { return focusEndSoundEnabled; }
    public boolean isFocusEndVibrationEnabled() { return focusEndVibrationEnabled; }
    public boolean isAmbientSoundEnabled() { return ambientSoundEnabled; }
    public int getAvailableStartMinute() { return availableStartMinute; }
    public int getAvailableEndMinute() { return availableEndMinute; }
    public int getWorkStartMinute() { return workStartMinute; }
    public int getWorkEndMinute() { return workEndMinute; }
    public List<TimeRange> getBlockedTimeRanges() {
        return Collections.unmodifiableList(new ArrayList<>(blockedTimeRanges));
    }
    public boolean isAllowAiRescheduleUnlockedTasks() { return allowAiRescheduleUnlockedTasks; }
    public boolean isScheduleReminderEnabled() { return scheduleReminderEnabled; }
    public boolean isTaskDeadlineReminderEnabled() { return taskDeadlineReminderEnabled; }
    public boolean isFocusReminderEnabled() { return focusReminderEnabled; }
    public boolean isOverdueTaskReminderEnabled() { return overdueTaskReminderEnabled; }
    public boolean isDailyReviewReminderEnabled() { return dailyReviewReminderEnabled; }
    public String getDeviceId() { return deviceId; }
    public String getPairingCode() { return pairingCode; }
    public String getPairedDeviceName() { return pairedDeviceName; }
    public String getSyncEndpoint() { return syncEndpoint; }
    public boolean isSyncEnabled() { return syncEnabled; }
    public long getLastSyncAt() { return lastSyncAt; }
    public long getUpdatedAt() { return updatedAt; }

    public AppSettings setAiService(String baseUrl, String apiKey, String model) {
        this.baseUrl = normalize(baseUrl);
        this.apiKey = normalize(apiKey);
        this.model = normalize(model);
        touch();
        return this;
    }

    public AppSettings setBaseUrl(String baseUrl) {
        this.baseUrl = normalize(baseUrl);
        touch();
        return this;
    }

    public AppSettings setApiKey(String apiKey) {
        this.apiKey = normalize(apiKey);
        touch();
        return this;
    }

    public AppSettings setModel(String model) {
        this.model = normalize(model);
        touch();
        return this;
    }

    public AppSettings clearAiService() {
        baseUrl = "";
        apiKey = "";
        model = "";
        touch();
        return this;
    }

    /** Short, user-facing phrase shown after the time-of-day greeting. */
    public AppSettings setGreetingText(String greetingText) {
        String normalized = normalize(greetingText);
        if (normalized.isEmpty()) throw new IllegalArgumentException("greetingText must not be empty");
        if (normalized.length() > MAX_GREETING_TEXT_LENGTH) {
            throw new IllegalArgumentException("greetingText must not exceed "
                    + MAX_GREETING_TEXT_LENGTH + " characters");
        }
        this.greetingText = normalized;
        touch();
        return this;
    }

    public AppSettings setFocusDurations(int focus, int shortBreak, int longBreak) {
        focusDurationMinutes = positiveDuration(focus, "focusDurationMinutes");
        shortBreakDurationMinutes = positiveDuration(shortBreak, "shortBreakDurationMinutes");
        longBreakDurationMinutes = positiveDuration(longBreak, "longBreakDurationMinutes");
        touch();
        return this;
    }

    public AppSettings setFocusRoundsPerCycle(int focusRounds) {
        focusRoundsPerCycle = cycleCount(focusRounds, "focusRoundsPerCycle");
        touch();
        return this;
    }

    /** Legacy import/API compatibility; break counts are derived from the focus count. */
    public AppSettings setFocusCycleCounts(int focusRounds, int ignoredShortBreakRounds,
                                           int ignoredLongBreakRounds) {
        return setFocusRoundsPerCycle(focusRounds);
    }

    public AppSettings setAutoStartNextRound(boolean enabled) {
        autoStartNextRound = enabled;
        normalizeFocusAutomation();
        touch();
        return this;
    }

    public AppSettings setFocusAutomation(boolean autoAdvance, boolean startBreak,
                                          boolean startPomodoro, boolean disableBreaks) {
        autoStartNextRound = autoAdvance;
        autoStartBreak = startBreak;
        autoStartNextPomodoro = startPomodoro;
        breaksDisabled = disableBreaks;
        normalizeFocusAutomation();
        touch();
        return this;
    }

    public AppSettings setFocusCountUpEnabled(boolean enabled) {
        focusCountUpEnabled = enabled;
        touch();
        return this;
    }

    public AppSettings setFocusEndSignals(boolean soundEnabled, boolean vibrationEnabled) {
        focusEndSoundEnabled = soundEnabled;
        focusEndVibrationEnabled = vibrationEnabled;
        touch();
        return this;
    }

    public AppSettings setAmbientSoundEnabled(boolean enabled) {
        ambientSoundEnabled = enabled;
        touch();
        return this;
    }

    public AppSettings setAvailableWindow(int startMinute, int endMinute) {
        validateWindow(startMinute, endMinute, "available window");
        availableStartMinute = startMinute;
        availableEndMinute = endMinute;
        touch();
        return this;
    }

    public AppSettings setWorkWindow(int startMinute, int endMinute) {
        validateWindow(startMinute, endMinute, "work window");
        workStartMinute = startMinute;
        workEndMinute = endMinute;
        touch();
        return this;
    }

    public AppSettings setBlockedTimeRanges(Collection<TimeRange> ranges) {
        blockedTimeRanges.clear();
        if (ranges != null) {
            for (TimeRange range : ranges) if (range != null) blockedTimeRanges.add(range);
        }
        touch();
        return this;
    }

    public AppSettings setAllowAiRescheduleUnlockedTasks(boolean allowed) {
        allowAiRescheduleUnlockedTasks = allowed;
        touch();
        return this;
    }

    public AppSettings setNotificationSettings(
            boolean schedule,
            boolean deadline,
            boolean focus,
            boolean overdue,
            boolean dailyReview
    ) {
        scheduleReminderEnabled = schedule;
        taskDeadlineReminderEnabled = deadline;
        focusReminderEnabled = focus;
        overdueTaskReminderEnabled = overdue;
        dailyReviewReminderEnabled = dailyReview;
        touch();
        return this;
    }

    public AppSettings setPairingInfo(String pairingCode, String pairedDeviceName) {
        this.pairingCode = normalize(pairingCode);
        this.pairedDeviceName = normalize(pairedDeviceName);
        return this;
    }

    public AppSettings setSyncConfiguration(String endpoint, boolean enabled) {
        syncEndpoint = normalize(endpoint);
        syncEnabled = enabled;
        return this;
    }

    public AppSettings setLastSyncAt(long lastSyncAt) {
        if (lastSyncAt < 0) throw new IllegalArgumentException("lastSyncAt must not be negative");
        this.lastSyncAt = lastSyncAt;
        return this;
    }

    public boolean hasCompleteAiConfiguration() {
        return !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty();
    }

    public AppSettings copy() {
        AppSettings copy = new AppSettings();
        copy.baseUrl = baseUrl;
        copy.apiKey = apiKey;
        copy.model = model;
        copy.greetingText = greetingText;
        copy.focusDurationMinutes = focusDurationMinutes;
        copy.shortBreakDurationMinutes = shortBreakDurationMinutes;
        copy.longBreakDurationMinutes = longBreakDurationMinutes;
        copy.focusRoundsPerCycle = focusRoundsPerCycle;
        copy.autoStartNextRound = autoStartNextRound;
        copy.autoStartBreak = autoStartBreak;
        copy.autoStartNextPomodoro = autoStartNextPomodoro;
        copy.breaksDisabled = breaksDisabled;
        copy.focusCountUpEnabled = focusCountUpEnabled;
        copy.focusEndSoundEnabled = focusEndSoundEnabled;
        copy.focusEndVibrationEnabled = focusEndVibrationEnabled;
        copy.ambientSoundEnabled = ambientSoundEnabled;
        copy.availableStartMinute = availableStartMinute;
        copy.availableEndMinute = availableEndMinute;
        copy.workStartMinute = workStartMinute;
        copy.workEndMinute = workEndMinute;
        copy.blockedTimeRanges.clear();
        copy.blockedTimeRanges.addAll(blockedTimeRanges);
        copy.allowAiRescheduleUnlockedTasks = allowAiRescheduleUnlockedTasks;
        copy.scheduleReminderEnabled = scheduleReminderEnabled;
        copy.taskDeadlineReminderEnabled = taskDeadlineReminderEnabled;
        copy.focusReminderEnabled = focusReminderEnabled;
        copy.overdueTaskReminderEnabled = overdueTaskReminderEnabled;
        copy.dailyReviewReminderEnabled = dailyReviewReminderEnabled;
        copy.deviceId = deviceId;
        copy.pairingCode = pairingCode;
        copy.pairedDeviceName = pairedDeviceName;
        copy.syncEndpoint = syncEndpoint;
        copy.syncEnabled = syncEnabled;
        copy.lastSyncAt = lastSyncAt;
        copy.updatedAt = updatedAt;
        return copy;
    }

    public AppSettings copyWithoutApiKey() {
        AppSettings copy = copy();
        copy.apiKey = "";
        return copy;
    }

    public JSONObject toJson() throws JSONException {
        return toJson(true);
    }

    public JSONObject toJson(boolean includeApiKey) throws JSONException {
        return JsonInterop.toJsonObject(toJsonValue(includeApiKey));
    }

    Map<String, Object> toJsonValue(boolean includeApiKey) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("baseUrl", baseUrl);
        if (includeApiKey) ai.put("apiKey", apiKey);
        ai.put("model", model);
        root.put("ai", ai);
        root.put("greetingText", greetingText);

        Map<String, Object> focus = new LinkedHashMap<>();
        focus.put("focusDurationMinutes", focusDurationMinutes);
        focus.put("shortBreakDurationMinutes", shortBreakDurationMinutes);
        focus.put("longBreakDurationMinutes", longBreakDurationMinutes);
        focus.put("focusRoundsPerCycle", focusRoundsPerCycle);
        focus.put("autoStartNextRound", autoStartNextRound);
        focus.put("autoAdvanceFocusSegment", autoStartNextRound);
        focus.put("autoStartBreak", autoStartBreak);
        focus.put("autoStartNextPomodoro", autoStartNextPomodoro);
        focus.put("breaksDisabled", breaksDisabled);
        focus.put("focusCountUpEnabled", focusCountUpEnabled);
        focus.put("focusEndSoundEnabled", focusEndSoundEnabled);
        focus.put("focusEndVibrationEnabled", focusEndVibrationEnabled);
        focus.put("ambientSoundEnabled", ambientSoundEnabled);
        root.put("focus", focus);

        Map<String, Object> time = new LinkedHashMap<>();
        time.put("availableStartMinute", availableStartMinute);
        time.put("availableEndMinute", availableEndMinute);
        time.put("workStartMinute", workStartMinute);
        time.put("workEndMinute", workEndMinute);
        List<Object> blocked = new ArrayList<>();
        for (TimeRange range : blockedTimeRanges) blocked.add(range.toJsonValue());
        time.put("blockedTimeRanges", blocked);
        time.put("allowAiRescheduleUnlockedTasks", allowAiRescheduleUnlockedTasks);
        root.put("timePreferences", time);

        Map<String, Object> notifications = new LinkedHashMap<>();
        notifications.put("scheduleReminderEnabled", scheduleReminderEnabled);
        notifications.put("taskDeadlineReminderEnabled", taskDeadlineReminderEnabled);
        notifications.put("focusReminderEnabled", focusReminderEnabled);
        notifications.put("overdueTaskReminderEnabled", overdueTaskReminderEnabled);
        notifications.put("dailyReviewReminderEnabled", dailyReviewReminderEnabled);
        root.put("notifications", notifications);

        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("deviceId", deviceId);
        sync.put("pairingCode", pairingCode);
        sync.put("pairedDeviceName", pairedDeviceName);
        sync.put("syncEndpoint", syncEndpoint);
        sync.put("syncEnabled", syncEnabled);
        sync.put("lastSyncAt", lastSyncAt);
        root.put("sync", sync);
        root.put("updatedAt", updatedAt);
        return Collections.unmodifiableMap(root);
    }

    /**
     * Portable settings shared with another device. Pairing identity and transport state are
     * deliberately local-only: synchronizing them can make two peers adopt the same device id or
     * try to connect to an endpoint that is only meaningful from the other device.
     */
    public Map<String, Object> toSyncJsonValue() {
        Map<String, Object> root = new LinkedHashMap<>(toJsonValue(false));
        root.remove("sync");
        return Collections.unmodifiableMap(root);
    }

    /**
     * Restores fields that identify this installation after importing shared user preferences.
     * This intentionally does not touch {@link #updatedAt}; local transport metadata must not win
     * or lose user-preference conflict resolution.
     */
    public AppSettings preserveLocalSyncStateFrom(AppSettings local) {
        if (local == null) return this;
        deviceId = local.deviceId;
        pairingCode = local.pairingCode;
        pairedDeviceName = local.pairedDeviceName;
        syncEndpoint = local.syncEndpoint;
        syncEnabled = local.syncEnabled;
        lastSyncAt = local.lastSyncAt;
        return this;
    }

    /** Keeps an intentionally omitted local secret without advancing shared setting timestamps. */
    public AppSettings preserveLocalApiKey(String localApiKey) {
        apiKey = normalize(localApiKey);
        return this;
    }

    public static AppSettings fromJson(JSONObject root) throws JSONException {
        if (root == null) throw new JSONException("settings JSON must not be null");
        AppSettings settings = new AppSettings();
        try {
            JSONObject ai = root.optJSONObject("ai");
            if (ai != null) {
                settings.baseUrl = ai.optString("baseUrl", settings.baseUrl).trim();
                if (ai.has("apiKey") && !ai.isNull("apiKey")) {
                    settings.apiKey = ai.optString("apiKey", "").trim();
                }
                settings.model = ai.optString("model", settings.model).trim();
            }
            String greeting = root.optString("greetingText", settings.greetingText).trim();
            settings.greetingText = greeting.isEmpty() ? DEFAULT_GREETING_TEXT : greeting;
            if (settings.greetingText.length() > MAX_GREETING_TEXT_LENGTH) {
                settings.greetingText = settings.greetingText.substring(0, MAX_GREETING_TEXT_LENGTH);
            }

            JSONObject focus = root.optJSONObject("focus");
            if (focus != null) {
                settings.focusDurationMinutes = positiveDuration(
                        focus.optInt("focusDurationMinutes", settings.focusDurationMinutes),
                        "focusDurationMinutes");
                settings.shortBreakDurationMinutes = positiveDuration(
                        focus.optInt("shortBreakDurationMinutes", settings.shortBreakDurationMinutes),
                        "shortBreakDurationMinutes");
                settings.longBreakDurationMinutes = positiveDuration(
                        focus.optInt("longBreakDurationMinutes", settings.longBreakDurationMinutes),
                        "longBreakDurationMinutes");
                settings.focusRoundsPerCycle = cycleCount(
                        focus.optInt("focusRoundsPerCycle", settings.focusRoundsPerCycle),
                        "focusRoundsPerCycle");
                boolean legacyAutoStart = focus.optBoolean(
                        "autoStartNextRound", settings.autoStartNextRound);
                settings.autoStartNextRound = focus.optBoolean(
                        "autoAdvanceFocusSegment", legacyAutoStart);
                settings.autoStartBreak = focus.optBoolean("autoStartBreak", legacyAutoStart);
                settings.autoStartNextPomodoro = focus.optBoolean(
                        "autoStartNextPomodoro", legacyAutoStart);
                settings.breaksDisabled = focus.optBoolean(
                        "breaksDisabled", settings.breaksDisabled);
                settings.focusCountUpEnabled = focus.optBoolean(
                        "focusCountUpEnabled", settings.focusCountUpEnabled);
                settings.focusEndSoundEnabled = focus.optBoolean(
                        "focusEndSoundEnabled", settings.focusEndSoundEnabled);
                settings.focusEndVibrationEnabled = focus.optBoolean(
                        "focusEndVibrationEnabled", settings.focusEndVibrationEnabled);
                settings.normalizeFocusAutomation();
                settings.ambientSoundEnabled = focus.optBoolean("ambientSoundEnabled", settings.ambientSoundEnabled);
            }

            JSONObject time = root.optJSONObject("timePreferences");
            if (time != null) {
                int availableStart = time.optInt("availableStartMinute", settings.availableStartMinute);
                int availableEnd = time.optInt("availableEndMinute", settings.availableEndMinute);
                validateWindow(availableStart, availableEnd, "available window");
                settings.availableStartMinute = availableStart;
                settings.availableEndMinute = availableEnd;
                int workStart = time.optInt("workStartMinute", settings.workStartMinute);
                int workEnd = time.optInt("workEndMinute", settings.workEndMinute);
                validateWindow(workStart, workEnd, "work window");
                settings.workStartMinute = workStart;
                settings.workEndMinute = workEnd;
                settings.blockedTimeRanges.clear();
                JSONArray blocked = time.optJSONArray("blockedTimeRanges");
                if (blocked != null) {
                    for (int i = 0; i < blocked.length(); i++) {
                        JSONObject range = blocked.optJSONObject(i);
                        if (range == null) throw new JSONException("blockedTimeRanges[" + i + "] is not an object");
                        settings.blockedTimeRanges.add(TimeRange.fromJson(range));
                    }
                }
                settings.allowAiRescheduleUnlockedTasks = time.optBoolean(
                        "allowAiRescheduleUnlockedTasks", settings.allowAiRescheduleUnlockedTasks);
            }

            JSONObject notifications = root.optJSONObject("notifications");
            if (notifications != null) {
                settings.scheduleReminderEnabled = notifications.optBoolean(
                        "scheduleReminderEnabled", settings.scheduleReminderEnabled);
                settings.taskDeadlineReminderEnabled = notifications.optBoolean(
                        "taskDeadlineReminderEnabled", settings.taskDeadlineReminderEnabled);
                settings.focusReminderEnabled = notifications.optBoolean(
                        "focusReminderEnabled", settings.focusReminderEnabled);
                settings.overdueTaskReminderEnabled = notifications.optBoolean(
                        "overdueTaskReminderEnabled", settings.overdueTaskReminderEnabled);
                settings.dailyReviewReminderEnabled = notifications.optBoolean(
                        "dailyReviewReminderEnabled", settings.dailyReviewReminderEnabled);
            }

            JSONObject sync = root.optJSONObject("sync");
            if (sync != null) {
                settings.deviceId = nonEmptyOr(sync.optString("deviceId", settings.deviceId), settings.deviceId);
                settings.pairingCode = sync.optString("pairingCode", "").trim();
                settings.pairedDeviceName = sync.optString("pairedDeviceName", "").trim();
                settings.syncEndpoint = sync.optString("syncEndpoint", "").trim();
                settings.syncEnabled = sync.optBoolean("syncEnabled", false);
                settings.lastSyncAt = sync.optLong("lastSyncAt", 0);
                if (settings.lastSyncAt < 0) throw new IllegalArgumentException("lastSyncAt must not be negative");
            }
            settings.updatedAt = root.optLong("updatedAt", System.currentTimeMillis());
            if (settings.updatedAt <= 0) settings.updatedAt = System.currentTimeMillis();
            return settings;
        } catch (IllegalArgumentException exception) {
            throw new JSONException("invalid settings: " + exception.getMessage());
        }
    }

    public static boolean containsApiKey(JSONObject root) {
        if (root == null) return false;
        JSONObject ai = root.optJSONObject("ai");
        return ai != null && ai.has("apiKey") && !ai.isNull("apiKey");
    }

    private void touch() {
        updatedAt = Math.max(
                System.currentTimeMillis(),
                updatedAt == Long.MAX_VALUE ? Long.MAX_VALUE : updatedAt + 1
        );
    }

    private void normalizeFocusAutomation() {
        if (!autoStartNextRound) {
            autoStartBreak = false;
            autoStartNextPomodoro = false;
        }
        if (breaksDisabled) autoStartBreak = false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nonEmptyOr(String value, String fallback) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static int positiveDuration(int value, String name) {
        if (value <= 0 || value > MINUTES_PER_DAY) {
            throw new IllegalArgumentException(name + " must be between 1 and " + MINUTES_PER_DAY);
        }
        return value;
    }

    private static int cycleCount(int value, String name) {
        if (value <= 0 || value > 20) {
            throw new IllegalArgumentException(name + " must be between 1 and 20");
        }
        return value;
    }

    private static void validateWindow(int start, int end, String name) {
        validateMinute(start, false, name + " start");
        validateMinute(end, true, name + " end");
        if (start == end) throw new IllegalArgumentException(name + " must have a non-zero duration");
    }

    private static void validateMinute(int value, boolean allowEndOfDay, String name) {
        int maximum = allowEndOfDay ? MINUTES_PER_DAY : MINUTES_PER_DAY - 1;
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException(name + " must be between 0 and " + maximum);
        }
    }
}
