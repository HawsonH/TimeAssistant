package com.hawson.timeassistant.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.util.Locale;

/** Fixed-sp timer typography; narrow and large-font layouts wrap rather than shrink. */
final class TimerDialText {
    private final Paint time = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Context context;

    TimerDialText(Context context) {
        this.context = context;
        time.setTextAlign(Paint.Align.CENTER);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setFontFeatureSettings("tnum");
        caption.setTextAlign(Paint.Align.CENTER);
    }

    void setPalette(int text, int secondary) { time.setColor(text); caption.setColor(secondary); }

    void draw(Canvas canvas, int width, int height, long millis, String label, float availableWidth) {
        float density = context.getResources().getDisplayMetrics().density;
        float scaled = context.getResources().getDisplayMetrics().scaledDensity;
        time.setTextSize((context.getResources().getConfiguration().fontScale >= 1.8f ? 48 : 56) * scaled);
        caption.setTextSize(14 * scaled);
        long seconds = (millis + 999) / 1000;
        String formatted = String.format(Locale.CHINA, "%02d:%02d", seconds / 60, seconds % 60);
        boolean split = time.measureText(formatted) > availableWidth;
        // Font leading is not visible ink; reserving it can push a large caption into the ring.
        android.graphics.Rect numberBounds = new android.graphics.Rect(), labelBounds = new android.graphics.Rect();
        time.getTextBounds("0123456789", 0, 10, numberBounds);
        String captionSample = "分00秒" + label;
        caption.getTextBounds(captionSample, 0, captionSample.length(), labelBounds);
        float numberHeight = numberBounds.height();
        float labelHeight = labelBounds.height();
        float total = numberHeight + labelHeight + 12 * density;
        if (split) total += labelHeight;
        float top = (height - total) / 2f;
        canvas.drawText(split ? String.valueOf(seconds / 60) : formatted,
                width / 2f, top - numberBounds.top, time);
        top += numberHeight + (split ? 4 : 12) * density;
        if (split) {
            canvas.drawText(String.format(Locale.CHINA, "分 %02d 秒", seconds % 60),
                    width / 2f, top - labelBounds.top, caption);
            top += labelHeight + 8 * density;
        }
        canvas.drawText(label, width / 2f, top - labelBounds.top, caption);
    }
}
