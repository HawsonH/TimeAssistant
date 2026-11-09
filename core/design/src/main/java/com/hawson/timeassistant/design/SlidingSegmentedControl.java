package com.hawson.timeassistant.design;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** Equal-width native segments sharing the same moving indicator as bottom navigation. */
public final class SlidingSegmentedControl extends SlidingIndicatorLayout {
    public interface OnSelectionChangedListener { void onSelectionChanged(int index); }
    private int selectedIndex;
    private OnSelectionChangedListener listener;
    public SlidingSegmentedControl(Context context) { this(context, null); }
    public SlidingSegmentedControl(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        int inset = UiKit.dp(context, ShapeTokens.TRACK_INSET);
        if (attrs == null) setPadding(inset, inset, inset, inset);
        setMinimumHeight(UiKit.dp(context, 48));
        if (getBackground() == null) setBackground(UiKit.background(context, UiKit.border(context), 0, ShapeTokens.TRACK_RADIUS));
    }
    public void setSegments(CharSequence... labels) {
        stopMotion(); removeAllViews();
        selectedIndex = labels.length == 0 ? -1 : 0;
        for (CharSequence value : labels) {
            TextView label = new TextView(getContext());
            label.setText(value); label.setTextSize(14); label.setLetterSpacing(0);
            label.setGravity(Gravity.CENTER); label.setMinHeight(UiKit.dp(getContext(), 48));
            label.setPadding(UiKit.dp(getContext(), 8), 0, UiKit.dp(getContext(), 8), 0);
            addView(label, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        }
        wireSegments(); requestLayout();
    }
    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        selectedIndex = getChildCount() == 0 ? -1 : 0;
        wireSegments();
    }
    private void wireSegments() {
        for (int i = 0; i < getChildCount(); i++) {
            final int index = i;
            View label = getChildAt(i);
            if (!(label instanceof TextView)) throw new IllegalArgumentException("Segments must be TextViews");
            label.setFocusable(true);
            label.setOnClickListener(v -> { if (isEnabled()) setSelectedIndex(index, true); });
            label.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.RadioButton"); info.setCheckable(true); info.setChecked(index == selectedIndex);
                }
            });
        }
        updateLabels();
        if (selectedIndex >= 0) selectIndicator(selectedIndex);
    }
    public void setOnSelectionChangedListener(OnSelectionChangedListener value) { listener = value; }
    public int getSelectedIndex() { return selectedIndex; }
    public void setSelectedIndex(int index, boolean notify) {
        if (index < 0 || index >= getChildCount()) throw new IllegalArgumentException("Invalid segment");
        if (index == selectedIndex) return;
        selectedIndex = index; updateLabels(); selectIndicator(index);
        if (notify && listener != null) listener.onSelectionChanged(index);
    }
    private void updateLabels() {
        for (int i = 0; i < getChildCount(); i++) {
            TextView label = (TextView) getChildAt(i);
            label.setSelected(i == selectedIndex);
            label.setTextColor(i == selectedIndex ? UiKit.text(getContext()) : UiKit.secondary(getContext()));
        }
    }
}
