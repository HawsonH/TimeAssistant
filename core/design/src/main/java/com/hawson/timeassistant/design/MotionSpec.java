package com.hawson.timeassistant.design;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Visual state is owned by the component, never by a shared ViewPropertyAnimator. */
public final class MotionSpec {
    public enum Mode { FULL, REDUCED, OFF }
    public interface Owner { void stopMotion(); }
    public static final long PRESS = 90, RELEASE = 160, CONTENT = 180, INDICATOR = 220, SETTLE = 240;
    public static final TimeInterpolator EASE_OUT = new PathInterpolator(.2f, 0f, 0f, 1f);
    private MotionSpec() { }

    public static boolean full(Context context) {
        return duration(context, 100) > 0
                && new AppearancePreferences(context).motionMode() == Mode.FULL;
    }

    public static void configureDialog(android.app.Dialog dialog) {
        if (!full(dialog.getContext()) && dialog.getWindow() != null)
            dialog.getWindow().setWindowAnimations(0);
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    public static void press(View view) {
        if (view instanceof ViewGroup) return;
        State state = state(view);
        view.setOnTouchListener((target, event) -> {
            if (!target.isEnabled()) { state.restorePress(target); return false; }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && full(target.getContext())) {
                if (!state.pressing) {
                    state.baseScaleX = target.getScaleX(); state.baseScaleY = target.getScaleY();
                    state.pressing = true;
                }
                state.scale(target, .985f, PRESS, false);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_DOWN) {
                state.scale(target, 1f, RELEASE, true);
            }
            return false;
        });
    }

    public static void feedback(View view) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        // Progress, disabled opacity and entry motion are not feedback properties.
    }

    public static long duration(Context context, long normalMillis) {
        boolean systemEnabled = Build.VERSION.SDK_INT >= 26
                ? ValueAnimator.areAnimatorsEnabled()
                : Settings.Global.getFloat(context.getContentResolver(),
                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        Mode mode = new AppearancePreferences(context).motionMode();
        if (!systemEnabled || mode == Mode.OFF) return 0L;
        return mode == Mode.REDUCED ? Math.min(100L, normalMillis)
                : Math.min(800L, normalMillis);
    }

    public static MotionHandle animate(View owner, long millis, Consumer<Float> frame, Runnable completed) {
        State state = state(owner);
        MotionHandle[] holder = new MotionHandle[1];
        boolean startedFull = full(owner.getContext());
        MotionHandle handle = new MotionHandle(duration(owner.getContext(), millis), frame, completed,
                () -> state.handles.remove(holder[0]), () -> duration(owner.getContext(), millis) > 0
                        && (!startedFull || full(owner.getContext())));
        holder[0] = handle;
        state.handles.add(handle);
        handle.start();
        return handle;
    }
    public static MotionHandle animate(View owner, long millis, Consumer<Float> frame) {
        return animate(owner, millis, frame, () -> { });
    }
    public static void enter(View view) { enter(view, false, 1); }
    public static void enterHorizontal(View view, int direction) { enter(view, true, direction); }
    private static void enter(View view, boolean horizontal, int direction) {
        State state = state(view);
        boolean continuing = state.entry != null && state.entry.isRunning();
        if (continuing && state.horizontal != horizontal) { state.entry.finish(); continuing = false; }
        if (!continuing) {
            state.baseAlpha = view.getAlpha();
            state.baseTranslation = horizontal ? view.getTranslationX() : view.getTranslationY();
            state.horizontal = horizontal;
            if (duration(view.getContext(), CONTENT) == 0) return;
            view.setAlpha(horizontal ? state.baseAlpha * .8f : 0f);
            float offset = full(view.getContext()) ? UiKit.dp(view.getContext(), 4) * direction : 0;
            if (horizontal) view.setTranslationX(state.baseTranslation + offset);
            else view.setTranslationY(state.baseTranslation + offset);
        } else state.entry.cancel();
        float alpha = view.getAlpha();
        float translation = horizontal ? view.getTranslationX() : view.getTranslationY();
        state.entry = animate(view, CONTENT, fraction -> {
            view.setAlpha(alpha + (state.baseAlpha - alpha) * fraction);
            float position = translation + (state.baseTranslation - translation) * fraction;
            if (horizontal) view.setTranslationX(position); else view.setTranslationY(position);
        });
    }
    public static void cancel(View view) {
        State state = (State) view.getTag(R.id.motion_state);
        if (state != null) state.finish(view);
    }
    public static void cancelTree(View view) {
        List<View> children = new ArrayList<>();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) children.add(group.getChildAt(i));
        }
        if (view instanceof Owner) ((Owner) view).stopMotion();
        cancel(view);
        for (View child : children) cancelTree(child);
    }
    private static State state(View view) {
        State state = (State) view.getTag(R.id.motion_state);
        if (state != null) return state;
        State created = new State();
        view.setTag(R.id.motion_state, created);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { }
            public void onViewDetachedFromWindow(View v) { created.finish(v); }
        });
        view.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (!(v instanceof Owner) && oldR > oldL && oldB > oldT
                    && (r - l != oldR - oldL || b - t != oldB - oldT))
                created.finish(v);
        });
        return created;
    }
    private static final class State {
        final List<MotionHandle> handles = new ArrayList<>();
        MotionHandle press, entry;
        boolean pressing, horizontal;
        float baseScaleX, baseScaleY, baseAlpha, baseTranslation;
        void scale(View view, float factor, long millis, boolean release) {
            if (!pressing) return;
            if (press != null) press.cancel();
            float x = view.getScaleX(), y = view.getScaleY();
            float endX = baseScaleX * factor, endY = baseScaleY * factor;
            press = animate(view, full(view.getContext()) ? millis : 0, f -> {
                view.setScaleX(x + (endX - x) * f); view.setScaleY(y + (endY - y) * f);
            }, () -> { if (release) pressing = false; });
        }
        void restorePress(View view) {
            if (press != null) press.cancel();
            if (pressing) { view.setScaleX(baseScaleX); view.setScaleY(baseScaleY); pressing = false; }
        }
        void finish(View view) {
            for (MotionHandle handle : new ArrayList<>(handles)) handle.finish();
            restorePress(view);
        }
    }
}
