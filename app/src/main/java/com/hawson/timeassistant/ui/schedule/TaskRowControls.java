package com.hawson.timeassistant.ui.schedule;

import android.content.Context;
import android.annotation.SuppressLint;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.view.View;
import com.hawson.timeassistant.design.MotionSpec;

import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.TooltipCompat;

/** Presentation-only controls; task writes and navigation belong to the injected actions. */
@SuppressLint("ViewConstructor") // Requires injected commands; never inflated from XML.
public final class TaskRowControls extends LinearLayout {
    public interface Actions {
        void completeOrUndo();
        void edit();
        void focus();
        void delete();
    }

    public TaskRowControls(Context context, String title, boolean completed, boolean cancelled,
                           int menuIcon, int tint, Actions actions) {
        super(context);
        setGravity(Gravity.CENTER_VERTICAL);
        int size = Math.round(48 * getResources().getDisplayMetrics().density);
        CheckBox complete = new CheckBox(context);
        complete.setChecked(completed);
        complete.setEnabled(!cancelled);
        complete.setContentDescription((completed ? "撤销完成：" : "完成：") + title);
        complete.setOnClickListener(view -> {
            complete.setChecked(completed);
            actions.completeOrUndo();
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        });
        MotionSpec.press(complete);
        addView(complete, new LayoutParams(size, size));
        ImageButton more = new ImageButton(context);
        more.setImageResource(menuIcon);
        more.setColorFilter(tint);
        more.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        more.setContentDescription("更多操作：" + title);
        TooltipCompat.setTooltipText(more, "更多操作");
        more.setOnClickListener(view -> {
            PopupMenu menu = new PopupMenu(context, more);
            menu.getMenu().add(0, 1, 0, "编辑");
            if (!completed && !cancelled) menu.getMenu().add(0, 2, 1, "进入专注");
            menu.getMenu().add(0, 3, 2, "删除");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) actions.edit();
                else if (item.getItemId() == 2) actions.focus();
                else actions.delete();
                return true;
            });
            menu.show();
        });
        MotionSpec.press(more);
        addView(more, new LayoutParams(size, size));
    }

    public void content(View details) {
        addView(details, 1, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
    }
}
