package com.hawson.timeassistant.ui;

/** Pure geometry. Preview is separate from persistence and cancellation never submits. */
final class FocusDialGesture {
    enum Axis { NONE, HORIZONTAL, VERTICAL }
    private final float slop, step, horizontalThreshold;
    private float downX, downY, lastY, remainder;
    private int initial, value, maximum;
    private boolean active, moved;
    private Axis axis = Axis.NONE;

    FocusDialGesture(float slop, float step, float horizontalThreshold) {
        this.slop = slop; this.step = step; this.horizontalThreshold = horizontalThreshold;
    }

    void begin(float x, float y, int minutes, int maximum) {
        downX = x; downY = lastY = y; initial = value = minutes; this.maximum = maximum;
        remainder = 0; active = true; moved = false; axis = Axis.NONE;
    }

    boolean move(float x, float y) {
        if (!active) return false;
        float dx = x - downX, dy = y - downY;
        if (Math.max(Math.abs(dx), Math.abs(dy)) > slop) moved = true;
        if (axis == Axis.NONE && moved) {
            if (Math.abs(dy) > Math.abs(dx) * 1.25f) {
                axis = Axis.VERTICAL;
                remainder = downY - lastY;
            }
            else if (Math.abs(dx) > Math.abs(dy) * 1.25f) axis = Axis.HORIZONTAL;
        }
        int before = value;
        if (axis == Axis.VERTICAL) {
            remainder += lastY - y;
            int delta = (int) (remainder / step);
            value = Math.max(1, Math.min(maximum, value + delta));
            remainder -= delta * step;
            if ((value == maximum && remainder > 0) || (value == 1 && remainder < 0)) remainder = 0;
        }
        lastY = y;
        return before != value;
    }

    Result end(float x, float y) {
        if (!active) return new Result(false, 0, 0);
        move(x, y);
        active = false;
        int direction = axis == Axis.HORIZONTAL && Math.abs(x - downX) >= horizontalThreshold
                ? (x < downX ? 1 : -1) : 0;
        return new Result(!moved, axis == Axis.VERTICAL && value != initial ? value : 0, direction);
    }

    void cancel() { active = false; }
    boolean isActive() { return active; }
    boolean isPreviewing() { return active && axis == Axis.VERTICAL; }
    int value() { return value; }

    static final class Result {
        final boolean tap;
        final int minutes, modeDirection;
        Result(boolean tap, int minutes, int modeDirection) {
            this.tap = tap; this.minutes = minutes; this.modeDirection = modeDirection;
        }
    }
}
