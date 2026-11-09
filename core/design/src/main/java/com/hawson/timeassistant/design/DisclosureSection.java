package com.hawson.timeassistant.design;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Optional fields remain in the form hierarchy so draft protection still sees them. */
public final class DisclosureSection extends LinearLayout implements MotionSpec.Owner {
    public final LinearLayout content;
    private final TextView toggle;
    private MotionHandle motion;
    private boolean expanded;
    public DisclosureSection(Context context, String title, boolean expanded) {
        super(context); setOrientation(VERTICAL);
        toggle = new TextView(context); toggle.setText(title); toggle.setTextSize(14);
        toggle.setTextColor(UiKit.secondary(context)); toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setMinHeight(UiKit.dp(context, 48));
        toggle.setFocusable(true);
        android.util.TypedValue feedback = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, feedback, true);
        toggle.setBackgroundResource(feedback.resourceId);
        toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0);
        addView(toggle, new LayoutParams(-1, -2));
        content = new LinearLayout(context); content.setOrientation(VERTICAL);
        addView(content, new LayoutParams(-1, -2));
        setExpanded(expanded);
        toggle.setOnClickListener(v -> {
            animateExpanded(!this.expanded);
        });
    }
    public void setExpanded(boolean expanded) {
        if (motion != null) motion.cancel();
        this.expanded = expanded;
        applyEndpoint();
        updateToggle();
    }
    private void updateToggle() {
        toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                expanded ? android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float, 0);
        androidx.core.view.ViewCompat.setStateDescription(toggle, expanded ? "已展开" : "已收起");
        content.setImportantForAccessibility(expanded ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
    private void animateExpanded(boolean show) {
        if (motion != null) motion.cancel();
        int from = content.getVisibility() == VISIBLE ? content.getHeight() : 0;
        float alpha = content.getVisibility() == VISIBLE ? content.getAlpha() : 0;
        expanded = show; updateToggle();
        if (MotionSpec.duration(getContext(), MotionSpec.CONTENT) == 0 || getWidth() == 0) { applyEndpoint(); return; }
        int width = Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
        content.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int target = show ? content.getMeasuredHeight() : 0;
        content.setVisibility(VISIBLE);
        boolean full = MotionSpec.full(getContext());
        motion = MotionSpec.animate(this, MotionSpec.CONTENT, fraction -> {
            LayoutParams params = (LayoutParams) content.getLayoutParams();
            params.height = full ? Math.round(from + (target - from) * fraction) : target;
            content.setLayoutParams(params);
            content.setAlpha(alpha + ((show ? 1f : 0f) - alpha) * fraction);
        }, () -> {
            applyEndpoint();
            View focused = content.findFocus();
            if (expanded && focused != null) focused.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, focused.getWidth(), focused.getHeight()), true);
        });
    }
    private void applyEndpoint() {
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        content.setAlpha(1f);
        LayoutParams params = (LayoutParams) content.getLayoutParams();
        params.height = LayoutParams.WRAP_CONTENT; content.setLayoutParams(params);
    }
    @Override public void stopMotion() { if (motion != null) motion.cancel(); applyEndpoint(); }
    @Override protected void onDetachedFromWindow() { stopMotion(); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE && content != null) stopMotion();
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldw > 0 && w != oldw) stopMotion();
    }
}
