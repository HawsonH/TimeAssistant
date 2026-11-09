package com.hawson.timeassistant.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.LinearLayout;
import java.util.function.IntConsumer;
import com.hawson.timeassistant.design.UiKit;

/** Grouped settings home. Sections are addressed by their index in {@link #TITLES}. */
@SuppressLint("ViewConstructor") // Requires an injected selection callback; not an XML widget.
public final class SettingsNavigationView extends LinearLayout {
    private static final String[] TITLES = {"外观与教程", "AI 连接与账号", "主动整理", "安排偏好与通知", "日历连接", "同步与数据", "系统权限与提醒"};
    /** A titled group of section indexes on the settings home. */
    public static final class Group {
        final String title;
        final int[] sections;
        public Group(String title, int... sections) { this.title = title; this.sections = sections; }
    }
    private final IntConsumer select;
    private int[] icons = new int[0];
    private String[] values = new String[0];
    private int[] valueColors = new int[0];
    private Group[] groups = new Group[0];
    public SettingsNavigationView(Context context, IntConsumer select) {
        super(context); this.select = select; setOrientation(VERTICAL);
    }
    /**
     * @param icons per-section icon resources
     * @param values per-section subtitles (status text for stateful sections)
     * @param valueColors per-section subtitle colors; 0 keeps the secondary color
     * @param groups display grouping; sections not listed are not shown
     */
    public void configure(int[] icons, String[] values, int[] valueColors, Group[] groups) {
        this.icons = icons; this.values = values; this.valueColors = valueColors; this.groups = groups;
    }
    public static String sectionTitle(int section) {
        return section >= 0 && section < TITLES.length ? TITLES[section] : "设置";
    }
    public void showSection(int section) {
        removeAllViews();
        if (section >= 0) return;
        Context context = getContext();
        for (int index = 0; index < groups.length; index++) {
            Group group = groups[index];
            android.widget.TextView header = UiKit.sectionHeader(context, group.title);
            if (index == 0) header.setPadding(header.getPaddingLeft(), UiKit.dp(context, 8),
                    header.getPaddingRight(), header.getPaddingBottom());
            addView(header);
            LinearLayout list = UiKit.groupList(context);
            for (int target : group.sections) {
                if (target < 0 || target >= TITLES.length) continue;
                UiKit.addRows(list, 52, UiKit.row(context, TITLES[target],
                        target < values.length ? values[target] : "",
                        target < valueColors.length ? valueColors[target] : 0,
                        target < icons.length ? icons[target] : 0,
                        () -> select.accept(target)));
            }
            addView(list);
        }
    }
}
