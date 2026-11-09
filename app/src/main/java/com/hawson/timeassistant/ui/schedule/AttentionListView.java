package com.hawson.timeassistant.ui.schedule;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.hawson.timeassistant.design.MotionSpec;
import com.hawson.timeassistant.design.UiKit;
import com.hawson.timeassistant.domain.TaskItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable attention groups with row-level updates and visual-only removal transitions. */
public final class AttentionListView extends LinearLayout {
    public interface RowFactory { View create(TaskItem task, String group); }
    private final RowFactory factory;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<String, String> membership = new HashMap<>();
    private final Map<String, Integer> positions = new HashMap<>();
    private final View emptyState;

    public AttentionListView(Context context, RowFactory factory) {
        super(context); this.factory = factory; setOrientation(VERTICAL);
        emptyState = UiKit.emptyState(context, com.hawson.timeassistant.R.drawable.ic_task_check,
                "暂无注意事项", null, null);
        addView(emptyState, new LayoutParams(-1, -2));
    }

    public String groupOf(String id) { return membership.get(id); }
    public int positionOf(String id) { return positions.getOrDefault(id, 0); }

    public void bind(String key, String title, int color, List<TaskItem> tasks) {
        Group group = groups.get(key);
        if (group == null) {
            group = new Group(title, color);
            groups.put(key, group); addView(group, new LayoutParams(-1, -2));
        }
        group.header.setText(title + "  " + tasks.size());
        Group target = group;
        List<String> ids = new ArrayList<>();
        for (TaskItem task : tasks) ids.add(task.getId());
        for (String old : new ArrayList<>(group.rows.keySet())) {
            if (ids.contains(old)) continue;
            View row = group.rows.remove(old);
            membership.remove(old); positions.remove(old); group.versions.remove(old);
            int height = row.getHeight();
            MotionSpec.animate(row, MotionSpec.CONTENT, fraction -> {
                row.setAlpha(1f - fraction);
                if (MotionSpec.full(getContext())) {
                    row.getLayoutParams().height = Math.max(0, Math.round(height * (1f - fraction)));
                    row.requestLayout();
                }
            }, () -> target.body.removeView(row));
        }
        for (int index = 0; index < tasks.size(); index++) {
            TaskItem task = tasks.get(index);
            String id = task.getId();
            LinearLayout row = group.rows.get(id);
            if (row == null) {
                row = new LinearLayout(getContext()); row.setOrientation(VERTICAL);
                row.setPadding(0, 0, 0, dp(9));
                group.rows.put(id, row);
                group.body.addView(row, Math.min(index, group.body.getChildCount()), new LayoutParams(-1, -2));
            }
            if (!Long.valueOf(task.getUpdatedAt()).equals(group.versions.get(id))) {
                row.removeAllViews();
                row.addView(factory.create(task, key), new LayoutParams(-1, -2));
                group.versions.put(id, task.getUpdatedAt());
            }
            membership.put(id, key); positions.put(id, index);
        }
        boolean allEmpty = true;
        for (Group item : groups.values()) {
            boolean empty = item.rows.isEmpty();
            item.setVisibility(empty ? GONE : VISIBLE);
            if (!empty) allEmpty = false;
        }
        emptyState.setVisibility(allEmpty ? VISIBLE : GONE);
    }

    private int dp(int value) { return UiKit.dp(getContext(), value); }

    private final class Group extends LinearLayout {
        final TextView header;
        final LinearLayout body;
        final Map<String, LinearLayout> rows = new LinkedHashMap<>();
        final Map<String, Long> versions = new HashMap<>();
        Group(String title, int color) {
            super(AttentionListView.this.getContext()); setOrientation(VERTICAL);
            setPadding(0, groups.isEmpty() ? 0 : dp(18), 0, 0);
            header = new TextView(getContext()); header.setTextSize(14); header.setTypeface(null, Typeface.BOLD);
            header.setTextColor(color); header.setPadding(0, 0, 0, dp(10));
            addView(header, new LayoutParams(-1, -2));
            body = new LinearLayout(getContext()); body.setOrientation(VERTICAL);
            addView(body, new LayoutParams(-1, -2));
        }
    }
}
