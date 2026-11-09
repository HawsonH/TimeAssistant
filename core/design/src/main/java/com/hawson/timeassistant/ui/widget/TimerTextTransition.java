package com.hawson.timeassistant.ui.widget;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import com.hawson.timeassistant.design.MotionHandle;
import com.hawson.timeassistant.design.MotionSpec;

/** Owns only the dial's text layer; retargeting snapshots the current visible frame. */
final class TimerTextTransition {
    private final View owner;
    private final TimerDialText text;
    private final Paint composite = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap outgoing;
    private MotionHandle animator;
    private long millis = 1_500_000L;
    private String label = "准备专注";
    private float fraction = 1f, offset;

    TimerTextTransition(View owner, TimerDialText text) { this.owner = owner; this.text = text; }

    void update(long value, String caption, boolean animate, boolean modeChange) {
        if (millis == value && label.equals(caption)) return;
        Bitmap snapshot = null;
        boolean allowed = animate && owner.isShown() && owner.getWidth() > 0
                && owner.getHeight() > 0 && MotionSpec.duration(owner.getContext(), 180) > 0;
        if (allowed) {
            snapshot = Bitmap.createBitmap(owner.getWidth(), owner.getHeight(), Bitmap.Config.ARGB_8888);
            draw(new Canvas(snapshot));
        }
        long previous = millis;
        stop();
        millis = value;
        label = caption;
        if (!allowed) { owner.invalidate(); return; }
        outgoing = snapshot;
        fraction = 0f;
        offset = !modeChange && MotionSpec.full(owner.getContext())
                ? (value >= previous ? -1 : 1) * 8 * owner.getResources().getDisplayMetrics().density : 0;
        animator = MotionSpec.animate(owner, modeChange ? MotionSpec.INDICATOR : MotionSpec.CONTENT,
                f -> { fraction = f; owner.invalidate(); }, this::releaseSnapshot);
    }

    void draw(Canvas canvas) {
        int width = owner.getWidth(), height = owner.getHeight();
        float density = owner.getResources().getDisplayMetrics().density;
        int save = canvas.save();
        canvas.clipRect(12 * density, 20 * density, width - 12 * density, height - 20 * density);
        if (outgoing == null) {
            text.draw(canvas, width, height, millis, label, Math.max(0, width - 56 * density));
            canvas.restoreToCount(save);
            return;
        }
        if (outgoing != null && fraction < 1) {
            composite.setAlpha(Math.round(255 * (1 - fraction)));
            canvas.drawBitmap(outgoing, 0, offset * fraction, composite);
        }
        int layer = canvas.saveLayerAlpha(0, 0, width, height, Math.round(255 * fraction));
        canvas.translate(0, -offset * (1 - fraction));
        text.draw(canvas, width, height, millis, label, Math.max(0, width - 56 * density));
        canvas.restoreToCount(layer);
        canvas.restoreToCount(save);
    }

    void stop() {
        if (animator != null) animator.cancel();
        animator = null;
        fraction = 1;
        offset = 0;
        releaseSnapshot();
    }

    private void releaseSnapshot() {
        if (outgoing != null) outgoing.recycle();
        outgoing = null;
        owner.invalidate();
    }
}
