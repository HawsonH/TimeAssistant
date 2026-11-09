package com.hawson.timeassistant.design;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import java.util.function.Consumer;

/** Cancel preserves the current frame; finish applies the target. Callbacks are visual-only. */
public final class MotionHandle {
    private final ValueAnimator animator;
    private final Consumer<Float> frame;
    private final Runnable completed;
    private final Runnable disposed;
    private boolean active = true;

    MotionHandle(long duration, Consumer<Float> frame, Runnable completed, Runnable disposed,
                 java.util.function.BooleanSupplier allowed) {
        this.frame = frame; this.completed = completed; this.disposed = disposed;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setInterpolator(MotionSpec.EASE_OUT);
        animator.addUpdateListener(value -> {
            if (!active) return;
            if (!allowed.getAsBoolean()) finish();
            else frame.accept((float) value.getAnimatedValue());
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ignored) { finish(); }
        });
    }
    void start() { if (animator.getDuration() == 0) finish(); else { frame.accept(0f); animator.start(); } }
    public boolean isRunning() { return active; }
    public void cancel() {
        if (!active) return;
        active = false; animator.cancel(); disposed.run();
    }
    public void finish() {
        if (!active) return;
        active = false; animator.cancel(); frame.accept(1f); disposed.run(); completed.run();
    }
    /** Deterministic intermediate-frame capture without host scheduling assumptions. */
    public void seekTo(long millis) { if (active) animator.setCurrentPlayTime(millis); }
}
