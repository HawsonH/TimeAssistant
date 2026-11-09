package com.hawson.timeassistant.design;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/** One retargetable background for native tab labels or icon-and-label navigation items. */
public class SlidingIndicatorLayout extends LinearLayout implements MotionSpec.Owner {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private MotionHandle motion;
    private int indicatorIndex = -1;
    private float radius = ShapeTokens.INDICATOR_RADIUS;

    public SlidingIndicatorLayout(Context context) { this(context, null); }
    public SlidingIndicatorLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        paint.setColor(UiKit.surface(context));
    }
    public void setIndicatorColor(int color) { paint.setColor(color); invalidate(); }
    public void setIndicatorRadius(float dp) { radius = dp; invalidate(); }
    public RectF indicatorBounds() { return new RectF(bounds); }
    public void seekIndicatorTo(long millis) { if (motion != null) motion.seekTo(millis); }
    public void selectIndicator(int index) {
        if (index < 0 || index >= getChildCount()) throw new IllegalArgumentException("Invalid indicator index");
        if (indicatorIndex == index) return;
        indicatorIndex = index;
        if (motion != null) motion.cancel();
        RectF target = target();
        if (!isLaidOut() || bounds.isEmpty() || MotionSpec.duration(getContext(), MotionSpec.INDICATOR) == 0) {
            bounds.set(target); invalidate(); return;
        }
        RectF from = new RectF(bounds);
        boolean slide = MotionSpec.full(getContext());
        if (!slide) bounds.set(target);
        motion = MotionSpec.animate(this, MotionSpec.INDICATOR, f -> {
            if (slide) bounds.set(from.left + (target.left - from.left) * f,
                    from.top + (target.top - from.top) * f,
                    from.right + (target.right - from.right) * f,
                    from.bottom + (target.bottom - from.bottom) * f);
            paint.setAlpha(slide ? 255 : Math.round(255 * f));
            invalidate();
        });
    }
    private RectF target() {
        if (indicatorIndex < 0 || indicatorIndex >= getChildCount()) return new RectF();
        View child = getChildAt(indicatorIndex);
        return new RectF(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed || motion == null || !motion.isRunning()) stopMotion();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float corner = Math.min(UiKit.dp(getContext(), radius), bounds.height() / 2f);
        canvas.drawRoundRect(bounds, corner, corner, paint);
    }
    @Override public void stopMotion() {
        if (motion != null) { motion.cancel(); motion = null; }
        bounds.set(target()); paint.setAlpha(255); invalidate();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE && bounds != null) stopMotion();
    }
    @Override protected void onDetachedFromWindow() { stopMotion(); super.onDetachedFromWindow(); }
}
