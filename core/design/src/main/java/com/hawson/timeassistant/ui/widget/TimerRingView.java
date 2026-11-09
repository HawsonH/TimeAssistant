/**
 * 专注计时圆环
 *
 * 专注页中央的圆环进度与剩余时间展示控件。
 */
package com.hawson.timeassistant.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.hawson.timeassistant.design.MotionSpec;
import com.hawson.timeassistant.design.MotionHandle;
import com.hawson.timeassistant.design.RingAmbientMotion;

import java.util.Locale;

public class TimerRingView extends View implements MotionSpec.Owner {
    public enum PresentationState { READY, RUNNING, PAUSED, FINISHED }
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private TimerDialText dialText;
    private TimerTextTransition timeText;
    private final RectF oval = new RectF();
    private final Paint detailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF innerOval = new RectF();
    private final RingAmbientMotion ambient = new RingAmbientMotion(this);
    private PresentationState presentation = PresentationState.READY;
    private boolean expanded, motionActive = true;
    private int viewportDiameterLimit = Integer.MAX_VALUE;
    private float progress = 1f;
    private long remainingMillis = 25 * 60 * 1000L;
    private String label = "准备专注";
    private MotionHandle timerAnimator;
    private float targetProgress = 1f;

    public TimerRingView(Context context) { super(context); init(); }
    public TimerRingView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(4 * density);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(4 * density);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        dialText = new TimerDialText(getContext());
        timeText = new TimerTextTransition(this, dialText);
        detailPaint.setStyle(Paint.Style.STROKE);
        detailPaint.setStrokeWidth(density);
        detailPaint.setStrokeCap(Paint.Cap.ROUND);
        TypedArray colors = getContext().obtainStyledAttributes(new int[]{
                androidx.appcompat.R.attr.colorPrimary, android.R.attr.colorBackground,
                android.R.attr.textColorPrimary, android.R.attr.textColorSecondary});
        try {
            setPalette(colors.getColor(0, android.graphics.Color.DKGRAY),
                    colors.getColor(1, android.graphics.Color.LTGRAY),
                    colors.getColor(2, android.graphics.Color.BLACK),
                    colors.getColor(3, android.graphics.Color.GRAY));
        } finally {
            colors.recycle();
        }
    }

    public void setPalette(int primary, int track, int text, int secondaryText) {
        progressPaint.setColor(primary);
        trackPaint.setColor(track);
        dialText.setPalette(text, secondaryText);
        detailPaint.setColor(primary);
        invalidate();
    }

    public void setTimer(long remainingMillis, long totalMillis, String label) {
        if (sameTimer(remainingMillis, totalMillis, label)) return;
        if (timerAnimator != null) timerAnimator.cancel();
        applyTimer(remainingMillis, totalMillis, label);
        timeText.update(this.remainingMillis, this.label, false, false);
        refreshAmbient();
    }

    public void setPresentationState(PresentationState state) {
        presentation = state == null ? PresentationState.READY : state;
        boolean interactive = presentation == PresentationState.READY;
        setClickable(interactive);
        updateContentDescription(remainingMillis);
        refreshAmbient();
    }

    public void setExpanded(boolean next) {
        if (expanded == next) return;
        expanded = next;
        requestLayout();
    }

    /** Called only by the viewport during measurement, without scheduling another layout. */
    public void constrainDiameterForMeasure(int pixels) { viewportDiameterLimit = pixels; }

    public void setMotionActive(boolean active) {
        motionActive = active;
        if (!active) stopMotion();
        refreshAmbient();
    }

    public float getAmbientOpacity() { return ambient.opacity(); }
    public boolean isAmbientRunning() { return ambient.isRunning(); }
    public PresentationState getPresentationState() { return presentation; }

    private void refreshAmbient() {
        if (ambient == null) return;
        ambient.update(presentation == PresentationState.RUNNING,
                motionActive && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE);
    }

    public void animateTimerTo(long remainingMillis, long totalMillis, String label,
                               long durationMillis) {
        updateTimer(remainingMillis, totalMillis, label, false, durationMillis, false);
    }

    public void transitionTimerTo(long remainingMillis, long totalMillis, String label,
                                  boolean modeChange) {
        updateTimer(remainingMillis, totalMillis, label, modeChange, MotionSpec.CONTENT, true);
    }

    private void updateTimer(long remainingMillis, long totalMillis, String label,
                             boolean modeChange, long durationMillis, boolean animateText) {
        if (sameTimer(remainingMillis, totalMillis, label)) return;
        if (timerAnimator != null) timerAnimator.cancel();
        float previous = progress;
        applyTimer(remainingMillis, totalMillis, label);
        timeText.update(this.remainingMillis, this.label,
                animateText && motionActive && getWindowVisibility() == VISIBLE, modeChange);
        refreshAmbient();
        float target = progress;
        if (motionActive && getWindowVisibility() == VISIBLE && MotionSpec.full(getContext()) && isShown()) {
            progress = previous;
            timerAnimator = MotionSpec.animate(this, Math.min(MotionSpec.SETTLE, Math.max(0, durationMillis)), fraction -> {
                progress = previous + (target - previous) * fraction;
                invalidate();
            });
        }
    }

    private boolean sameTimer(long value, long total, String caption) {
        float target = total == 0 ? 0 : Math.max(0f, Math.min(1f, Math.max(0, value) / (float) total));
        return this.remainingMillis == Math.max(0, value) && targetProgress == target
                && this.label.equals(caption == null ? "" : caption);
    }

    private void applyTimer(long remainingMillis, long totalMillis, String label) {
        this.remainingMillis = Math.max(0, remainingMillis);
        this.progress = totalMillis == 0 ? 0
                : Math.max(0f, Math.min(1f, this.remainingMillis / (float) totalMillis));
        targetProgress = this.progress;
        this.label = label == null ? "" : label;
        updateContentDescription(this.remainingMillis);
        invalidate();
    }

    private void updateContentDescription(long value) {
        long totalSeconds = (value + 999) / 1000;
        String actions = presentation == PresentationState.READY
                ? "。点击详细设置，上下滑动加减分钟，左右滑动切换模式" : "";
        setContentDescription(String.format(Locale.CHINA, "%s，%d 分 %02d 秒%s",
                this.label, totalSeconds / 60, totalSeconds % 60, actions));
    }

    public void stopMotion() {
        if (timerAnimator != null) timerAnimator.cancel();
        timerAnimator = null;
        if (ambient != null) ambient.stop();
        if (timeText != null) timeText.stop();
        progress = targetProgress;
        invalidate();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) stopMotion();
        else refreshAmbient();
    }

    @Override public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (ambient == null) return;
        if (isVisible) refreshAmbient(); else stopMotion();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshAmbient();
    }

    @Override protected void onDetachedFromWindow() {
        stopMotion();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) {
            stopMotion();
            post(this::refreshAmbient);
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int preferred = Math.min(viewportDiameterLimit,
                Math.round(320 * getResources().getDisplayMetrics().density));
        int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
        int size = availableWidth > 0 ? Math.min(availableWidth, preferred) : preferred;
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            size = Math.min(size, MeasureSpec.getSize(heightMeasureSpec));
        }
        setMeasuredDimension(Math.max(getSuggestedMinimumWidth(), size),
                Math.max(getSuggestedMinimumHeight(), size));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float inset = 12 * density;
        oval.set(inset, inset, getWidth() - inset, getHeight() - inset);
        drawDetails(canvas, density);
        canvas.drawArc(oval, -90, 360, false, trackPaint);
        canvas.drawArc(oval, -90, 360 * progress, false, progressPaint);
        timeText.draw(canvas);
    }

    private void drawDetails(Canvas canvas, float density) {
        float centerX = getWidth() / 2f, centerY = getHeight() / 2f;
        float radius = oval.width() / 2f;
        detailPaint.setAlpha(48);
        for (int tick = 0; tick < 60; tick++) {
            double angle = Math.toRadians(tick * 6 - 90);
            float length = (tick % 5 == 0 ? 5 : 2) * density;
            float outer = radius - 9 * density;
            canvas.drawLine(centerX + (float) Math.cos(angle) * outer,
                    centerY + (float) Math.sin(angle) * outer,
                    centerX + (float) Math.cos(angle) * (outer - length),
                    centerY + (float) Math.sin(angle) * (outer - length), detailPaint);
        }
        float inner = radius - 24 * density;
        innerOval.set(centerX - inner, centerY - inner, centerX + inner, centerY + inner);
        detailPaint.setAlpha(Math.round(ambient.opacity() * 255));
        canvas.drawOval(innerOval, detailPaint);
    }
}
