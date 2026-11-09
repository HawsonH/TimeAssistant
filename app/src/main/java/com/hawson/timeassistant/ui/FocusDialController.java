package com.hawson.timeassistant.ui;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.ui.widget.TimerRingView;

/** Routes dial input to injected events; never owns a repository or a timer session. */
final class FocusDialController {
    interface Host {
        boolean editable();
        FocusState.Mode mode();
        int minutes();
        void preview(int minutes);
        void restore();
        void save(FocusState.Mode mode, int minutes);
        void switchMode(int direction);
        void details();
    }
    private final TimerRingView ring;
    private final Host host;
    private final FocusDialGesture gesture;
    private FocusState.Mode startingMode;
    private int startingMinutes;
    private int increaseAction, decreaseAction;
    private boolean actionsEnabled;

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    FocusDialController(TimerRingView ring, Host host) {
        this.ring = ring; this.host = host;
        float density = ring.getResources().getDisplayMetrics().density;
        gesture = new FocusDialGesture(ViewConfiguration.get(ring.getContext()).getScaledTouchSlop(),
                24 * density, 52 * density);
        ring.setOnClickListener(v -> { if (host.editable()) host.details(); });
        ring.setOnTouchListener((v, event) -> touch(event));
        ring.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (oldR > oldL && oldB > oldT && (r - l != oldR - oldL || b - t != oldB - oldT)) cancel();
        });
        ring.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { }
            public void onViewDetachedFromWindow(View v) { cancel(); }
        });
        ViewCompat.setAccessibilityDelegate(ring, new AccessibilityDelegateCompat() {
            @Override public void onInitializeAccessibilityNodeInfo(View v, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(v, info);
                if (!host.editable() || ring.getPresentationState() != TimerRingView.PresentationState.READY) return;
                info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD, "下一个模式"));
                info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD, "上一个模式"));
            }
            @Override public boolean performAccessibilityAction(View v, int action, Bundle args) {
                if (host.editable() && action == AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD) {
                    cancel(); host.switchMode(1); return true;
                }
                if (host.editable() && action == AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD) {
                    cancel(); host.switchMode(-1); return true;
                }
                return super.performAccessibilityAction(v, action, args);
            }
        });
        updateAccessibility();
    }

    void updateAccessibility() {
        boolean enabled = host.editable() && ring.getPresentationState() == TimerRingView.PresentationState.READY;
        if (actionsEnabled == enabled) return;
        actionsEnabled = enabled;
        if (!enabled) {
            ViewCompat.removeAccessibilityAction(ring, increaseAction);
            ViewCompat.removeAccessibilityAction(ring, decreaseAction);
            ViewCompat.removeAccessibilityAction(ring, AccessibilityNodeInfoCompat.ACTION_CLICK);
            return;
        }
        increaseAction = ViewCompat.addAccessibilityAction(ring, "增加一分钟", (v, args) -> adjust(1));
        decreaseAction = ViewCompat.addAccessibilityAction(ring, "减少一分钟", (v, args) -> adjust(-1));
        ViewCompat.replaceAccessibilityAction(ring,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, "详细设置",
                (v, args) -> { if (!host.editable()) return false; ring.performClick(); return true; });
    }

    private boolean adjust(int delta) {
        if (!host.editable()) return false;
        cancel();
        int next = Math.max(1, Math.min(maximum(host.mode()), host.minutes() + delta));
        if (next != host.minutes()) host.save(host.mode(), next);
        return true;
    }

    private boolean touch(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL || e.getPointerCount() > 1) { cancel(); return true; }
        if (!host.editable()) { cancel(); return false; }
        if (action == MotionEvent.ACTION_DOWN) {
            cancel();
            startingMode = host.mode(); startingMinutes = host.minutes();
            gesture.begin(e.getX(), e.getY(), startingMinutes, maximum(startingMode));
            parentCapture(true);
            return true;
        }
        if (!gesture.isActive()) return true;
        if (host.mode() != startingMode || host.minutes() != startingMinutes) { cancel(); return true; }
        if (action == MotionEvent.ACTION_MOVE) {
            if (gesture.move(e.getX(), e.getY())) host.preview(gesture.value());
        } else if (action == MotionEvent.ACTION_UP) {
            FocusDialGesture.Result result = gesture.end(e.getX(), e.getY());
            parentCapture(false);
            if (result.minutes != 0) host.save(startingMode, result.minutes);
            else if (result.modeDirection != 0) host.switchMode(result.modeDirection);
            else if (result.tap) ring.performClick();
            else host.restore();
        }
        return true;
    }

    boolean isPreviewing() {
        if (gesture.isActive() && (!host.editable() || host.mode() != startingMode
                || host.minutes() != startingMinutes)) cancel();
        return gesture.isPreviewing();
    }
    void cancel() {
        boolean preview = gesture.isPreviewing();
        gesture.cancel(); parentCapture(false);
        if (preview) host.restore();
    }
    private void parentCapture(boolean capture) {
        if (ring.getParent() != null) ring.getParent().requestDisallowInterceptTouchEvent(capture);
    }
    private int maximum(FocusState.Mode mode) { return mode == FocusState.Mode.FOCUS ? 180 : 60; }
}
