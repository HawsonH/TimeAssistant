/**
 * 当前专注状态
 *
 * 正在进行的番茄钟会话：模式、剩余时间、暂停/运行状态等，支持从时间戳推算实时剩余。
 */
package com.hawson.timeassistant.domain;

import com.hawson.timeassistant.util.JsonInterop;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Persisted state of the current focus timer. When running, {@code remainingMillis} is the
 * value at {@code lastStartedAt}; {@link #remainingAt(long)} derives the live remaining time.
 */
public final class FocusState {
    public enum Mode {
        FOCUS("focus"), SHORT_BREAK("short_break"), LONG_BREAK("long_break");

        private final String storageValue;
        Mode(String storageValue) { this.storageValue = storageValue; }
        public String storageValue() { return storageValue; }
        public static Mode fromStorage(String raw) {
            if (raw != null) {
                String normalized = raw.trim().toLowerCase(Locale.ROOT);
                for (Mode value : values()) {
                    if (value.storageValue.equals(normalized)
                            || value.name().toLowerCase(Locale.ROOT).equals(normalized)) return value;
                }
            }
            return FOCUS;
        }
    }

    public enum State {
        IDLE("idle"), READY("ready"), RUNNING("running"), PAUSED("paused"), FINISHED("finished");

        private final String storageValue;
        State(String storageValue) { this.storageValue = storageValue; }
        public String storageValue() { return storageValue; }
        public static State fromStorage(String raw) {
            if (raw != null) {
                String normalized = raw.trim().toLowerCase(Locale.ROOT);
                for (State value : values()) {
                    if (value.storageValue.equals(normalized)
                            || value.name().toLowerCase(Locale.ROOT).equals(normalized)) return value;
                }
            }
            return IDLE;
        }
    }

    private final String taskId;
    private final Mode mode;
    private final long totalMillis;
    private final long remainingMillis;
    private final State state;
    private final long lastStartedAt;
    private final int interruptionCount;
    private final int pomodoroIndex;
    private final long updatedAt;
    private final String sessionId;
    private final String sessionLabel;
    private final boolean naturalCompletionRecorded;

    public FocusState(
            String taskId,
            Mode mode,
            long totalMillis,
            long remainingMillis,
            State state,
            long lastStartedAt,
            int interruptionCount,
            int pomodoroIndex,
            long updatedAt
    ) {
        this(taskId, mode, totalMillis, remainingMillis, state, lastStartedAt,
                interruptionCount, pomodoroIndex, updatedAt,
                state == State.IDLE ? "" : UUID.randomUUID().toString(), "", false);
    }

    private FocusState(
            String taskId,
            Mode mode,
            long totalMillis,
            long remainingMillis,
            State state,
            long lastStartedAt,
            int interruptionCount,
            int pomodoroIndex,
            long updatedAt,
            String sessionId,
            String sessionLabel,
            boolean naturalCompletionRecorded
    ) {
        this.taskId = normalizeTaskId(taskId);
        this.mode = mode == null ? Mode.FOCUS : mode;
        if (totalMillis < 0) throw new IllegalArgumentException("totalMillis must not be negative");
        if (remainingMillis < 0 || remainingMillis > totalMillis) {
            throw new IllegalArgumentException("remainingMillis must be between zero and totalMillis");
        }
        this.totalMillis = totalMillis;
        this.remainingMillis = remainingMillis;
        this.state = state == null ? State.IDLE : state;
        if (lastStartedAt < 0) throw new IllegalArgumentException("lastStartedAt must not be negative");
        if (this.state == State.RUNNING && lastStartedAt == 0) {
            throw new IllegalArgumentException("a running focus requires lastStartedAt");
        }
        if (interruptionCount < 0) throw new IllegalArgumentException("interruptionCount must not be negative");
        if (pomodoroIndex < 0) throw new IllegalArgumentException("pomodoroIndex must not be negative");
        this.lastStartedAt = lastStartedAt;
        this.interruptionCount = interruptionCount;
        this.pomodoroIndex = pomodoroIndex;
        this.updatedAt = updatedAt > 0 ? updatedAt : System.currentTimeMillis();
        this.sessionId = this.state == State.IDLE ? "" : requireSessionId(sessionId);
        this.sessionLabel = normalizeLabel(sessionLabel);
        this.naturalCompletionRecorded = this.state != State.IDLE
                && naturalCompletionRecorded;
    }

    public static FocusState idle() {
        return new FocusState(null, Mode.FOCUS, 0, 0, State.IDLE, 0, 0, 0,
                System.currentTimeMillis());
    }

    public static FocusState ready(String taskId, Mode mode, long totalMillis, int pomodoroIndex) {
        return ready(taskId, mode, totalMillis, pomodoroIndex, "");
    }

    public static FocusState ready(String taskId, Mode mode, long totalMillis, int pomodoroIndex,
                                   String sessionLabel) {
        if (totalMillis <= 0) throw new IllegalArgumentException("totalMillis must be greater than zero");
        return new FocusState(taskId, mode, totalMillis, totalMillis, State.READY, 0, 0,
                pomodoroIndex, System.currentTimeMillis(), UUID.randomUUID().toString(),
                sessionLabel, false);
    }

    public String getTaskId() { return taskId; }
    public Mode getMode() { return mode; }
    public long getTotalMillis() { return totalMillis; }
    public long getRemainingMillis() { return remainingMillis; }
    public State getState() { return state; }
    public long getLastStartedAt() { return lastStartedAt; }
    public int getInterruptionCount() { return interruptionCount; }
    public int getPomodoroIndex() { return pomodoroIndex; }
    public long getUpdatedAt() { return updatedAt; }
    public String getSessionId() { return sessionId; }
    public String getSessionLabel() { return sessionLabel; }
    public boolean isNaturalCompletionRecorded() { return naturalCompletionRecorded; }

    public long remainingAt(long now) {
        if (state != State.RUNNING) return remainingMillis;
        long elapsed = Math.max(0, now - lastStartedAt);
        return Math.max(0, remainingMillis - elapsed);
    }

    public FocusState startOrResume(long now) {
        if (now <= 0) throw new IllegalArgumentException("now must be greater than zero");
        if (state == State.RUNNING) return this;
        if (state == State.FINISHED || remainingMillis == 0) {
            throw new IllegalStateException("a finished focus cannot be resumed");
        }
        return new FocusState(taskId, mode, totalMillis, remainingMillis, State.RUNNING, now,
                interruptionCount, pomodoroIndex, now, sessionId, sessionLabel,
                naturalCompletionRecorded);
    }

    public FocusState pause(long now) {
        if (state != State.RUNNING) return this;
        long remaining = remainingAt(now);
        State nextState = remaining == 0 ? State.FINISHED : State.PAUSED;
        return new FocusState(taskId, mode, totalMillis, remaining, nextState, 0,
                interruptionCount + (remaining == 0 ? 0 : 1), pomodoroIndex, now,
                sessionId, sessionLabel, naturalCompletionRecorded);
    }

    public FocusState finish(long now) {
        if (now <= 0) throw new IllegalArgumentException("now must be greater than zero");
        return new FocusState(taskId, mode, totalMillis, 0, State.FINISHED, 0,
                interruptionCount, pomodoroIndex, now, sessionId, sessionLabel,
                naturalCompletionRecorded);
    }

    public FocusState markNaturalCompletionRecorded(long now) {
        if (now <= 0) throw new IllegalArgumentException("now must be greater than zero");
        return new FocusState(taskId, mode, totalMillis, 0, State.FINISHED, 0,
                interruptionCount, pomodoroIndex, now, sessionId, sessionLabel, true);
    }

    /** Produces a stable snapshot suitable for export or synchronization. */
    public FocusState snapshotAt(long now) {
        if (state != State.RUNNING) return this;
        long remaining = remainingAt(now);
        if (remaining == 0) return finish(now);
        return new FocusState(taskId, mode, totalMillis, remaining, State.RUNNING, now,
                interruptionCount, pomodoroIndex, now, sessionId, sessionLabel,
                naturalCompletionRecorded);
    }

    public JSONObject toJson() throws JSONException {
        return JsonInterop.toJsonObject(toJsonValue());
    }

    Map<String, Object> toJsonValue() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("taskId", taskId);
        json.put("mode", mode.storageValue());
        json.put("totalMillis", totalMillis);
        json.put("remainingMillis", remainingMillis);
        json.put("state", state.storageValue());
        json.put("lastStartedAt", lastStartedAt);
        json.put("interruptionCount", interruptionCount);
        json.put("pomodoroIndex", pomodoroIndex);
        json.put("updatedAt", updatedAt);
        json.put("sessionId", sessionId);
        json.put("sessionLabel", sessionLabel);
        json.put("naturalCompletionRecorded", naturalCompletionRecorded);
        return Collections.unmodifiableMap(json);
    }

    public static FocusState fromJson(JSONObject json) throws JSONException {
        if (json == null) return idle();
        long updatedAt = json.optLong("updatedAt", System.currentTimeMillis());
        State parsedState = State.fromStorage(json.optString("state", State.IDLE.storageValue()));
        // Old snapshots used PAUSED for a timer that had never started.
        if (parsedState == State.PAUSED && json.optLong("lastStartedAt", 0) == 0
                && json.optInt("interruptionCount", 0) == 0
                && json.optLong("remainingMillis", 0) == json.optLong("totalMillis", 0)
                && json.optLong("totalMillis", 0) > 0) {
            parsedState = State.READY;
        }
        String parsedTaskId = null;
        if (json.has("taskId") && !json.isNull("taskId")) {
            parsedTaskId = json.optString("taskId", null);
        }
        String sessionId = json.optString("sessionId", "").trim();
        if (parsedState != State.IDLE && sessionId.isEmpty()) {
            String stable = String.valueOf(parsedTaskId) + '|' + json.optString("mode", "focus")
                    + '|' + json.optLong("totalMillis", 0) + '|' + updatedAt;
            sessionId = "legacy-" + Long.toHexString(updatedAt) + '-'
                    + Integer.toHexString(stable.hashCode());
        }
        try {
            return new FocusState(
                    parsedTaskId,
                    Mode.fromStorage(json.optString("mode", Mode.FOCUS.storageValue())),
                    json.optLong("totalMillis", 0),
                    json.optLong("remainingMillis", 0),
                    parsedState,
                    json.optLong("lastStartedAt", 0),
                    json.optInt("interruptionCount", 0),
                    json.optInt("pomodoroIndex", 0),
                    updatedAt,
                    sessionId,
                    json.optString("sessionLabel", ""),
                    json.optBoolean("naturalCompletionRecorded", false)
            );
        } catch (IllegalArgumentException exception) {
            throw new JSONException("invalid current focus: " + exception.getMessage());
        }
    }

    private static String normalizeTaskId(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String requireSessionId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("sessionId must not be empty");
        return normalized;
    }

    private static String normalizeLabel(String value) {
        return value == null ? "" : value.trim();
    }
}
