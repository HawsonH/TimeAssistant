package com.hawson.timeassistant.ui;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.hawson.timeassistant.design.UiKit;

/** Both roles share metrics; attachments expand within the actual chat container. */
final class ChatBubbleLayout extends LinearLayout implements UiKit.MetricsOwner {
    private final boolean attachments;

    ChatBubbleLayout(Context context, int color, boolean attachments) {
        super(context); this.attachments = attachments; setOrientation(VERTICAL);
        setPadding(dp(16), dp(12), dp(16), dp(12));
        setBackground(UiKit.background(context, color, 0));
    }

    static void textMetrics(TextView text) {
        text.setTextSize(16); text.setLetterSpacing(0);
        text.setLineSpacing(UiKit.dp(text.getContext(), 4), 1f);
        text.setMinHeight(0); text.setMinimumHeight(0); text.setPadding(0, 0, 0, 0);
    }

    @Override protected void onMeasure(int width, int height) {
        int available = MeasureSpec.getSize(width);
        int limit = Math.max(1, available - dp(24));
        int mode = attachments ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
        int target = attachments ? Math.min(limit, dp(320)) : limit;
        super.onMeasure(MeasureSpec.makeMeasureSpec(target, mode), height);
    }

    private int dp(int value) { return UiKit.dp(getContext(), value); }
}
