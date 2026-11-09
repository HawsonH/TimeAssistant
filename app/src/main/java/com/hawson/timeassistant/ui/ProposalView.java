package com.hawson.timeassistant.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.appcompat.widget.PopupMenu;
import com.google.android.material.button.MaterialButton;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.design.MotionSpec;
import com.hawson.timeassistant.design.UiKit;
import com.hawson.timeassistant.domain.ChatMessage;
import java.util.*;

/** One assistant bubble; business commands are delegated to a fresh host. */
final class ProposalView extends LinearLayout {
    interface Host {
        String description(int index);
        String status(int index);
        void confirm(List<Integer> indexes);
        void cancel(List<Integer> indexes);
        void changed();
        default void undo() { }
        default void view(boolean focusPrepared) { }
        default void sources(List<String> sources) { }
    }

    private final ProposalPresentation presentation;
    private final ChatDisplayState state;
    private final Host host;
    private final Set<Integer> selected;
    private final List<View> mutationControls = new ArrayList<>();
    private final boolean selecting;
    private final boolean expanded;
    private boolean commandDispatched;

    /** Compatibility for hosts which still render narrative separately. */
    ProposalView(Context context, ChatMessage message, int count, List<Integer> pending,
                 boolean historical, ChatDisplayState state, Host host) {
        this(context, message, "", new ProposalPresentation(message, count, pending, historical,
                null, false, "", false, false), state, host);
    }

