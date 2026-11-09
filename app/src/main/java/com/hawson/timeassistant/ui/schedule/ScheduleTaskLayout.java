package com.hawson.timeassistant.ui.schedule;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.hawson.timeassistant.design.UiKit;

/** Shared geometry for scheduled tasks and virtual recurring occurrences. */
public final class ScheduleTaskLayout {
    private ScheduleTaskLayout() { }

    public static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int padding = UiKit.dp(context, 16);
        card.setPadding(padding, padding, padding, padding);
        card.setBackground(UiKit.background(context, UiKit.surface(context), UiKit.border(context)));
        return card;
    }

    public static LinearLayout timeline(Context context, String start, String end) {
        LinearLayout row = new LinearLayout(context);
        row.setBaselineAligned(false); row.setGravity(Gravity.TOP);
        LinearLayout times = new LinearLayout(context);
        times.setOrientation(LinearLayout.VERTICAL);
        times.setPadding(0, UiKit.dp(context, 16), UiKit.dp(context, 4), 0);
        int timeWidth = UiKit.dp(context, 56);
        for (String value : new String[]{start, end}) {
            TextView text = new TextView(context);
            text.setText(value); text.setTextSize(13); text.setTextColor(UiKit.secondary(context));
            text.setSingleLine(true);
            timeWidth = Math.max(timeWidth, (int) Math.ceil(text.getPaint().measureText(value))
                    + times.getPaddingEnd());
            text.setIncludeFontPadding(false);
            text.setLineSpacing(UiKit.dp(context, 4), 1);
            times.addView(text, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(times, new LinearLayout.LayoutParams(timeWidth, -2));
        return row;
    }

    public static void content(LinearLayout timeline, View card) {
        timeline.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
    }
}
