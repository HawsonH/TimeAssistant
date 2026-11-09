package com.hawson.timeassistant.ui.schedule;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import com.hawson.timeassistant.design.MotionSpec;
import com.hawson.timeassistant.design.MotionHandle;
import com.hawson.timeassistant.design.ShapeTokens;
import com.hawson.timeassistant.design.UiKit;

/** Trailing swipe actions. No data access: the host MUST validate the captured state on callback. */
public final class ScheduleSwipeLayout extends FrameLayout implements MotionSpec.Owner {
    public enum Action { MAIN, EDIT }
    public interface Callback { void onAction(Action action, Object capturedState); }
    private final FrameLayout actions;
    private final ImageButton main;
    private final ImageButton edit;
    private final int mainAccessibilityId = View.generateViewId();
    private final int editAccessibilityId = View.generateViewId();
    private final int slop;
    private View content;
    private ScheduleSwipeGroup group;
    private ScheduleSwipeGesture gesture;
    private Callback callback;
    private Object boundState;
    private Object capturedState;
    private float downX, downY, initialReveal;
    private float displayDistance;
    private MotionHandle settling;
    private final Rect actionClip = new Rect();
    private boolean dragging, blocked, open, hasEdit;
    private CharSequence mainLabel, editLabel;

    public ScheduleSwipeLayout(Context context) { this(context, null); }
    public ScheduleSwipeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setMinimumHeight(UiKit.dp(context, 56));
        setClipChildren(true);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        UiKit.dp(context, ShapeTokens.TASK_RADIUS));
            }
        });
        actions = new FrameLayout(context);
        actions.setBackground(UiKit.background(context, UiKit.accent(context), 0, ShapeTokens.TASK_RADIUS));
        addView(actions, new LayoutParams(-1, -1));
        main = button(context);
        edit = button(context);
        actions.addView(edit, new LayoutParams(actionWidth(), -1));
        actions.addView(main, new LayoutParams(actionWidth(), -1));
        main.setOnClickListener(v -> invoke(Action.MAIN, boundState));
        edit.setOnClickListener(v -> invoke(Action.EDIT, boundState));
        actions.setVisibility(INVISIBLE);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private ImageButton button(Context context) {
        ImageButton button = new ImageButton(context);
        button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        android.util.TypedValue ripple = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        if (ripple.resourceId != 0) button.setForeground(context.getDrawable(ripple.resourceId));
        button.setPadding(UiKit.dp(context, 16), UiKit.dp(context, 16),
                UiKit.dp(context, 16), UiKit.dp(context, 16));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        return button;
    }

    public void setContent(View view) {
        close();
        if (content != null) removeView(content);
        content = view;
        addView(view, new LayoutParams(-1, -2));
    }

    public void setSwipeGroup(ScheduleSwipeGroup value) {
        close();
        group = value;
    }

    public void setActionColors(int mainBackground, int editBackground) {
        actions.setBackground(UiKit.background(getContext(), mainBackground, 0, ShapeTokens.TASK_RADIUS));
        edit.setBackgroundColor(editBackground);
    }

    /** Null edit icon omits edit. Labels are required, localized accessibility action names. */
    public void bind(Object state, Drawable mainIcon, CharSequence primaryLabel,
            Drawable editIcon, CharSequence secondaryLabel, Callback value) {
        if (mainIcon == null || primaryLabel == null || primaryLabel.length() == 0 || value == null
                || (editIcon != null && (secondaryLabel == null || secondaryLabel.length() == 0))) {
            throw new IllegalArgumentException("Icons, action labels and callback are required");
        }
        close();
        boundState = state;
        callback = value;
        mainLabel = primaryLabel;
        editLabel = secondaryLabel;
        hasEdit = editIcon != null;
        main.setImageDrawable(mainIcon);
        edit.setImageDrawable(editIcon);
        UiKit.tooltip(main, primaryLabel.toString());
        if (hasEdit) UiKit.tooltip(edit, secondaryLabel.toString());
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    public boolean isOpen() { return open; }
    public void revealActions() {
        if (content == null || callback == null || !isEnabled()) return;
        close();
        if (group != null) group.claim(this);
        open = true;
        settleTo(actionWidth() * (hasEdit ? 2 : 1));
    }

    /** Also invalidates an in-flight gesture; safe on recycling, page changes and host state changes. */
    public void close() {
        invalidateGesture();
        cancelSettle();
        render(0, false);
    }

    void closeAnimated() {
        invalidateGesture();
        settleTo(0);
    }

    private void invalidateGesture() {
        if (dragging && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        blocked = true;
        dragging = false;
        open = false;
        capturedState = null;
        if (gesture != null) gesture.cancel();
        if (group != null) group.release(this);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            boolean handled = super.dispatchTouchEvent(event);
            close();
            return handled;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            blocked = false;
            dragging = false;
            downX = event.getX();
            downY = event.getY();
            cancelSettle();
            float reveal = actionWidth() * (hasEdit ? 2 : 1);
            initialReveal = displayDistance <= reveal ? displayDistance
                    : reveal + (displayDistance - reveal) / .55f;
            capturedState = boundState;
        } else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            close();
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            return true;
        }
        if (blocked) return true;
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (callback == null || content == null || !isEnabled()) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { close(); return false; }
        if (event.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
        if (dragging) return true;
        float dx = Math.abs(event.getX() - downX);
        float dy = Math.abs(event.getY() - downY);
        if (dy > slop && dy >= dx) { close(); return false; }
        if (dx <= slop || dx <= dy) return false;
        if (initialReveal <= 0 && !open && revealDelta(event) <= 0) return false;
        dragging = true;
        if (group != null) group.claim(this);
        gesture = new ScheduleSwipeGesture(actionWidth(), hasEdit ? 2 : 1, getWidth(),
                UiKit.dp(getContext(), 96), UiKit.dp(getContext(), 16));
        gesture.begin(open);
        updateGesture(event);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (blocked || callback == null || content == null || !isEnabled()) {
            return super.onTouchEvent(event);
        }
        // With no child touch target, ViewGroup skips interception after DOWN.
        if (!dragging && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            onInterceptTouchEvent(event);
        }
        if (!dragging) {
            return event.getActionMasked() == MotionEvent.ACTION_DOWN || super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                updateGesture(event);
                return true;
            case MotionEvent.ACTION_UP:
                updateGesture(event);
                Object snapshot = capturedState;
                ScheduleSwipeGesture.Release release = gesture.release();
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (release == ScheduleSwipeGesture.Release.COMMIT) invoke(Action.MAIN, snapshot);
                else if (release == ScheduleSwipeGesture.Release.OPEN) {
                    open = true;
                    capturedState = null;
                    settleTo(gesture.getReveal());
                } else closeAnimated();
                return true;
            case MotionEvent.ACTION_CANCEL:
                close();
                return true;
            default: return true;
        }
    }

    private void updateGesture(MotionEvent event) {
        if (gesture.move(initialReveal + revealDelta(event))) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        render(Math.min(getWidth(), gesture.getDistance()), gesture.isArmed());
    }

    private float revealDelta(MotionEvent event) {
        return (downX - event.getX()) * (getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -1 : 1);
    }

    private void render(float distance, boolean armed) {
        displayDistance = Math.max(0, Math.min(getWidth(), distance));
        if (content != null) content.setTranslationX(displayDistance
                * (getLayoutDirection() == LAYOUT_DIRECTION_RTL ? 1 : -1));
        if (actions == null) return;
        actions.setVisibility(displayDistance > 0 ? VISIBLE : INVISIBLE);
        layoutActions();
    }

    private void layoutActions() {
        float distance = displayDistance;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float reveal = actionWidth() * (hasEdit ? 2 : 1);
        float threshold = reveal + Math.min(UiKit.dp(getContext(), 96), getWidth() * .25f);
        float progress = Math.max(0f, Math.min(1f, (distance - reveal) / Math.max(1f, threshold - reveal)));
        progress = progress * progress * (3 - 2 * progress);
        int primary = Math.round(Math.min(distance, !hasEdit ? distance
                : actionWidth() + Math.max(0, distance - actionWidth()) * progress));
        int secondary = Math.max(0, Math.round(distance) - primary);
        int edge = rtl ? Math.round(distance) : getWidth() - Math.round(distance);
        int corner = UiKit.dp(getContext(), ShapeTokens.TASK_RADIUS);
        int padding = UiKit.dp(getContext(), 16);
        int leftPadding = padding + (rtl ? 0 : corner), rightPadding = padding + (rtl ? corner : 0);
        if (edit.getPaddingLeft() != leftPadding || edit.getPaddingRight() != rightPadding)
            edit.setPadding(leftPadding, padding, rightPadding, padding);
        if (rtl) {
            main.layout(0, 0, primary, getHeight());
            edit.layout(primary, 0, primary + secondary + corner, getHeight());
            actionClip.set(0, 0, Math.min(getWidth(), edge + corner), getHeight());
        } else {
            main.layout(getWidth() - primary, 0, getWidth(), getHeight());
            edit.layout(edge - corner, 0, edge + secondary, getHeight());
            actionClip.set(Math.max(0, edge - corner), 0, getWidth(), getHeight());
        }
        actions.setClipBounds(actionClip);
        edit.setVisibility(hasEdit && secondary > 0 ? VISIBLE : INVISIBLE);
        edit.setAlpha(Math.min(1f, secondary / (float) actionWidth()) * (1f - progress));
        main.setEnabled(open && !dragging && secondary + primary > 0);
        edit.setEnabled(open && !dragging && hasEdit && secondary >= actionWidth());
    }

    private void cancelSettle() {
        if (settling != null) { settling.cancel(); settling = null; }
        if (actions != null) actions.setAlpha(1f);
    }

    private void settleTo(float target) {
        cancelSettle();
        float from = displayDistance;
        if (!MotionSpec.full(getContext())) {
            render(target, false);
            if (target > 0) settling = MotionSpec.animate(this, 100, f -> actions.setAlpha(.85f + .15f * f));
            return;
        }
        settling = MotionSpec.animate(this, MotionSpec.SETTLE, f -> render(from + (target - from) * f, false));
    }

    private void invoke(Action action, Object snapshot) {
        if (!isEnabled() || callback == null || (action == Action.EDIT && !hasEdit)) return;
        Callback target = callback;
        closeAnimated();
        target.onAction(action, snapshot);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (!isEnabled() || callback == null) return;
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(mainAccessibilityId, mainLabel));
        if (hasEdit) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(editAccessibilityId, editLabel));
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (isEnabled() && callback != null && (action == mainAccessibilityId
                || (action == editAccessibilityId && hasEdit))) {
            invoke(action == mainAccessibilityId ? Action.MAIN : Action.EDIT, boundState);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    private int actionWidth() { return UiKit.dp(getContext(), 56); }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        layoutActions();
    }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if ((width != oldWidth || height != oldHeight) && oldWidth > 0) close();
    }
    @Override public void setEnabled(boolean enabled) {
        if (!enabled) close();
        super.setEnabled(enabled);
    }
    @Override protected void onDetachedFromWindow() { close(); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) close();
    }
    @Override public void stopMotion() { close(); }
}
