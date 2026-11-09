package com.hawson.timeassistant.design;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.TooltipCompat;
import com.hawson.timeassistant.design.R;

/** Shared presentation tokens for XML pages and dynamically created views. */
public final class UiKit {
    public interface MetricsOwner { }
    private UiKit() { }
    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
    /** Corner radius for cards, groups, inputs and buttons. */
    public static final int RADIUS_CONTAINER = 12;
    /** Corner radius for segmented controls and chips (fully round). */
    public static final int RADIUS_PILL = 999;
    /** Corner radius for small tags and status labels. */
    public static final int RADIUS_TAG = 6;
    public static int color(Context context, int resource) { return context.getColor(resource); }
    public static int text(Context context) { return color(context, R.color.ui_text); }
    public static int secondary(Context context) { return color(context, R.color.ui_secondary); }
    public static int surface(Context context) { return color(context, R.color.ui_surface); }
    public static int border(Context context) { return color(context, R.color.ui_border); }
    public static int danger(Context context) { return color(context, R.color.ui_danger); }
    public static int warning(Context context) { return color(context, R.color.ui_warning); }
    public static int accent(Context context) {
        android.util.TypedValue value = new android.util.TypedValue();
        context.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true);
        return value.resourceId == 0 ? value.data : context.getColor(value.resourceId);
    }
    public static int onAccent(Context context) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (!context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnPrimary, value, true)) {
            return android.graphics.Color.WHITE;
        }
        return value.resourceId == 0 ? value.data : context.getColor(value.resourceId);
    }
    public static GradientDrawable background(Context context, int color, int stroke) {
        return background(context, color, stroke, RADIUS_CONTAINER);
    }
    public static GradientDrawable background(Context context, int color, int stroke, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radiusDp));
        if (stroke != 0) shape.setStroke(dp(context, 1), stroke);
        return shape;
    }
    public static void input(EditText field) {
        Context context = field.getContext();
        field.setBackground(inputStates(context));
        field.setTextColor(text(context));
        field.setHintTextColor(secondary(context));
        field.setTextSize(16);
        field.setMinimumHeight(dp(context, 48));
        field.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
    }

    private static StateListDrawable inputStates(Context context) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},
                background(context, surface(context), accent(context), RADIUS_CONTAINER));
        states.addState(android.util.StateSet.WILD_CARD,
                background(context, surface(context), border(context), RADIUS_CONTAINER));
        return states;
    }

    public static TextView title(Context context, String value) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(20);
        text.setTextColor(text(context));
        text.setTypeface(null, Typeface.BOLD);
        text.setPadding(0, dp(context, 20), 0, dp(context, 12));
        return text;
    }
    public static View row(Context context, String title, String value, int icon, Runnable action) {
        return row(context, title, value, 0, icon, action);
    }

    /** Navigation row; {@code valueColor} 0 keeps the secondary subtitle color. */
    public static View row(Context context, String title, String value, int valueColor, int icon,
                           Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 16), dp(context, 12), dp(context, 12), dp(context, 12));
        row.setMinimumHeight(dp(context, 56));
        android.util.TypedValue feedback = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, feedback, true);
        row.setBackgroundResource(feedback.resourceId);
        if (icon != 0) {
            android.widget.ImageView image = new android.widget.ImageView(context);
            image.setImageResource(icon);
            image.setImageTintList(ColorStateList.valueOf(accent(context)));
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
            params.setMarginEnd(dp(context, 12));
            row.addView(image, params);
        }
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        name.setText(title); name.setTextSize(16); name.setTextColor(text(context));
        labels.addView(name);
        if (value != null && !value.isEmpty()) {
            TextView subtitle = new TextView(context);
            subtitle.setText(value); subtitle.setTextSize(13);
            subtitle.setTextColor(valueColor == 0 ? secondary(context) : valueColor);
            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-2, -2);
            subtitleParams.topMargin = dp(context, 2);
            labels.addView(subtitle, subtitleParams);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.ImageView chevron = new android.widget.ImageView(context);
        chevron.setImageResource(R.drawable.ui_ic_chevron_right);
        chevron.setImageTintList(ColorStateList.valueOf(secondary(context)));
        chevron.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
        chevronParams.setMarginStart(dp(context, 8));
        row.addView(chevron, chevronParams);
        row.setFocusable(true);
        androidx.core.view.ViewCompat.setScreenReaderFocusable(row, true);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.setContentDescription(title + (value == null || value.isEmpty() ? "" : "，" + value));
        row.setOnClickListener(view -> action.run());
        return row;
    }

    /** Small bold heading above a group; keeps its own metrics when the page is polished. */
    public static TextView sectionHeader(Context context, String value) {
        TextView header = new FixedText(context);
        header.setText(value);
        header.setTextSize(13);
        header.setTypeface(null, Typeface.BOLD);
        header.setTextColor(secondary(context));
        header.setPadding(dp(context, 4), dp(context, 24), dp(context, 4), dp(context, 8));
        androidx.core.view.ViewCompat.setAccessibilityHeading(header, true);
        header.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return header;
    }

    /** Framed group container with the shared container radius and 16dp padding. */
    public static LinearLayout group(Context context) {
        LinearLayout group = groupList(context);
        int padding = dp(context, 16);
        group.setPadding(padding, padding, padding, padding);
        return group;
    }

    /** Same frame without padding, for edge-to-edge rows separated by {@link #divider}. */
    public static LinearLayout groupList(Context context) {
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(background(context, surface(context), border(context)));
        group.setClipToOutline(true);
        group.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return group;
    }

    public static View divider(Context context, int insetStartDp) {
        View divider = new View(context);
        divider.setBackgroundColor(border(context));
        divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(context, 1));
        params.setMarginStart(dp(context, insetStartDp));
        divider.setLayoutParams(params);
        return divider;
    }

    /** Adds rows to an edge-to-edge group, inserting inset dividers between them. */
    public static void addRows(LinearLayout group, int insetStartDp, View... rows) {
        for (View row : rows) {
            if (group.getChildCount() > 0) group.addView(divider(group.getContext(), insetStartDp));
            group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    public static android.widget.Button primaryButton(Context context, String value) {
        return button(context, value, accent(context), onAccent(context), 0);
    }

    public static android.widget.Button secondaryButton(Context context, String value) {
        return button(context, value, surface(context), accent(context), border(context));
    }

    public static android.widget.Button textButton(Context context, String value) {
        return button(context, value, android.graphics.Color.TRANSPARENT, accent(context), 0);
    }

    private static android.widget.Button button(Context context, String value, int fill, int foreground,
                                                int stroke) {
        android.widget.Button button = new KitButton(context);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(null, Typeface.BOLD);
        button.setTextColor(foreground);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        button.setStateListAnimator(null);
        button.setLetterSpacing(0f);
        android.graphics.drawable.GradientDrawable mask = background(context, android.graphics.Color.WHITE, 0);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(fill == accent(context) ? 0x33FFFFFF : 0x1F000000 | (accent(context) & 0xFFFFFF)),
                background(context, fill, stroke), mask));
        button.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    /** Destructive command row: red icon and label, no chevron. */
    public static View dangerRow(Context context, String title, int icon, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        row.setMinimumHeight(dp(context, 52));
        android.util.TypedValue feedback = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, feedback, true);
        row.setBackgroundResource(feedback.resourceId);
        android.widget.ImageView image = new android.widget.ImageView(context);
        image.setImageResource(icon == 0 ? R.drawable.ui_ic_trash : icon);
        image.setImageTintList(ColorStateList.valueOf(danger(context)));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
        iconParams.setMarginEnd(dp(context, 12));
        row.addView(image, iconParams);
        TextView label = new TextView(context);
        label.setText(title);
        label.setTextSize(15);
        label.setTypeface(null, Typeface.BOLD);
        label.setTextColor(danger(context));
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        row.setFocusable(true);
        row.setClickable(true);
        androidx.core.view.ViewCompat.setScreenReaderFocusable(row, true);
        row.setContentDescription(title);
        androidx.core.view.ViewCompat.setAccessibilityDelegate(row, new androidx.core.view.AccessibilityDelegateCompat() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
        row.setOnClickListener(view -> action.run());
        return row;
    }

    /** Status text preceded by a colored dot. */
    public static TextView statusLine(Context context, String value, int color) {
        TextView status = new TextView(context);
        setStatus(status, value, color);
        status.setTextSize(13);
        status.setLineSpacing(dp(context, 2), 1f);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setCompoundDrawablePadding(dp(context, 8));
        status.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return status;
    }

    public static void setStatus(TextView status, String value, int color) {
        Context context = status.getContext();
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        dot.setSize(dp(context, 8), dp(context, 8));
        status.setText(value);
        status.setTextColor(text(context));
        status.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
    }

    /** Compact pill label, for example "当前" or "实验功能". */
    public static TextView tag(Context context, String value, int color) {
        TextView tag = new FixedText(context);
        tag.setText(value);
        tag.setTextSize(11);
        tag.setTypeface(null, Typeface.BOLD);
        tag.setTextColor(color);
        tag.setPadding(dp(context, 8), dp(context, 2), dp(context, 8), dp(context, 2));
        tag.setBackground(background(context, (color & 0x00FFFFFF) | 0x1F000000, 0, RADIUS_TAG));
        tag.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return tag;
    }

    /** Text with fixed metrics; {@link #polish} skips it. */
    private static final class FixedText extends androidx.appcompat.widget.AppCompatTextView implements MetricsOwner {
        FixedText(Context context) { super(context); }
    }

    /** Dims while disabled so tiered buttons keep a readable state. */
    private static final class KitButton extends androidx.appcompat.widget.AppCompatButton {
        KitButton(Context context) { super(context); }
        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.4f);
        }
    }
    public static void tooltip(View view, String description) {
        view.setContentDescription(description);
        TooltipCompat.setTooltipText(view, description);
    }

    /** A single unframed empty state; it does not repeat contextual tutorial instructions. */
    public static View emptyState(Context context, int icon, String title, String action,
                                  Runnable onAction) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(context, 16), dp(context, 32), dp(context, 16), dp(context, 32));
        if (icon != 0) {
            android.widget.ImageView image = new android.widget.ImageView(context);
            image.setImageResource(icon);
            image.setImageTintList(ColorStateList.valueOf(secondary(context)));
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            content.addView(image, new LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)));
        }
        TextView label = new FixedText(context);
        label.setText(title); label.setTextSize(16); label.setTextColor(secondary(context));
        label.setGravity(Gravity.CENTER); label.setLineSpacing(dp(context, 4), 1);
        LinearLayout.LayoutParams name = new LinearLayout.LayoutParams(-1, -2);
        name.topMargin = dp(context, 12); content.addView(label, name);
        if (onAction != null && action != null) {
            android.widget.Button button = textButton(context, action);
            button.setOnClickListener(v -> onAction.run());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.topMargin = dp(context, 8); content.addView(button, params);
        }
        return content;
    }

    /** Numeric field keeps its unit visible without constraining the font-scaled row height. */
    public static View numericRow(Context context, String title, EditText input, String unit) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(context, 56));
        TextView label = new FixedText(context);
        label.setText(title); label.setTextSize(16); label.setTextColor(text(context));
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        input.setGravity(Gravity.CENTER); input.setSingleLine(true);
        input.setContentDescription(title + "，" + unit);
        int numberWidth = Math.max(dp(context, 80), Math.round(input.getPaint().measureText("1440"))
                + input.getCompoundPaddingLeft() + input.getCompoundPaddingRight() + dp(context, 8));
        LinearLayout.LayoutParams number = new LinearLayout.LayoutParams(numberWidth, -2);
        number.setMarginStart(dp(context, 8)); row.addView(input, number);
        TextView suffix = new FixedText(context);
        suffix.setText(unit); suffix.setTextSize(13); suffix.setTextColor(secondary(context));
        LinearLayout.LayoutParams tail = new LinearLayout.LayoutParams(-2, -2);
        tail.setMarginStart(dp(context, 8)); row.addView(suffix, tail);
        return row;
    }

    public static View sheetHeading(Context context, String value, Runnable dismiss) {
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = title(context, value);
        heading.setPadding(0, dp(context, 12), dp(context, 8), dp(context, 12));
        androidx.core.view.ViewCompat.setAccessibilityHeading(heading, true);
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        androidx.appcompat.widget.AppCompatImageButton close = new androidx.appcompat.widget.AppCompatImageButton(context);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setImageTintList(ColorStateList.valueOf(secondary(context)));
        close.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(border(context)), background(context, surface(context), 0, RADIUS_PILL),
                background(context, android.graphics.Color.WHITE, 0, RADIUS_PILL)));
        close.setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14));
        tooltip(close, "关闭" + value);
        close.setOnClickListener(v -> dismiss.run());
        bar.addView(close, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        return bar;
    }

    public static void polish(View view) {
        if (view instanceof MetricsOwner) return;
        // Segmented controls own their XML tracks, label metrics and indicator geometry.
        if (view instanceof SlidingSegmentedControl) return;
        Context context = view.getContext();
        if (view instanceof LinearLayout) {
            android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null && params.height > 0 && params.height <= dp(context, 80)) {
                view.setMinimumHeight(params.height);
                params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            }
        }
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setLetterSpacing(0f);
            if (!(text instanceof EditText)) {
                float size = text.getTextSize() / context.getResources().getDisplayMetrics().scaledDensity;
                text.setTextSize(size >= 20 ? 20 : size >= 16 ? 16 : 13);
                text.setLineSpacing(dp(context, 3), 1f);
            }
            if (view.isClickable()) {
                text.setMinHeight(dp(context, 48));
                android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
                if (params != null && params.height > 0 && params.height < dp(context, 80)) {
                    params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                }
                text.setPadding(text.getPaddingLeft(), Math.max(dp(context, 8), text.getPaddingTop()),
                        text.getPaddingRight(), Math.max(dp(context, 8), text.getPaddingBottom()));
            }
            if (text instanceof android.widget.Button) {
                text.setAllCaps(false);
                text.setStateListAnimator(null);
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) polish(group.getChildAt(index));
        }
    }
}
