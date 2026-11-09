package com.hawson.timeassistant.ui.schedule;

/** Pure geometry/state model. Distances use the same unit (pixels in the Android host). */
public final class ScheduleSwipeGesture {
    public enum Release { CLOSED, OPEN, COMMIT }
    private final float reveal;
    private final float threshold;
    private final float hysteresis;
    private float distance;
    private boolean active;
    private boolean armed;
    private boolean hapticSent;

    public ScheduleSwipeGesture(float actionWidth, int actionCount, float rowWidth,
            float minimumExtra, float hysteresis) {
        if (actionWidth <= 0 || actionCount < 1 || rowWidth <= 0 || minimumExtra < 0 || hysteresis < 0) {
            throw new IllegalArgumentException("Invalid swipe geometry");
        }
        reveal = actionWidth * actionCount;
        threshold = reveal + Math.min(minimumExtra, rowWidth * .25f);
        this.hysteresis = hysteresis;
    }

    public void begin(boolean open) {
        active = true;
        armed = false;
        hapticSent = false;
        distance = open ? reveal : 0;
    }

    /** Returns true once per gesture, on the first transition into armed. */
    public boolean move(float absoluteRevealDistance) {
        if (!active) return false;
        float travel = Math.max(0, absoluteRevealDistance);
        distance = travel <= reveal ? travel : reveal + (travel - reveal) * .55f;
        if (armed && distance < threshold - hysteresis) armed = false;
        else if (!armed && distance >= threshold) armed = true;
        if (!armed || hapticSent) return false;
        hapticSent = true;
        return true;
    }

    public Release release() {
        if (!active) return Release.CLOSED;
        active = false;
        Release result = armed ? Release.COMMIT : distance >= reveal * .5f ? Release.OPEN : Release.CLOSED;
        armed = false;
        distance = result == Release.OPEN ? reveal : 0;
        return result;
    }

    public void cancel() { active = false; armed = false; distance = 0; }
    public float getDistance() { return distance; }
    public float getReveal() { return reveal; }
    public float getThreshold() { return threshold; }
    public boolean isArmed() { return armed; }
}
