package com.hawson.timeassistant.design;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Shared presentation only; loaders own credential and cancellation policy. */
public final class ModelSelectionSheet {
    public static final class Option {
        public final String id, name;
        public Option(String id, String name) { this.id = id; this.name = name; }
    }
    public static final class Action {
        final String name; final Runnable run;
        public Action(String name, Runnable run) { this.name = name; this.run = run; }
    }
    private ModelSelectionSheet() { }
    public static void show(Context context, String title, String current, Callable<List<Option>> load,
                            Runnable cancel, Consumer<Option> selected, List<Action> actions) {
        BottomSheetDialog sheet = new BottomSheetDialog(context);
        MotionSpec.configureDialog(sheet);
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad * 2);
        root.setBackground(UiKit.background(context, UiKit.surface(context), 0)); root.setClipToOutline(true);
        root.addView(UiKit.sheetHeading(context, title, sheet::dismiss));
        EditText search = new EditText(context);
        search.setHint("搜索模型"); search.setSingleLine(true); UiKit.input(search);
        search.setVisibility(View.GONE); root.addView(search, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context); list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        list.addView(content);
        for (Action action : actions) list.addView(UiKit.row(context, action.name, "", 0, () -> {
            sheet.dismiss(); action.run.run();
        }));
        scroll.addView(list); root.addView(scroll, new LinearLayout.LayoutParams(-1, -2)); sheet.setContentView(root);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Handler main = new Handler(Looper.getMainLooper());
        boolean[] closed = {false};
        boolean[] loading = {false};
        List<Option> loaded = new ArrayList<>();
        Runnable render = () -> {
            content.removeAllViews();
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (Option option : loaded) {
                if (!query.isEmpty() && !(option.name + " " + option.id).toLowerCase(Locale.ROOT).contains(query)) continue;
                boolean active = option.id.equals(current);
                View row = UiKit.row(context, option.name, active ? "当前模型" : "", 0, () -> {
                    if (closed[0]) return;
                    sheet.dismiss(); selected.accept(option);
                });
                row.setSelected(active);
                if (active) row.setBackground(UiKit.background(context, UiKit.surface(context), UiKit.border(context)));
                TextView mark = (TextView) ((ViewGroup) row).getChildAt(((ViewGroup) row).getChildCount() - 1);
                mark.setText(active ? "✓" : ""); mark.setTextColor(UiKit.accent(context));
                androidx.core.view.ViewCompat.setStateDescription(row, active ? "已选择" : "未选择");
                content.addView(row);
            }
            if (content.getChildCount() == 0) {
                TextView empty = new TextView(context); empty.setText("没有匹配的模型");
                empty.setTextColor(UiKit.secondary(context)); empty.setPadding(0, pad, 0, pad); content.addView(empty);
            }
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { if (!loading[0]) render.run(); }
            public void afterTextChanged(android.text.Editable s) { }
        });
        Runnable[] retry = new Runnable[1];
        retry[0] = () -> {
            if (closed[0] || loading[0]) return;
            loading[0] = true;
            content.removeAllViews();
            TextView status = new TextView(context); status.setText("正在获取模型…");
            status.setTextColor(UiKit.secondary(context)); status.setPadding(0, pad, 0, pad); content.addView(status);
            worker.execute(() -> {
                try {
                    List<Option> options = load.call();
                    main.post(() -> {
                        if (closed[0]) return;
                        loading[0] = false;
                        content.removeAllViews();
                        if (options.isEmpty()) content.addView(UiKit.row(context, "没有可选模型", "已保留当前选择，点击重试", 0, retry[0]));
                        loaded.clear(); loaded.addAll(options);
                        search.setVisibility(options.size() > 6 ? View.VISIBLE : View.GONE);
                        if (!options.isEmpty()) render.run();
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (closed[0]) return;
                        loading[0] = false;
                        content.removeAllViews();
                        content.addView(UiKit.row(context, "模型获取失败", "已保留原选择，点击重试", 0, retry[0]));
                    });
                }
            });
        };
        sheet.setOnDismissListener(ignored -> { closed[0] = true; cancel.run(); worker.shutdownNow(); });
        sheet.getBehavior().setShouldRemoveExpandedCorners(false);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        sheet.show();
        retry[0].run();
    }
}
