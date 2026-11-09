package com.hawson.timeassistant.design;

import android.content.Context;
import android.annotation.SuppressLint;
import android.graphics.Typeface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;

/** Independent appearance controls; no knowledge of the application's settings page. */
@SuppressLint("ViewConstructor") // Requires an injected tutorial command; not an XML widget.
public final class AppearanceSettingsView extends LinearLayout {
    public AppearanceSettingsView(Context context, Runnable replayTutorial) {
        super(context);
        setOrientation(VERTICAL);
        AppearancePreferences preferences = new AppearancePreferences(context);
        int night = preferences.nightMode();
        choice("明暗主题", new String[]{"跟随系统", "浅色", "深色"},
                night == AppCompatDelegate.MODE_NIGHT_NO ? 1
                        : night == AppCompatDelegate.MODE_NIGHT_YES ? 2 : 0,
                index -> preferences.setNightMode(index == 1 ? AppCompatDelegate.MODE_NIGHT_NO
                        : index == 2 ? AppCompatDelegate.MODE_NIGHT_YES
                        : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        choice("动态效果", new String[]{"完整", "减少", "关闭"},
                preferences.motionMode().ordinal(),
                index -> {
                    MotionSpec.cancelTree(getRootView());
                    preferences.setMotionMode(MotionSpec.Mode.values()[index]);
                });
        addView(UiKit.sectionHeader(context, "教程"));
        LinearLayout tutorial = UiKit.groupList(context);
        UiKit.addRows(tutorial, 16, UiKit.row(context, "重新体验新手教程", "", 0, replayTutorial));
        addView(tutorial, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private void choice(String title, String[] values, int selected,
                        java.util.function.IntConsumer changed) {
        addView(UiKit.sectionHeader(getContext(), title));
        LinearLayout frame = UiKit.group(getContext());
        frame.setPadding(dp(8), dp(8), dp(8), dp(8));
        android.widget.RadioGroup group = new android.widget.RadioGroup(getContext());
        group.setOrientation(HORIZONTAL);
        group.setPadding(dp(4), dp(4), dp(4), dp(4));
        group.setBackground(UiKit.background(getContext(), UiKit.border(getContext()), 0, UiKit.RADIUS_PILL));
        group.setContentDescription(title);
        for (int index = 0; index < values.length; index++) {
            android.widget.RadioButton option = new android.widget.RadioButton(getContext());
            option.setId(View.generateViewId());
            option.setText(values[index]);
            option.setTextSize(14);
            option.setGravity(android.view.Gravity.CENTER);
            option.setButtonDrawable((android.graphics.drawable.Drawable) null);
            option.setMinHeight(dp(48));
            group.addView(option, new android.widget.RadioGroup.LayoutParams(0, -2, 1));
            option.setChecked(index == selected);
            MotionSpec.press(option);
        }
        Runnable style = () -> {
            for (int index = 0; index < group.getChildCount(); index++) {
                android.widget.RadioButton option = (android.widget.RadioButton) group.getChildAt(index);
                option.setTextColor(option.isChecked() ? UiKit.accent(getContext()) : UiKit.secondary(getContext()));
                option.setBackground(option.isChecked() ? UiKit.background(getContext(), UiKit.surface(getContext()), 0, UiKit.RADIUS_PILL) : null);
                option.setTypeface(null, option.isChecked() ? Typeface.BOLD : Typeface.NORMAL);
            }
        };
        style.run();
        group.setOnCheckedChangeListener((parent, id) -> {
            style.run();
            for (int index = 0; index < group.getChildCount(); index++) {
                if (group.getChildAt(index).getId() == id) { changed.accept(index); break; }
            }
        });
        frame.addView(group, new LayoutParams(-1, -2));
        addView(frame, new LayoutParams(-1, -2));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
