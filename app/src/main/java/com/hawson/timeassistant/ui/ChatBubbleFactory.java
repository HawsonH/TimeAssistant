package com.hawson.timeassistant.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.hawson.timeassistant.design.UiKit;
import com.hawson.timeassistant.domain.ChatMessage;
import java.util.Locale;

final class ChatBubbleFactory {
    private ChatBubbleFactory() { }

    static ChatBubbleLayout assistant(Context context, String message, int background, int color) {
        ChatBubbleLayout bubble = new ChatBubbleLayout(context, background, false);
        TextView text = text(context, message, 17, color);
        ChatBubbleLayout.textMetrics(text); text.setTextIsSelectable(true); text.setTag("assistant-bubble");
        bubble.addView(text, new LinearLayout.LayoutParams(-2, -2));
        return bubble;
    }

    static LinearLayout user(Context context, ChatMessage message, int background, int color, int secondary) {
        LinearLayout line = new LinearLayout(context); line.setGravity(Gravity.END);
        ChatBubbleLayout bubble = new ChatBubbleLayout(context, background, !message.getAttachments().isEmpty());
        bubble.setTag("user-bubble");
        if (!message.getContent().trim().isEmpty()) {
            TextView text = text(context, message.getContent(), 17, color);
            ChatBubbleLayout.textMetrics(text); text.setTextIsSelectable(true);
            bubble.addView(text, new LinearLayout.LayoutParams(-2, -2));
        }
        for (ChatMessage.Attachment attachment : message.getAttachments()) {
            LinearLayout item = new LinearLayout(context); item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
            item.setBackground(UiKit.background(context, Color.argb(24, 127, 127, 127), 0));
            ImageView icon = new ImageView(context);
            icon.setImageResource(attachment.isImage() ? android.R.drawable.ic_menu_gallery : android.R.drawable.ic_menu_save);
            icon.setColorFilter(secondary); icon.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
            iconParams.setMarginEnd(dp(context, 10)); item.addView(icon, iconParams);
            LinearLayout details = new LinearLayout(context); details.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(context, attachment.getName(), 14, color);
            name.setTypeface(null, Typeface.BOLD); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
            details.addView(name, new LinearLayout.LayoutParams(-1, -2));
            details.addView(text(context, size(attachment.getSize()), 12, secondary), new LinearLayout.LayoutParams(-1, -2));
            item.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(context, 8);
            bubble.addView(item, params);
        }
        line.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        return line;
    }

    private static TextView text(Context context, String value, int size, int color) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(size);
        text.setTextColor(color); text.setLetterSpacing(0); return text;
    }
    private static String size(long bytes) {
        if (bytes <= 0) return "附件";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024d * 1024d));
    }
    private static int dp(Context context, int value) { return UiKit.dp(context, value); }
}
