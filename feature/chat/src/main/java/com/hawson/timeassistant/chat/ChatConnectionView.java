package com.hawson.timeassistant.chat;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.design.ModelSelectionSheet;
import java.util.*;
import java.util.function.Consumer;

public final class ChatConnectionView extends LinearLayout {
    private final MaterialButton picker;
    private final AiProfilesPort profiles;
    public ChatConnectionView(Context context, AiProfilesPort profiles, AiSettingsPort settings,
                              Consumer<AiProfile> select, Consumer<AiModelOption> modelSelected,
                              Runnable configure, Runnable usage) {
        super(context); setOrientation(VERTICAL); this.profiles = profiles;
        picker = new MaterialButton(context, null, android.R.attr.borderlessButtonStyle);
        picker.setTextSize(13); picker.setAllCaps(false); picker.setMaxLines(2);
        picker.setEllipsize(android.text.TextUtils.TruncateAt.END);
        picker.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        picker.setLetterSpacing(0);
        picker.setTextColor(com.hawson.timeassistant.design.UiKit.secondary(context));
        picker.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        picker.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        addView(picker, new LayoutParams(-1, -2)); refresh();
        picker.setOnClickListener(view -> {
            AiProfile active = profiles.active();
            if (active == null) { configure.run(); return; }
            AiCancellation cancel = new AiCancellation();
            List<ModelSelectionSheet.Action> actions = Arrays.asList(
                    new ModelSelectionSheet.Action("切换连接 · " + active.name, () -> {
                        List<AiProfile> values = profiles.list();
                        String[] labels = new String[values.size()]; int selected = -1;
                        for (int i = 0; i < labels.length; i++) {
                            labels[i] = values.get(i).name;
                            if (values.get(i).id.equals(active.id)) selected = i;
                        }
                        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(context).setTitle("切换连接")
                                .setSingleChoiceItems(labels, selected, (dialog, index) -> {
                                    dialog.dismiss(); select.accept(values.get(index));
                                }).setNegativeButton("取消", null).show();
                    }), new ModelSelectionSheet.Action("账号与用量", usage));
            ModelSelectionSheet.show(context, active.name, active.model, () -> {
                List<ModelSelectionSheet.Option> items = new ArrayList<>();
                for (AiModelOption option : settings.modelOptions(active, "", cancel)) {
                    items.add(new ModelSelectionSheet.Option(option.id, option.name));
                }
                return items;
            }, cancel::cancel, option -> {
                modelSelected.accept(new AiModelOption(option.id, option.name)); refresh();
            }, actions);
        });
    }
    public void refresh() {
        AiProfile active = profiles.active();
        String name = active == null ? "选择模型" : getContext().getSharedPreferences("ai_model_names", 0)
                .getString(active.id + ":" + active.model, active.model);
        picker.setText(active == null ? "选择模型 · 未连接 ▾" : active.name + " · " + name + " ▾");
        picker.setContentDescription(active == null ? "选择 AI 连接与模型" : active.name + "，" + name + "，选择模型");
    }
}
