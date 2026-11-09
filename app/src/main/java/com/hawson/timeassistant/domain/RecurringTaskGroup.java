package com.hawson.timeassistant.domain;

import com.hawson.timeassistant.util.JsonInterop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A recurrence template. Future occurrences are projected and only become {@link TaskItem}s when
 * real user behavior happens. Rule edits therefore never overwrite concrete historical tasks.
 */
public final class RecurringTaskGroup {
    public enum Unit {
        DAYS("days"), WEEKS("weeks"), MONTHS("months");

        private final String storageValue;
        Unit(String storageValue) { this.storageValue = storageValue; }
        public String storageValue() { return storageValue; }

        static Unit fromStorage(String value) {
            if (value == null) return WEEKS;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Unit unit : values()) {
                if (unit.storageValue.equals(normalized)
                        || unit.name().toLowerCase(Locale.ROOT).equals(normalized)) return unit;
            }
            return WEEKS;
        }
    }

    private final String id;
    private String title;
    private String description;
    private long anchorStartAt;
    private long durationMillis;
    private Unit unit;
    private int interval;
    private boolean workdaysOnly;
    private final LinkedHashSet<Integer> weekdays = new LinkedHashSet<>();
    private int monthlyOrdinal;
    private int monthlyWeekday;
    private long endsAt;
    private int maxOccurrences;
    private boolean active;
    private final long createdAt;
    private long updatedAt;

    public RecurringTaskGroup(String title, long anchorStartAt, long durationMillis,
                              Unit unit, int interval) {
        this(UUID.randomUUID().toString(), title, "", anchorStartAt, durationMillis,
                unit, interval, false, Collections.emptyList(), 0, 0,
                0, 0, true, System.currentTimeMillis(), System.currentTimeMillis());
    }

    private RecurringTaskGroup(String id, String title, String description, long anchorStartAt,
                               long durationMillis, Unit unit, int interval,
                               boolean workdaysOnly, Collection<Integer> weekdays,
                               int monthlyOrdinal, int monthlyWeekday,
                               long endsAt, int maxOccurrences, boolean active,
                               long createdAt, long updatedAt) {
        this.id = requireText(id, "id");
        this.title = requireText(title, "title");
        this.description = description == null ? "" : description;
        validateBaseRule(anchorStartAt, durationMillis, interval);
        validateAdvancedRule(monthlyOrdinal, monthlyWeekday, endsAt, maxOccurrences);
        this.anchorStartAt = anchorStartAt;
        this.durationMillis = durationMillis;
        this.unit = unit == null ? Unit.WEEKS : unit;
        this.interval = interval;
        this.workdaysOnly = workdaysOnly;
        replaceWeekdays(weekdays);
        this.monthlyOrdinal = monthlyOrdinal;
        this.monthlyWeekday = monthlyWeekday;
        this.endsAt = endsAt;
        this.maxOccurrences = maxOccurrences;
        this.active = active;
        long now = System.currentTimeMillis();
        this.createdAt = createdAt > 0 ? createdAt : now;
        this.updatedAt = Math.max(updatedAt > 0 ? updatedAt : this.createdAt, this.createdAt);
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public long getAnchorStartAt() { return anchorStartAt; }
    public long getDurationMillis() { return durationMillis; }
    public Unit getUnit() { return unit; }
    public int getInterval() { return interval; }
    public boolean isWorkdaysOnly() { return workdaysOnly; }
    public Set<Integer> getWeekdays() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(weekdays));
    }
    public int getMonthlyOrdinal() { return monthlyOrdinal; }
    public int getMonthlyWeekday() { return monthlyWeekday; }
    public long getEndsAt() { return endsAt; }
    public int getMaxOccurrences() { return maxOccurrences; }
    public boolean isActive() { return active; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }

    public RecurringTaskGroup setTitle(String value) {
        title = requireText(value, "title"); touch(); return this;
    }

    public RecurringTaskGroup setDescription(String value) {
        description = value == null ? "" : value; touch(); return this;
    }

    public RecurringTaskGroup setRule(long startAt, long duration, Unit valueUnit,
                                      int valueInterval) {
        validateBaseRule(startAt, duration, valueInterval);
        anchorStartAt = startAt;
        durationMillis = duration;
        unit = valueUnit == null ? Unit.WEEKS : valueUnit;
        interval = valueInterval;
        touch();
        return this;
    }

    /** Sets weekday/monthly variants and one of the optional end conditions. */
    public RecurringTaskGroup setAdvancedRule(boolean valueWorkdaysOnly,
                                               Collection<Integer> valueWeekdays,
                                               int valueMonthlyOrdinal,
                                               int valueMonthlyWeekday,
                                               long valueEndsAt,
                                               int valueMaxOccurrences) {
        validateAdvancedRule(valueMonthlyOrdinal, valueMonthlyWeekday,
                valueEndsAt, valueMaxOccurrences);
        workdaysOnly = valueWorkdaysOnly;
        replaceWeekdays(valueWeekdays);
        monthlyOrdinal = valueMonthlyOrdinal;
        monthlyWeekday = valueMonthlyWeekday;
        endsAt = valueEndsAt;
        maxOccurrences = valueMaxOccurrences;
        touch();
        return this;
    }

    public RecurringTaskGroup setEndCondition(long valueEndsAt, int valueMaxOccurrences) {
        validateAdvancedRule(monthlyOrdinal, monthlyWeekday, valueEndsAt, valueMaxOccurrences);
        endsAt = valueEndsAt;
        maxOccurrences = valueMaxOccurrences;
        touch();
        return this;
    }

    public RecurringTaskGroup setActive(boolean value) { active = value; touch(); return this; }

    /** Creates the new half used by “this and following”; the current group remains untouched. */
    public RecurringTaskGroup forkFrom(long newAnchorStartAt) {
        RecurringTaskGroup fork = new RecurringTaskGroup(title, newAnchorStartAt,
                durationMillis, unit, interval);
        fork.description = description;
        fork.workdaysOnly = workdaysOnly;
        fork.replaceWeekdays(weekdays);
        fork.monthlyOrdinal = monthlyOrdinal;
        fork.monthlyWeekday = monthlyWeekday;
        fork.endsAt = endsAt;
        fork.maxOccurrences = 0;
        return fork;
    }

    /** Returns the occurrence start on the supplied local calendar day, or zero when none. */
    public long occurrenceStartOnDay(long dayAt) {
        if (!active || dayAt <= 0) return 0;
        Calendar anchor = Calendar.getInstance();
        anchor.setTimeInMillis(anchorStartAt);
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(dayAt);
        Calendar anchorDay = dayAtNoon(anchor);
        Calendar targetDay = dayAtNoon(target);
        if (targetDay.before(anchorDay)) return 0;
        if (endsAt > 0) {
            Calendar endDay = Calendar.getInstance();
            endDay.setTimeInMillis(endsAt);
            if (targetDay.after(dayAtNoon(endDay))) return 0;
        }
        if (!matchesDayIgnoringLimits(anchorDay, targetDay)) return 0;
        if (maxOccurrences > 0 && occurrenceIndex(anchorDay, targetDay) > maxOccurrences) return 0;
        target.set(Calendar.HOUR_OF_DAY, anchor.get(Calendar.HOUR_OF_DAY));
        target.set(Calendar.MINUTE, anchor.get(Calendar.MINUTE));
        target.set(Calendar.SECOND, anchor.get(Calendar.SECOND));
        target.set(Calendar.MILLISECOND, anchor.get(Calendar.MILLISECOND));
        return target.getTimeInMillis();
    }

    private boolean matchesDayIgnoringLimits(Calendar anchorDay, Calendar targetDay) {
        if (workdaysOnly) {
            int weekday = targetDay.get(Calendar.DAY_OF_WEEK);
            return weekday != Calendar.SATURDAY && weekday != Calendar.SUNDAY;
        }
        if (unit == Unit.MONTHS) {
            int months = monthDifference(anchorDay, targetDay);
            if (months % interval != 0) return false;
            if (monthlyOrdinal != 0) {
                return targetDay.get(Calendar.DAY_OF_WEEK) == monthlyWeekday
                        && ordinalInMonth(targetDay) == monthlyOrdinal;
            }
            int expectedDay = Math.min(anchorDay.get(Calendar.DAY_OF_MONTH),
                    targetDay.getActualMaximum(Calendar.DAY_OF_MONTH));
            return targetDay.get(Calendar.DAY_OF_MONTH) == expectedDay;
        }
        long days = dayDifference(anchorDay, targetDay);
        if (unit == Unit.WEEKS && !weekdays.isEmpty()) {
            long weeks = weekDifference(anchorDay, targetDay);
            return weeks % interval == 0 && weekdays.contains(targetDay.get(Calendar.DAY_OF_WEEK));
        }
        long period = unit == Unit.WEEKS ? interval * 7L : interval;
        return days % period == 0;
    }

    private int occurrenceIndex(Calendar anchorDay, Calendar targetDay) {
        Calendar cursor = (Calendar) anchorDay.clone();
        int count = 0;
        while (!cursor.after(targetDay)) {
            if (matchesDayIgnoringLimits(anchorDay, cursor)) count++;
            if (count > maxOccurrences) return count;
            cursor.add(Calendar.DAY_OF_YEAR, 1);
        }
        return count;
    }

    public RecurringTaskGroup copy() {
        return new RecurringTaskGroup(id, title, description, anchorStartAt, durationMillis,
                unit, interval, workdaysOnly, weekdays, monthlyOrdinal, monthlyWeekday,
                endsAt, maxOccurrences, active, createdAt, updatedAt);
    }

    public RecurringTaskGroup markUpdatedAfter(long timestamp) {
        updatedAt = Math.max(updatedAt, timestamp == Long.MAX_VALUE
                ? Long.MAX_VALUE : timestamp + 1L);
        return this;
    }

    public JSONObject toJson() throws JSONException {
        return JsonInterop.toJsonObject(toJsonValue());
    }

    Map<String, Object> toJsonValue() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("title", title);
        json.put("description", description);
        json.put("anchorStartAt", anchorStartAt);
        json.put("durationMillis", durationMillis);
        json.put("unit", unit.storageValue());
        json.put("interval", interval);
        json.put("workdaysOnly", workdaysOnly);
        json.put("weekdays", new ArrayList<>(weekdays));
        json.put("monthlyOrdinal", monthlyOrdinal);
        json.put("monthlyWeekday", monthlyWeekday);
        json.put("endsAt", endsAt);
        json.put("maxOccurrences", maxOccurrences);
        json.put("active", active);
        json.put("createdAt", createdAt);
        json.put("updatedAt", updatedAt);
        return Collections.unmodifiableMap(json);
    }

    public static RecurringTaskGroup fromJson(JSONObject json) throws JSONException {
        if (json == null) throw new JSONException("recurring task group JSON must not be null");
        long now = System.currentTimeMillis();
        List<Integer> storedWeekdays = new ArrayList<>();
        JSONArray weekdayArray = json.optJSONArray("weekdays");
        if (weekdayArray != null) {
            for (int index = 0; index < weekdayArray.length(); index++) {
                storedWeekdays.add(weekdayArray.optInt(index, 0));
            }
        }
        try {
            return new RecurringTaskGroup(
                    json.optString("id", UUID.randomUUID().toString()),
                    json.optString("title", ""), json.optString("description", ""),
                    json.optLong("anchorStartAt", 0), json.optLong("durationMillis", 0),
                    Unit.fromStorage(json.optString("unit", "weeks")),
                    json.optInt("interval", 1), json.optBoolean("workdaysOnly", false),
                    storedWeekdays, json.optInt("monthlyOrdinal", 0),
                    json.optInt("monthlyWeekday", 0), json.optLong("endsAt", 0),
                    json.optInt("maxOccurrences", 0), json.optBoolean("active", true),
                    json.optLong("createdAt", now),
                    json.optLong("updatedAt", json.optLong("createdAt", now)));
        } catch (IllegalArgumentException error) {
            throw new JSONException("invalid recurring task group: " + error.getMessage());
        }
    }

    private void replaceWeekdays(Collection<Integer> values) {
        weekdays.clear();
        if (values == null) return;
        for (Integer value : values) {
            if (value == null || value < Calendar.SUNDAY || value > Calendar.SATURDAY) {
                throw new IllegalArgumentException("weekday must use Calendar.SUNDAY..SATURDAY");
            }
            weekdays.add(value);
        }
    }

    private static void validateBaseRule(long startAt, long duration, int valueInterval) {
        if (startAt <= 0) throw new IllegalArgumentException("anchorStartAt must be positive");
        if (duration <= 0) throw new IllegalArgumentException("durationMillis must be positive");
        if (valueInterval <= 0) throw new IllegalArgumentException("interval must be positive");
    }

    private static void validateAdvancedRule(int ordinal, int weekday, long endAt, int count) {
        if (ordinal < 0 || ordinal > 5) {
            throw new IllegalArgumentException("monthlyOrdinal must be 0..5");
        }
        if (ordinal > 0 && (weekday < Calendar.SUNDAY || weekday > Calendar.SATURDAY)) {
            throw new IllegalArgumentException("monthlyWeekday is required for ordinal rules");
        }
        if (endAt < 0) throw new IllegalArgumentException("endsAt cannot be negative");
        if (count < 0) throw new IllegalArgumentException("maxOccurrences cannot be negative");
    }

    private static int monthDifference(Calendar anchor, Calendar target) {
        return (target.get(Calendar.YEAR) - anchor.get(Calendar.YEAR)) * 12
                + target.get(Calendar.MONTH) - anchor.get(Calendar.MONTH);
    }

    private static long dayDifference(Calendar anchor, Calendar target) {
        return Math.round((target.getTimeInMillis() - anchor.getTimeInMillis()) / 86_400_000d);
    }

    private static long weekDifference(Calendar anchor, Calendar target) {
        Calendar first = startOfIsoWeek(anchor);
        Calendar second = startOfIsoWeek(target);
        return Math.round((second.getTimeInMillis() - first.getTimeInMillis())
                / (7d * 86_400_000d));
    }

    private static Calendar startOfIsoWeek(Calendar value) {
        Calendar result = dayAtNoon(value);
        int offset = result.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                ? 6 : result.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY;
        result.add(Calendar.DAY_OF_YEAR, -offset);
        return result;
    }

    private static int ordinalInMonth(Calendar value) {
        return ((value.get(Calendar.DAY_OF_MONTH) - 1) / 7) + 1;
    }

    private void touch() {
        updatedAt = Math.max(System.currentTimeMillis(),
                updatedAt == Long.MAX_VALUE ? Long.MAX_VALUE : updatedAt + 1L);
    }

    private static Calendar dayAtNoon(Calendar value) {
        Calendar copy = (Calendar) value.clone();
        copy.set(Calendar.HOUR_OF_DAY, 12);
        copy.set(Calendar.MINUTE, 0);
        copy.set(Calendar.SECOND, 0);
        copy.set(Calendar.MILLISECOND, 0);
        return copy;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
