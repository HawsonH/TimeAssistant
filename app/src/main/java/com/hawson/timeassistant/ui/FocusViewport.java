package com.hawson.timeassistant.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.ui.widget.TimerRingView;

/** Uses available height for the dial before falling back to scrolling on short screens. */
public final class FocusViewport extends ScrollView {
    public FocusViewport(Context context, AttributeSet attributes) { super(context, attributes); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        TimerRingView ring = findViewById(R.id.timerRing);
        if (ring == null) { super.onMeasure(widthSpec, heightSpec); return; }
        float density = getResources().getDisplayMetrics().density;
        int width = MeasureSpec.getSize(widthSpec);
        ring.constrainDiameterForMeasure(Math.min(Math.round(320 * density), Math.round(width * .72f)));
        super.onMeasure(widthSpec, heightSpec);
        if (getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                || MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED || getChildCount() == 0) return;
        int overflow = getChildAt(0).getMeasuredHeight() - getMeasuredHeight()
                + getPaddingTop() + getPaddingBottom();
        int diameter = Math.max(Math.round(240 * density), ring.getMeasuredHeight() - Math.max(0, overflow));
        if (diameter < ring.getMeasuredHeight()) {
            ring.constrainDiameterForMeasure(diameter);
            super.onMeasure(widthSpec, heightSpec);
        }
    }
}
