package com.hawson.timeassistant.design;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Decorative ring opacity only; owns no timer state or progress. */
public final class RingAmbientMotion {
    public static final long PERIOD_MILLIS = 4_800;
    public static final float REST_ALPHA = .06f, PEAK_ALPHA = .14f;
    private final View owner;
    private ValueAnimator animator;
    private MotionHandle settling;
    private float opacity = REST_ALPHA;

    public RingAmbientMotion(View owner) { this.owner = owner; }

    public void update(boolean running, boolean visible) {
        if (!visible || !MotionSpec.full(owner.getContext())) { stop(); return; }
        if (!running) { settle(); return; }
        if (animator != null) return;
        if (settling != null) { settling.cancel(); settling = null; }
        float level = Math.max(0, Math.min(1, (opacity - REST_ALPHA) / (PEAK_ALPHA - REST_ALPHA)));
        long phase = Math.round(Math.acos(1 - 2 * level) / (2 * Math.PI) * PERIOD_MILLIS);
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(PERIOD_MILLIS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            if (!MotionSpec.full(owner.getContext()) || !owner.isShown()
                    || owner.getWindowVisibility() != View.VISIBLE) { stop(); return; }
            float fraction = (float) value.getAnimatedValue();
            opacity = REST_ALPHA + (PEAK_ALPHA - REST_ALPHA)
                    * (float) (.5 - .5 * Math.cos(fraction * 2 * Math.PI));
            owner.invalidate();
        });
        animator.start();
        animator.setCurrentPlayTime(phase);
    }

    private void settle() {
        if (animator != null) { animator.cancel(); animator = null; }
        if (settling != null && settling.isRunning() || opacity == REST_ALPHA) return;
        float previous = opacity;
        settling = MotionSpec.animate(owner, MotionSpec.RELEASE,
                fraction -> { opacity = previous + (REST_ALPHA - previous) * fraction; owner.invalidate(); });
    }

    public float opacity() { return opacity; }
    public boolean isRunning() { return animator != null && animator.isRunning(); }

    public void stop() {
        if (animator != null) { animator.cancel(); animator = null; }
        if (settling != null) { settling.cancel(); settling = null; }
        opacity = REST_ALPHA;
        owner.invalidate();
    }
}
