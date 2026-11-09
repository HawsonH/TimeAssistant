package com.hawson.timeassistant.ui.task;

import android.annotation.SuppressLint;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.PopupMenu;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.design.UiKit;
import java.text.DateFormat;
import java.util.Calendar;

/** Shared date/time presentation; callers retain ownership of the editable schedule draft. */
@SuppressLint("ViewConstructor")
public final class TaskDateTimeFields extends LinearLayout {
    private final long[][] values;
    private final TextView[] fields;
    private final ImageButton[] clears;
    private final TextView error;

    public TaskDateTimeFields(Context context, long[] date, long[] start, long[] end,
                              boolean includeTimes) {
        super(context);
        setOrientation(VERTICAL);
        values = new long[][]{date, start, end};
        fields = new TextView[includeTimes ? 3 : 1];
        clears = new ImageButton[fields.length];
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(context);
        label.setText(includeTimes ? "日期与时间" : "日期");
        label.setTextColor(UiKit.secondary(context));
        label.setTextSize(12);
        heading.addView(label, new LayoutParams(0, -2, 1));
        ImageButton more = icon(R.drawable.ic_more, "日期时间选项");
        more.setOnClickListener(view -> {
            PopupMenu menu = new PopupMenu(context, more);
            menu.getMenu().add("重置全部").setOnMenuItemClickListener(item -> {
                for (long[] value : values) value[0] = 0;
                refresh();
                return true;
            });
            menu.show();
        });
        heading.addView(more, new LayoutParams(dp(48), dp(48)));
        addView(heading, new LayoutParams(-1, -2));
        String[] names = {"日期", "开始", "结束"};
        for (int index = 0; index < fields.length; index++) {
            final int field = index;
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(UiKit.background(context, UiKit.surface(context), UiKit.border(context)));
            TextView value = new TextView(context);
            value.setTextSize(14);
            value.setMinHeight(dp(48));
            value.setGravity(Gravity.CENTER_VERTICAL);
            value.setPadding(dp(12), dp(8), dp(4), dp(8));
            value.setFocusable(true);
            value.setOnClickListener(view -> pick(field));
            android.util.TypedValue feedback = new android.util.TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, feedback, true);
            value.setBackgroundResource(feedback.resourceId);
            fields[index] = value;
            row.addView(value, new LayoutParams(0, -2, 1));
            ImageButton clear = icon(android.R.drawable.ic_menu_close_clear_cancel, "清除" + names[index]);
            clear.setOnClickListener(view -> {
                values[field][0] = 0;
                refresh();
            });
            clears[index] = clear;
            row.addView(clear, new LayoutParams(dp(48), dp(48)));
            LayoutParams params = new LayoutParams(-1, -2);
            params.topMargin = index == 0 ? 0 : dp(4);
            addView(row, params);
        }
        error = new TextView(context);
        error.setTextSize(12);
        android.util.TypedValue errorColor = new android.util.TypedValue();
        context.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorError, errorColor, true);
        error.setTextColor(errorColor.resourceId == 0 ? errorColor.data : context.getColor(errorColor.resourceId));
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        error.setVisibility(GONE);
        addView(error, new LayoutParams(-1, -2));
        refresh();
    }

    private ImageButton icon(int resource, String description) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(resource);
        button.setColorFilter(UiKit.secondary(getContext()));
        android.util.TypedValue feedback = new android.util.TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, feedback, true);
        button.setBackgroundResource(feedback.resourceId);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        UiKit.tooltip(button, description);
        return button;
    }

    private void pick(int index) {
        Calendar initial = Calendar.getInstance();
        if (values[index][0] > 0) initial.setTimeInMillis(values[index][0]);
        else if (values[0][0] > 0) initial.setTimeInMillis(values[0][0]);
        if (index == 0) {
            new DatePickerDialog(getContext(), (view, year, month, day) -> {
                Calendar chosen = Calendar.getInstance();
                chosen.clear();
                chosen.set(year, month, day);
                // Move both endpoints by calendar days, retaining existing explicit overnight spans.
                Calendar old = Calendar.getInstance();
                old.setTimeInMillis(values[0][0] > 0 ? values[0][0]
                        : values[1][0] > 0 ? values[1][0]
                        : values[2][0] > 0 ? values[2][0] : chosen.getTimeInMillis());
                for (int field = 1; field < values.length; field++) {
                    if (values[field][0] <= 0) continue;
                    values[field][0] = DateValues.moveDate(values[field][0], old, chosen);
                }
                values[0][0] = chosen.getTimeInMillis();
                refresh();
            }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH)).show();
            return;
        }
        new TimePickerDialog(getContext(), (view, hour, minute) -> {
            Calendar chosen = Calendar.getInstance();
            if (values[0][0] > 0) chosen.setTimeInMillis(values[0][0]);
            chosen.set(Calendar.HOUR_OF_DAY, 0);
            chosen.set(Calendar.MINUTE, 0);
            chosen.set(Calendar.SECOND, 0);
            chosen.set(Calendar.MILLISECOND, 0);
            values[0][0] = chosen.getTimeInMillis();
            chosen.set(Calendar.HOUR_OF_DAY, hour);
            chosen.set(Calendar.MINUTE, minute);
            values[index][0] = chosen.getTimeInMillis();
            // Never reinterpret an earlier end time as tomorrow.
            refresh();
        }, initial.get(Calendar.HOUR_OF_DAY), initial.get(Calendar.MINUTE), true).show();
    }

    private void refresh() {
        String[] names = {"日期", "开始", "结束"};
        for (int index = 0; index < fields.length; index++) {
            long value = values[index][0];
            String formatted = value <= 0 ? "" : index == 0
                    ? DateFormat.getDateInstance(DateFormat.MEDIUM).format(value)
                    : android.text.format.DateFormat.getTimeFormat(getContext()).format(value);
            if (index > 0 && value > 0 && values[0][0] > 0) {
                Calendar date = Calendar.getInstance();
                date.setTimeInMillis(values[0][0]);
                Calendar time = Calendar.getInstance();
                time.setTimeInMillis(value);
                if (date.get(Calendar.YEAR) != time.get(Calendar.YEAR)
                        || date.get(Calendar.DAY_OF_YEAR) != time.get(Calendar.DAY_OF_YEAR)) {
                    formatted = DateFormat.getDateInstance(DateFormat.SHORT).format(value) + " " + formatted;
                }
            }
            fields[index].setText(names[index] + (value > 0 ? " · " + formatted : ""));
            fields[index].setTextColor(value > 0 ? UiKit.text(getContext()) : UiKit.secondary(getContext()));
            clears[index].setVisibility(value > 0 ? VISIBLE : INVISIBLE);
        }
        error.setText("");
        error.setVisibility(GONE);
        String message = DateValues.validationError(values[0][0], values[1][0], values[2][0], false);
        if (fields.length > 1 && message != null) showError(message);
    }

    public boolean validate(boolean requireComplete) {
        String message = DateValues.validationError(values[0][0], values[1][0], values[2][0], requireComplete);
        if (message != null) {
            showError(message);
            return false;
        }
        return true;
    }

    private void showError(String message) {
        error.setVisibility(VISIBLE);
        error.setText(message);
        error.post(() -> error.requestRectangleOnScreen(new android.graphics.Rect(0, 0,
                error.getWidth(), error.getHeight()), false));
    }

    private int dp(int value) { return UiKit.dp(getContext(), value); }

    static final class DateValues {
        private DateValues() { }

        static String validationError(long date, long start, long end, boolean requireComplete) {
            if (start > 0 && end > 0 && end <= start) return "结束时间需要晚于开始时间";
            if (requireComplete && (date <= 0 || start <= 0 || end <= 0)) return "请选择日期、开始和结束时间";
            return null;
        }

        static long moveDate(long time, Calendar from, Calendar to) {
            Calendar utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            utc.clear();
            utc.set(from.get(Calendar.YEAR), from.get(Calendar.MONTH), from.get(Calendar.DAY_OF_MONTH));
            long first = utc.getTimeInMillis();
            utc.clear();
            utc.set(to.get(Calendar.YEAR), to.get(Calendar.MONTH), to.get(Calendar.DAY_OF_MONTH));
            int days = (int) ((utc.getTimeInMillis() - first) / 86_400_000L);
            Calendar moved = Calendar.getInstance(from.getTimeZone());
            moved.setTimeInMillis(time);
            moved.add(Calendar.DAY_OF_MONTH, days);
            return moved.getTimeInMillis();
        }
    }
}
