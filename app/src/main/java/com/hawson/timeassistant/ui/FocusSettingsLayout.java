package com.hawson.timeassistant.ui;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import com.hawson.timeassistant.design.UiKit;

/** Presentation-only grouped settings and a keyboard-resizing footer; draft ownership stays with the page. */
final class FocusSettingsLayout {
    private final Context context;
    final ScrollView scroll;
    final LinearLayout body;

    FocusSettingsLayout(Context context) {
        this.context = context;
        scroll = new ScrollView(context);
        scroll.setId(com.hawson.timeassistant.R.id.settingsScroll);
        scroll.setClipToPadding(false);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
    }

    LinearLayout section(String title) {
        body.addView(UiKit.sectionHeader(context, title));
        LinearLayout group = UiKit.group(context);
        body.addView(group);
        return group;
    }

    void attach(LinearLayout root, Button save) {
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setBackgroundColor(UiKit.surface(context));
        footer.addView(UiKit.divider(context, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(16), dp(12), dp(16), dp(16));
        footer.addView(save, params);
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
    }

    void reveal(View section) { scroll.post(() -> scroll.scrollTo(0, Math.max(0, section.getTop() - dp(32)))); }
    private int dp(int value) { return UiKit.dp(context, value); }
}
