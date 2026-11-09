package com.hawson.timeassistant.ui.settings;

import android.app.TimePickerDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.SwitchCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hawson.timeassistant.design.UiKit;
import com.hawson.timeassistant.proactive.ProactiveRoutine;
import com.hawson.timeassistant.proactive.ProactiveSettings;
import com.hawson.timeassistant.proactive.TodoReviewSlot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Settings editor with host-owned authorization, persistence and execution. */
public final class ProactiveSettingsView extends LinearLayout {
    public interface Callbacks {
        void save(ProactiveSettings settings);
        void requestMasterEnabled(boolean enabled, Consumer<Boolean> result);
        void requestNotifications(boolean enabled, Consumer<Boolean> result);
        void runNow();
        void showMessage(String message);
    }

    private final ProactiveSettings settings;
    private final Callbacks callbacks;
    private final boolean aiConfigured;
    private final List<Runnable> dependencies = new ArrayList<>();
    private SwitchCompat master;
    private LinearLayout slots;
    private Button add;
    private Button run;
    private boolean updatingMaster;
    private boolean masterRequestPending;

    public ProactiveSettingsView(Context context, ProactiveSettings settings,
                                 boolean aiConfigured, boolean notificationGranted,
                                 Callbacks callbacks) {
        super(context);
        this.settings = settings;
        this.callbacks = callbacks;
        this.aiConfigured = aiConfigured;
        setOrientation(VERTICAL);
        buildMaster();
        buildReviews();
        buildAttention();
        buildDelivery(notificationGranted);
        buildLimits();
        refreshDependencies();
    }

    private void buildMaster() {
        LinearLayout section = section("主动整理", "只提供建议，不会自动修改任务");
        master = toggle("启用主动整理", settings.enabled);
        section.addView(master);
        if (!aiConfigured) section.addView(note("请先在“AI 服务”中完成配置，之后才能开启"));
        run = action("立即检查一次");
        section.addView(run, spaced());
        run.setOnClickListener(view -> callbacks.runNow());
        master.setOnCheckedChangeListener((button, checked) -> {
            if (updatingMaster || masterRequestPending) return;
            setMasterChecked(settings.enabled);
            masterRequestPending = true;
            refreshDependencies();
            callbacks.requestMasterEnabled(checked, accepted -> {
                masterRequestPending = false;
                settings.enabled = accepted;
                setMasterChecked(accepted);
                callbacks.save(settings);
                refreshDependencies();
            });
        });
    }

    private void setMasterChecked(boolean checked) {
        updatingMaster = true;
        master.setChecked(checked);
        updatingMaster = false;
    }

    private void buildReviews() {
        LinearLayout section = section("待办整理", "每天独立检查未排期待办，并可建议排入日程");
        SwitchCompat enabled = toggle("启用待办整理", settings.todoReviewEnabled);
        section.addView(enabled);
        dependencies.add(() -> enabled.setEnabled(settings.enabled));
        enabled.setOnCheckedChangeListener((button, checked) -> {
            settings.todoReviewEnabled = checked;
            changed();
        });
        slots = column();
        section.addView(slots);
        add = action("添加时段");
        section.addView(add, spaced());
        add.setOnClickListener(view -> pickMinute(nextAvailableMinute(), minute -> {
            if (!canEditReviews() || settings.getTodoReviewSlots().size() >= 6) return;
            if (duplicateMinute(null, minute)) return;
            settings.addTodoReviewSlot(minute);
            changed();
        }));
    }

    private boolean canEditReviews() {
        return settings.enabled && settings.todoReviewEnabled;
    }

    private void renderSlots() {
        slots.removeAllViews();
        List<TodoReviewSlot> current = settings.getTodoReviewSlots();
        for (int index = 0; index < current.size(); index++) {
            TodoReviewSlot slot = current.get(index);
            LinearLayout group = column();
            SwitchCompat enabled = toggle("每日时段 " + (index + 1), slot.enabled);
            enabled.setEnabled(canEditReviews());
            group.addView(enabled);
            LinearLayout controls = new LinearLayout(getContext());
            controls.setGravity(Gravity.CENTER_VERTICAL);
            Button time = action(clock(slot.minuteOfDay));
            time.setContentDescription("每日时段 " + (index + 1) + "，时间 " + clock(slot.minuteOfDay));
            time.setEnabled(canEditReviews());
            controls.addView(time, new LayoutParams(0, -2, 1));
            AppCompatImageButton remove = new AppCompatImageButton(getContext());
            remove.setImageResource(com.hawson.timeassistant.design.R.drawable.ui_ic_trash);
            remove.setImageTintList(ColorStateList.valueOf(UiKit.secondary(getContext())));
            remove.setBackgroundResource(android.R.color.transparent);
            UiKit.tooltip(remove, "移除每日时段 " + (index + 1));
            remove.setEnabled(canEditReviews() && current.size() > 1);
            controls.addView(remove, new LayoutParams(dp(48), dp(48)));
            LayoutParams controlsParams = new LayoutParams(-1, -2);
            controlsParams.bottomMargin = dp(8);
            group.addView(controls, controlsParams);
            slots.addView(group);
            enabled.setOnCheckedChangeListener((button, checked) -> {
                settings.updateTodoReviewSlot(slot.withEnabled(checked));
                changed();
            });
            time.setOnClickListener(view -> pickMinute(slot.minuteOfDay, minute -> {
                if (!canEditReviews() || duplicateMinute(slot.id, minute)) return;
                settings.updateTodoReviewSlot(slot.withTime(minute));
                changed();
            }));
            remove.setOnClickListener(view -> {
                if (!canEditReviews() || settings.getTodoReviewSlots().size() <= 1) return;
                settings.removeTodoReviewSlot(slot.id);
                changed();
            });
        }
        add.setEnabled(canEditReviews() && current.size() < 6);
    }

