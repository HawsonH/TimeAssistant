package com.hawson.timeassistant.ui;

import android.view.LayoutInflater;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import java.util.*;
import java.util.function.Supplier;
import com.hawson.timeassistant.design.UiKit;

import androidx.annotation.Nullable;

import com.hawson.timeassistant.databinding.PageChatBinding;

/** Owns the chat-page binding and its latest reactive state. */
public final class ChatPageController {
    private PageChatBinding binding;
    private ChatPageUiState state;
    private final Map<String, Entry> rows = new HashMap<>();
    private String foreground = "";
    boolean foregroundChanged(String signature) {
        boolean changed = !foreground.equals(signature);
        foreground = signature;
        return changed;
    }
    public static final class Row {
        final String key, signature;
        final Supplier<View> create;
        public Row(String key, String signature, Supplier<View> create) {
            this.key = key; this.signature = signature; this.create = create;
        }
    }
    private static final class Entry {
        final String signature;
        final View view;
        Entry(String signature, View view) { this.signature = signature; this.view = view; }
    }

    void invalidate(String id) { rows.remove(id); }

    /** Keep unchanged message views and text selection intact. */
    void reconcile(LinearLayout container, List<Row> next) {
        boolean animate = container.isAttachedToWindow() && container.getChildCount() > 0;
        Set<View> changed = new HashSet<>();
        Set<String> retained = new HashSet<>();
        Set<View> wanted = new HashSet<>();
        List<View> ordered = new ArrayList<>();
        for (Row row : next) {
            retained.add(row.key);
            Entry entry = rows.get(row.key);
            if (entry == null || !entry.signature.equals(row.signature)) {
                entry = new Entry(row.signature, row.create.get());
                changed.add(entry.view);
                rows.put(row.key, entry);
            }
            wanted.add(entry.view); ordered.add(entry.view);
        }
        for (int index = container.getChildCount() - 1; index >= 0; index--) {
            if (!wanted.contains(container.getChildAt(index))) container.removeViewAt(index);
        }
        for (int index = 0; index < ordered.size(); index++) {
            View view = ordered.get(index);
            if (view.getParent() == container && container.indexOfChild(view) == index) continue;
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            container.addView(view, index);
            if (animate && changed.contains(view)) com.hawson.timeassistant.design.MotionSpec.enter(view);
        }
        rows.keySet().retainAll(retained);
    }

    public PageChatBinding inflate(LayoutInflater inflater) {
        binding = PageChatBinding.inflate(inflater);
        return binding;
    }

    public void render(ChatPageUiState nextState) { state = nextState; }
    @Nullable public PageChatBinding getBinding() { return binding; }
    @Nullable public ChatPageUiState getState() { return state; }
    public void clearBinding() { binding = null; rows.clear(); foreground = ""; }

    View disconnected(Context context, Runnable configure) {
        LinearLayout group = UiKit.groupList(context);
        UiKit.addRows(group, 16, UiKit.row(context, "连接 AI", "尚未连接",
                com.hawson.timeassistant.R.drawable.ic_sparkles, configure));
        return group;
    }

}
