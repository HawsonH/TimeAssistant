package com.hawson.timeassistant.settings;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.hawson.timeassistant.design.UiKit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Passive permission presentation; platform inspection and launches belong to the host. */
public final class PermissionGuideView extends LinearLayout {
    public static final int TONE_NEUTRAL = 0, TONE_OK = 1, TONE_WARNING = 2;
    public static final class Item {
        public final String id, group, title, status, detail;
        public final int tone;
        public Item(String id, String group, String title, String status, String detail) {
            this(id, group, title, status, detail, TONE_NEUTRAL);
        }
        public Item(String id, String group, String title, String status, String detail, int tone) {
            this.id = id; this.group = group; this.title = title; this.status = status; this.detail = detail;
            this.tone = tone;
        }
    }
    private final Map<String, TextView> statuses = new LinkedHashMap<>();
    private final Map<String, LinearLayout> rows = new LinkedHashMap<>();
    private final Consumer<String> open;

    public PermissionGuideView(Context context, Consumer<String> open) {
        super(context); setOrientation(VERTICAL); this.open = open;
    }

    public void render(List<Item> items) {
        if (statuses.isEmpty()) build(items);
        for (Item item : items) {
            TextView status = statuses.get(item.id);
            if (status == null) continue;
            UiKit.setStatus(status, item.status, toneColor(item.tone));
            rows.get(item.id).setContentDescription(item.title + "，" + item.status + "。" + item.detail);
        }
    }

    private int toneColor(int tone) {
        return tone == TONE_OK ? UiKit.accent(getContext())
                : tone == TONE_WARNING ? UiKit.warning(getContext()) : UiKit.secondary(getContext());
    }

    private void build(List<Item> items) {
        String group = "";
        LinearLayout list = null;
        for (Item item : items) {
            if (!group.equals(item.group)) {
                TextView heading = UiKit.sectionHeader(getContext(), item.group);
                if (getChildCount() == 0) heading.setPadding(heading.getPaddingLeft(), dp(8),
                        heading.getPaddingRight(), heading.getPaddingBottom());
                addView(heading); group = item.group;
                list = UiKit.groupList(getContext());
                addView(list);
            }
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(12), dp(14)); row.setMinimumHeight(dp(56));
            LinearLayout labels = new LinearLayout(getContext()); labels.setOrientation(VERTICAL);
            labels.addView(text(item.title, 16, false));
            TextView status = UiKit.statusLine(getContext(), "", toneColor(item.tone));
            LayoutParams statusParams = new LayoutParams(-1, -2); statusParams.topMargin = dp(4);
            labels.addView(status, statusParams);
            TextView detail = text(item.detail, 12, true);
            LayoutParams detailParams = new LayoutParams(-1, -2); detailParams.topMargin = dp(4);
            labels.addView(detail, detailParams);
            row.addView(labels, new LayoutParams(0, -2, 1));
            android.widget.ImageView arrow = new android.widget.ImageView(getContext());
            arrow.setImageResource(com.hawson.timeassistant.design.R.drawable.ui_ic_chevron_right);
            arrow.setImageTintList(android.content.res.ColorStateList.valueOf(UiKit.secondary(getContext())));
            arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams arrowParams = new LayoutParams(dp(24), dp(24)); arrowParams.setMarginStart(dp(8));
            row.addView(arrow, arrowParams);
            android.util.TypedValue ripple = new android.util.TypedValue();
            getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            row.setBackgroundResource(ripple.resourceId);
            row.setFocusable(true); row.setOnClickListener(v -> open.accept(item.id));
            ViewCompat.setScreenReaderFocusable(row, true);
            labels.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            UiKit.addRows(list, 16, row); statuses.put(item.id, status); rows.put(item.id, row);
        }
    }

    private TextView text(String value, int size, boolean secondary) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size);
        view.setTextColor(secondary ? UiKit.secondary(getContext()) : UiKit.text(getContext()));
        return view;
    }
    private int dp(int value) { return UiKit.dp(getContext(), value); }
}