    private boolean duplicateMinute(String exceptId, int minute) {
        for (TodoReviewSlot slot : settings.getTodoReviewSlots()) {
            if (!slot.id.equals(exceptId) && slot.minuteOfDay == minute) {
                callbacks.showMessage("这个时间已存在，请选择其他时间");
                return true;
            }
        }
        return false;
    }

    private int nextAvailableMinute() {
        int candidate = settings.getTodoReviewSlots().get(0).minuteOfDay;
        for (int offset = 60; offset <= 1440; offset++) {
            int minute = (candidate + offset) % 1440;
            boolean exists = false;
            for (TodoReviewSlot slot : settings.getTodoReviewSlots()) {
                if (slot.minuteOfDay == minute) exists = true;
            }
            if (!exists) return minute;
        }
        return candidate;
    }

    private void buildAttention() {
        LinearLayout section = section("注意页提醒", "有待处理事项时，在以下时间检查");
        SwitchCompat attention = toggle("启用注意页提醒", settings.attentionTriggerEnabled);
        section.addView(attention);
        dependencies.add(() -> attention.setEnabled(settings.enabled));
        attention.setOnCheckedChangeListener((button, checked) -> {
            settings.attentionTriggerEnabled = checked;
            changed();
        });
        routine(section, ProactiveRoutine.MORNING, "早间检查");
        routine(section, ProactiveRoutine.MIDDAY, "日间检查");
        routine(section, ProactiveRoutine.AFTERNOON, "下午检查");
        routine(section, ProactiveRoutine.EVENING, "晚间检查");
    }

    private void routine(LinearLayout parent, ProactiveRoutine routine, String title) {
        LinearLayout group = column();
        SwitchCompat enabled = toggle(title, settings.isRoutineEnabled(routine));
        Button time = action(clock(settings.minuteFor(routine)));
        time.setContentDescription(title + "，时间 " + clock(settings.minuteFor(routine)));
        group.addView(enabled);
        LayoutParams timeParams = new LayoutParams(-1, -2);
        timeParams.bottomMargin = dp(8);
        group.addView(time, timeParams);
        parent.addView(group);
        dependencies.add(() -> {
            enabled.setEnabled(settings.enabled && settings.attentionTriggerEnabled);
            time.setEnabled(settings.enabled && settings.attentionTriggerEnabled);
        });
        enabled.setOnCheckedChangeListener((button, checked) -> {
            switch (routine) {
                case MORNING: settings.morningEnabled = checked; break;
                case MIDDAY: settings.middayEnabled = checked; break;
                case AFTERNOON: settings.afternoonEnabled = checked; break;
                case EVENING: settings.eveningEnabled = checked; break;
                default: return;
            }
            changed();
        });
        time.setOnClickListener(view -> pickMinute(settings.minuteFor(routine), minute -> {
            if (!settings.enabled || !settings.attentionTriggerEnabled) return;
            switch (routine) {
                case MORNING: settings.morningMinute = minute; break;
                case MIDDAY: settings.middayMinute = minute; break;
                case AFTERNOON: settings.afternoonMinute = minute; break;
                case EVENING: settings.eveningMinute = minute; break;
                default: return;
            }
            time.setText(clock(minute));
            time.setContentDescription(title + "，时间 " + clock(minute));
            changed();
        }));
    }