    ProposalView(Context context, ChatMessage message, String narrative,
                 ProposalPresentation presentation, ChatDisplayState state, Host host) {
        super(context);
        if (!message.getId().equals(presentation.messageId)) {
            throw new IllegalArgumentException("presentation belongs to a different message");
        }
        this.presentation = presentation;
        this.state = state;
        this.host = host;
        // Historical rendering must not prune today's session selection.
        selected = presentation.historical ? new HashSet<>() : state.selections.computeIfAbsent(
                message.getId(), key -> new HashSet<>(presentation.pendingIndexes));
        if (!presentation.historical) selected.retainAll(presentation.pendingIndexes);
        selecting = state.selectingPlans.contains(message.getId()) && editable();
        expanded = presentation.actionCount <= 3 || state.expandedPlans.contains(message.getId());
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(16), dp(16), dp(16));
        setBackground(UiKit.background(context, UiKit.surface(context), 0));
        if (narrative != null && !narrative.trim().isEmpty()) {
            TextView body = text(narrative, 14, false);
            body.setPadding(0, 0, 0, dp(12));
            addView(body, new LayoutParams(-1, -2));
        }
        addHeader(message);
        Button confirm = command(presentation.confirmationLabel(selected), 0);
        Runnable updateConfirm = () -> {
            confirm.setText(presentation.confirmationLabel(selected));
            confirm.setEnabled(editable() && !commandDispatched && !selected.isEmpty());
        };
        int visible = expanded ? presentation.actionCount : Math.min(3, presentation.actionCount);
        for (int index = 0; index < visible; index++) {
            addRow(index, updateConfirm);
            if (index < visible - 1) addDivider();
        }
        if (!expanded) {
            TextView expand = action("查看全部 " + presentation.actionCount + " 项");
            expand.setOnClickListener(v -> {
                state.expandedPlans.add(presentation.messageId);
                host.changed();
            });
            addView(expand, new LayoutParams(-1, -2));
        } else if (!presentation.historical && !presentation.pendingIndexes.isEmpty()) {
            mutationControls.add(confirm);
            updateConfirm.run();
            confirm.setOnClickListener(v -> {
                if (!confirm.isEnabled()) return;
                List<Integer> indexes = new ArrayList<>();
                for (Integer item : presentation.pendingIndexes) if (selected.contains(item)) indexes.add(item);
                if (!indexes.isEmpty()) dispatch(() -> host.confirm(indexes));
            });
            addView(confirm, new LayoutParams(-1, -2));
        }
        addOutcome();
    }

    private boolean editable() {
        return !presentation.historical && !presentation.busy && !presentation.pendingIndexes.isEmpty();
    }

    private void addHeader(ChatMessage message) {
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(48));
        header.addView(text(presentation.historical || presentation.pendingIndexes.isEmpty() ? "安排结果" : "建议安排", 17, true),
                new LayoutParams(0, -2, 1));
        if (editable() || !message.getContextSources().isEmpty() || !message.getContent().isEmpty()) {
            ImageButton more = icon("安排选项");
            header.addView(more, new LayoutParams(dp(48), dp(48)));
            more.setOnClickListener(v -> {
                PopupMenu menu = new PopupMenu(getContext(), more);
                if (!message.getContent().isEmpty()) menu.getMenu().add(0, 4, 3, "复制");
                if (editable() && !commandDispatched) {
                    menu.getMenu().add(0, 1, 0, selecting ? "完成选择" : "选择操作");
                    menu.getMenu().add(0, 2, 1, "取消全部");
                }
                if (!message.getContextSources().isEmpty()) menu.getMenu().add(0, 3, 2, "读取来源");
                menu.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 4) {
                        android.content.ClipboardManager clipboard = getContext()
                                .getSystemService(android.content.ClipboardManager.class);
                        if (clipboard != null) clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("时屿", message.getContent()));
                    } else if (item.getItemId() == 3) host.sources(message.getContextSources());
                    else if (commandDispatched || !editable()) return true;
                    else if (item.getItemId() == 2) {
                        dispatch(() -> host.cancel(new ArrayList<>(presentation.pendingIndexes)));
                    } else {
                        if (selecting) state.selectingPlans.remove(presentation.messageId);
                        else state.selectingPlans.add(presentation.messageId);
                        state.expandedPlans.add(presentation.messageId);
                        host.changed();
                    }
                    return true;
                });
                menu.show();
            });
        }
        addView(header, new LayoutParams(-1, -2));
    }

    private void addRow(int index, Runnable updateConfirm) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(0, dp(12), 0, dp(12));
        boolean pending = presentation.pendingIndexes.contains(index);
        if (selecting && pending) {
            CheckBox choice = new CheckBox(getContext());
            choice.setChecked(selected.contains(index));
            choice.setContentDescription("选择：" + host.description(index));
            mutationControls.add(choice);
            MotionSpec.press(choice);
            row.addView(choice, new LayoutParams(dp(48), dp(48)));
            choice.setOnCheckedChangeListener((button, checked) -> {
                if (commandDispatched) return;
                if (checked) selected.add(index); else selected.remove(index);
                updateConfirm.run();
            });
        }
        LinearLayout labels = new LinearLayout(getContext());
        labels.setOrientation(VERTICAL);
        labels.addView(text(host.description(index), 14, false), new LayoutParams(-1, -2));
        if (!pending || presentation.retryIndexes.contains(index) || presentation.historical || presentation.busy) {
            TextView status = text(presentation.rowStatus(index), 12, false);
            status.setTextColor(UiKit.secondary(getContext()));
            labels.addView(status, new LayoutParams(-1, -2));
        }
        row.addView(labels, new LayoutParams(0, -2, 1));
        if (selecting && expanded && pending) {
            ImageButton more = icon("此项更多操作");
            mutationControls.add(more);
            row.addView(more, new LayoutParams(dp(48), dp(48)));
            more.setOnClickListener(v -> {
                if (commandDispatched) return;
                PopupMenu menu = new PopupMenu(getContext(), more);
                menu.getMenu().add(0, 1, 0, presentation.retryIndexes.contains(index) ? "重试此项" : "确认此项");
                menu.getMenu().add(0, 2, 1, "取消此项");
                menu.setOnMenuItemClickListener(item -> {
                    dispatch(() -> {
                        if (item.getItemId() == 1) host.confirm(Collections.singletonList(index));
                        else host.cancel(Collections.singletonList(index));
                    });
                    return true;
                });
                menu.show();
            });
        }
        addView(row, new LayoutParams(-1, -2));
    }

    private void addOutcome() {
        if (!presentation.outcomeText.isEmpty() || presentation.busy || presentation.historical) {
            addDivider();
            TextView status = text(presentation.status, 14, true);
            status.setPadding(0, dp(12), 0, dp(4));
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            status.setTextColor(outcomeColor());
            addView(status, new LayoutParams(-1, -2));
            if (!presentation.outcomeText.isEmpty() && !presentation.outcomeText.equals(presentation.status)) {
                addView(text(presentation.outcomeText, 14, false), new LayoutParams(-1, -2));
            }
        }
        if (!presentation.historical && (presentation.undoAvailable || presentation.viewAvailable)) {
            if (presentation.undoAvailable) {
                Button undo = quietCommand("撤销", R.drawable.ic_task_undo, true);
                mutationControls.add(undo);
                undo.setOnClickListener(v -> { if (undo.isEnabled()) dispatch(host::undo); });
                addView(undo, new LayoutParams(-1, -2));
            } else {
                TextView reason = text(presentation.undoUnavailableReason, 12, false);
                reason.setTextColor(UiKit.secondary(getContext()));
                reason.setPadding(0, dp(8), 0, dp(4));
                addView(reason, new LayoutParams(-1, -2));
            }
        }
        if (presentation.viewAvailable) {
            Button inspect = quietCommand(presentation.viewLabel(), presentation.focusPrepared
                    ? R.drawable.ic_nav_focus : R.drawable.ic_nav_schedule, false);
            inspect.setEnabled(!presentation.busy);
            inspect.setOnClickListener(v -> {
                if (inspect.isEnabled()) host.view(presentation.focusPrepared);
            });
            addView(inspect, new LayoutParams(-1, -2));
        }
    }

    private int outcomeColor() {
        if (presentation.outcome == ProposalPresentation.Outcome.FAILED) {
            android.util.TypedValue color = new android.util.TypedValue();
            getContext().getTheme().resolveAttribute(androidx.appcompat.R.attr.colorError, color, true);
            return color.resourceId == 0 ? color.data : getContext().getColor(color.resourceId);
        }
        return presentation.outcome == ProposalPresentation.Outcome.SUCCEEDED
                ? UiKit.accent(getContext()) : UiKit.text(getContext());
    }

    private void dispatch(Runnable callback) {
        if (commandDispatched || presentation.historical || presentation.busy) return;
        commandDispatched = true;
        for (View control : mutationControls) control.setEnabled(false);
        try {
            callback.run();
        } catch (RuntimeException failure) {
            commandDispatched = false;
            host.changed();
            throw failure;
        }
    }

    private void addDivider() {
        View divider = new View(getContext());
        divider.setBackgroundColor(UiKit.border(getContext()));
        addView(divider, new LayoutParams(-1, dp(1)));
    }

    private ImageButton icon(String label) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(R.drawable.ic_more);
        button.setImageTintList(ColorStateList.valueOf(UiKit.secondary(getContext())));
        button.setBackgroundColor(Color.TRANSPARENT);
        UiKit.tooltip(button, label);
        MotionSpec.press(button);
        return button;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setLetterSpacing(0f);
        view.setSingleLine(false);
        view.setTextColor(UiKit.text(getContext()));
        view.setLineSpacing(dp(3), 1.05f);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private TextView action(String label) {
        TextView view = text(label, 14, false);
        view.setTextColor(UiKit.accent(getContext()));
        view.setMinHeight(dp(48));
        view.setPadding(0, dp(8), 0, dp(8));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setFocusable(true);
        MotionSpec.press(view);
        return view;
    }

    private Button command(String label, int icon) {
        MaterialButton button = new MaterialButton(getContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setLetterSpacing(0f);
        button.setSingleLine(false);
        button.setMaxLines(Integer.MAX_VALUE);
        button.setEllipsize(null);
        button.setCornerRadius(dp(UiKit.RADIUS_CONTAINER));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        if (icon != 0) button.setIconResource(icon);
        MotionSpec.press(button);
        return button;
    }

    private Button quietCommand(String label, int icon, boolean outlined) {
        MaterialButton button = (MaterialButton) command(label, icon);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        button.setTextColor(UiKit.secondary(getContext()));
        button.setIconTint(ColorStateList.valueOf(UiKit.secondary(getContext())));
        button.setStrokeWidth(outlined ? dp(1) : 0);
        button.setStrokeColor(ColorStateList.valueOf(UiKit.border(getContext())));
        return button;
    }

    private int dp(int value) { return UiKit.dp(getContext(), value); }
}
