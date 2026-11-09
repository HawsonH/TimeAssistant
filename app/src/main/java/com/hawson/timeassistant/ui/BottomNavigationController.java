/**
 * 底部导航控制器
 *
 * 绑定底部「对话 / 日程 / 专注」标签的选中样式与页面切换回调。
 */
package com.hawson.timeassistant.ui;

import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;

import com.hawson.timeassistant.R;
import com.hawson.timeassistant.databinding.ActivityTimeAssistantBinding;

/** Owns bottom-navigation binding and selection styling, independently of activity business state. */
final class BottomNavigationController {
    interface OnPageSelectedListener {
        void onPageSelected(int page);
    }

    private final LinearLayout[] items;
    private final ImageView[] icons;
    private final TextView[] labels;
    private final int selectedColor;
    private final int defaultColor;
    private final com.hawson.timeassistant.design.SlidingIndicatorLayout track;

    BottomNavigationController(@NonNull ActivityTimeAssistantBinding binding,
                               @NonNull OnPageSelectedListener listener) {
        items = new LinearLayout[]{binding.navChat, binding.navSchedule, binding.navFocus};
        track = binding.bottomNavigation;
        track.setIndicatorColor(AppTheme.resolveColor(binding.getRoot().getContext(),
                R.attr.colorPrimarySoft, R.color.brand_primary_soft));
        icons = new ImageView[]{binding.navChatIcon, binding.navScheduleIcon, binding.navFocusIcon};
        labels = new TextView[]{binding.navChatLabel, binding.navScheduleLabel, binding.navFocusLabel};
        selectedColor = AppTheme.resolveColor(
                binding.getRoot().getContext(),
                R.attr.colorBrandPrimary,
                R.color.brand_primary
        );
        defaultColor = binding.getRoot().getContext().getColor(R.color.text_secondary);
        for (int index = 0; index < items.length; index++) {
            final int page = index;
            items[index].setOnClickListener(view -> listener.onPageSelected(page));
            android.util.TypedValue ripple = new android.util.TypedValue();
            items[index].getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            items[index].setBackgroundResource(ripple.resourceId);
            com.hawson.timeassistant.design.MotionSpec.press(items[index]);
        }
    }

    void select(int selectedPage) {
        track.selectIndicator(selectedPage);
        for (int index = 0; index < items.length; index++) {
            boolean selected = index == selectedPage;
            int color = selected ? selectedColor : defaultColor;
            icons[index].setColorFilter(color);
            labels[index].setTextColor(color);
            items[index].setSelected(selected);
            ViewCompat.setStateDescription(items[index], selected ? "已选择" : null);
        }
    }
}