    private void buildDelivery(boolean notificationGranted) {
        LinearLayout section = section("建议与通知", "所有任务操作仍须确认");
        SwitchCompat background = toggle("后台生成完整 AI 建议", settings.backgroundAiEnabled);
        section.addView(background);
        dependencies.add(() -> background.setEnabled(settings.enabled));
        background.setOnCheckedChangeListener((button, checked) -> {
            settings.backgroundAiEnabled = checked;
            changed();
        });
        SwitchCompat notification = toggle("发送系统通知", settings.notificationEnabled && notificationGranted);
        section.addView(notification);
        dependencies.add(() -> notification.setEnabled(settings.enabled));
        boolean[] updating = {false};
        notification.setOnCheckedChangeListener((button, checked) -> {
            if (updating[0]) return;
            callbacks.requestNotifications(checked, accepted -> {
                updating[0] = true;
                notification.setChecked(accepted);
                updating[0] = false;
                settings.notificationEnabled = accepted;
                changed();
            });
        });
        section.addView(note("关闭后台生成时，只写入简短本地提示，不调用模型。"));
    }

    private void buildLimits() {
        LinearLayout section = section("频率与免打扰", "专注进行中会延后检查，不打断计时");
        Button limit = action("每天最多发送 " + settings.dailyLimit + " 条");
        section.addView(limit, spaced());
        dependencies.add(() -> limit.setEnabled(settings.enabled));
        limit.setOnClickListener(view -> new com.hawson.timeassistant.design.SoftAlertDialogBuilder(getContext())
                .setTitle("每天最多发送")
                .setSingleChoiceItems(new String[]{"1 条", "2 条", "3 条", "4 条", "5 条", "6 条"},
                        settings.dailyLimit - 1, (dialog, which) -> {
                            settings.dailyLimit = which + 1;
                            limit.setText("每天最多发送 " + settings.dailyLimit + " 条");
                            changed();
                            dialog.dismiss();
                        }).setNegativeButton("取消", null).show());
        quietTime(section, true);
        quietTime(section, false);
        section.addView(note("相同内容当天只提示一次；查看历史日期不会补发。\n系统可能在设定时间附近稍后运行。"));
    }

    private void quietTime(LinearLayout parent, boolean start) {
        String title = start ? "免打扰开始 " : "免打扰结束 ";
        Button time = action(title + clock(start ? settings.quietStartMinute : settings.quietEndMinute));
        parent.addView(time, spaced());
        dependencies.add(() -> time.setEnabled(settings.enabled));
        time.setOnClickListener(view -> pickMinute(start ? settings.quietStartMinute : settings.quietEndMinute,
                minute -> {
                    if (start) settings.quietStartMinute = minute;
                    else settings.quietEndMinute = minute;
                    time.setText(title + clock(minute));
                    changed();
                }));
    }

    private void changed() {
        callbacks.save(settings);
        refreshDependencies();
    }

    private void refreshDependencies() {
        master.setEnabled(!masterRequestPending && (aiConfigured || settings.enabled));
        run.setEnabled(settings.enabled && aiConfigured && hasEnabledContent());
        for (Runnable dependency : dependencies) dependency.run();
        renderSlots();
    }

    private boolean hasEnabledContent() {
        if (settings.todoReviewEnabled) {
            for (TodoReviewSlot slot : settings.getTodoReviewSlots()) if (slot.enabled) return true;
        }
        return settings.attentionTriggerEnabled && (settings.morningEnabled || settings.middayEnabled
                || settings.afternoonEnabled || settings.eveningEnabled);
    }

    private LinearLayout section(String title, String subtitle) {
        addView(UiKit.sectionHeader(getContext(), title));
        LinearLayout section = UiKit.group(getContext());
        TextView summary = note(subtitle);
        summary.setPadding(0, 0, 0, dp(4));
        section.addView(summary);
        addView(section, new LayoutParams(-1, -2));
        return section;
    }

    private LayoutParams spaced() {
        LayoutParams params = new LayoutParams(-1, -2);
        params.topMargin = dp(8);
        return params;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        column.setLayoutParams(new LayoutParams(-1, -2));
        return column;
    }

    private TextView note(String value) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(12);
        text.setTextColor(UiKit.secondary(getContext()));
        text.setPadding(0, dp(4), 0, dp(8));
        return text;
    }

    private SwitchCompat toggle(String title, boolean checked) {
        SwitchCompat toggle = new SwitchCompat(getContext());
        toggle.setText(title);
        toggle.setTextSize(14);
        toggle.setTextColor(UiKit.text(getContext()));
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setMinHeight(dp(48));
        toggle.setPadding(0, dp(8), 0, dp(8));
        toggle.setChecked(checked);
        toggle.setLayoutParams(new LayoutParams(-1, -2));
        return toggle;
    }

    private Button action(String title) {
        return UiKit.secondaryButton(getContext(), title);
    }

    private void pickMinute(int minute, IntConsumer selected) {
        new TimePickerDialog(getContext(), (picker, hour, value) -> selected.accept(hour * 60 + value),
                minute / 60, minute % 60, true).show();
    }

    private String clock(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    private int dp(int value) { return UiKit.dp(getContext(), value); }
}
