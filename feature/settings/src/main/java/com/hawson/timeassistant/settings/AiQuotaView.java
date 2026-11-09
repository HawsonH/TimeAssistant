package com.hawson.timeassistant.settings;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.hawson.timeassistant.connections.AiQuotaSnapshot;
import com.hawson.timeassistant.design.UiKit;
import com.hawson.timeassistant.settingsfeature.R;
import java.text.NumberFormat;

/** Read-only quota presentation; no credentials, JSON, providers or network calls. */
public final class AiQuotaView extends LinearLayout {
    private final TextView status;
    private final LinearLayout content;
    public AiQuotaView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        status = label(this, "", false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content = new LinearLayout(context); content.setOrientation(VERTICAL);
        addView(content, new LayoutParams(-1, -2));
    }
    public void setStatus(String text) { status.setText(text); }
    public void render(AiQuotaSnapshot data, long now) {
        content.removeAllViews();
        if (data == null) {
            status.setText(R.string.quota_not_read);
            return;
        }
        status.setText(data.stale(now) ? R.string.quota_stale : R.string.quota_snapshot);
        if (!data.plan.isEmpty()) label(content, getContext().getString(R.string.quota_plan, data.plan), true);
        if (data.observedAt > 0) label(content, getContext().getString(R.string.quota_observed, date(data.observedAt)), false);
        if (data.limits.isEmpty()) label(content, getContext().getString(R.string.quota_windows_unknown), false);
        for (AiQuotaSnapshot.Limit limit : data.limits) {
            label(content, limit.name, true);
            if (Boolean.FALSE.equals(limit.allowed) || Boolean.TRUE.equals(limit.reached))
                label(content, getContext().getString(R.string.quota_limited), false);
            if (limit.windows.isEmpty()) label(content, getContext().getString(R.string.quota_windows_unknown), false);
            for (AiQuotaSnapshot.Window window : limit.windows) addWindow(window, now);
        }
        if (data.credits != null) label(content, getContext().getString(R.string.quota_credits, credits(data.credits)), false);
        if (data.resetCredits != null) label(content,
                getContext().getString(R.string.quota_reset_credits, data.resetCredits), false);
        label(content, getContext().getString(R.string.quota_scope_notice), false);
    }
    private void addWindow(AiQuotaSnapshot.Window window, long now) {
        String title = windowTitle(window);
        label(content, title, true);
        if (window.usedPercent == null) label(content, getContext().getString(R.string.quota_percent_unknown), false);
        else {
            String usage = getContext().getString(R.string.quota_percent,
                    number(window.remainingPercent()), number(window.usedPercent));
            label(content, usage, false);
            LinearProgressIndicator bar = new LinearProgressIndicator(getContext());
            bar.setMax(1000); bar.setIndicatorColor(UiKit.accent(getContext()));
            bar.setTrackColor(UiKit.border(getContext()));
            bar.setProgressCompat((int) Math.round(window.remainingPercent() * 10), false);
            bar.setContentDescription(title + "，" + usage);
            LayoutParams params = new LayoutParams(-1, UiKit.dp(getContext(), 8));
            params.bottomMargin = UiKit.dp(getContext(), 8); content.addView(bar, params);
        }
        if (window.resetAt == null) label(content, getContext().getString(R.string.quota_reset_unknown), false);
        else label(content, getContext().getString(window.resetPassed(now) ? R.string.quota_reset_passed : R.string.quota_reset_at,
                date(window.resetAt)), false);
    }
    private String windowTitle(AiQuotaSnapshot.Window window) {
        if (window.seconds == null) return getContext().getString("primary_window".equals(window.slot)
                ? R.string.quota_primary : R.string.quota_secondary);
        if (window.seconds == 18000) return getContext().getString(R.string.quota_five_hour);
        if (window.seconds == 604800) return getContext().getString(R.string.quota_week);
        return getContext().getString(R.string.quota_duration, number(window.seconds / 3600.0));
    }
    private static String number(double value) {
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setMaximumFractionDigits(1); format.setGroupingUsed(false); return format.format(value);
    }
    private static String date(long value) { return android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", value).toString(); }
    private String credits(AiQuotaSnapshot.Credits value) {
        if (Boolean.TRUE.equals(value.unlimited)) return getContext().getString(R.string.quota_unlimited);
        if (value.balance != null) return value.balance;
        if (Boolean.FALSE.equals(value.available)) return getContext().getString(R.string.quota_credit_unavailable);
        return getContext().getString(Boolean.TRUE.equals(value.available) ? R.string.quota_credit_available : R.string.quota_unknown);
    }
    private static TextView label(LinearLayout parent, String text, boolean heading) {
        Context context = parent.getContext();
        TextView view = new TextView(context); view.setText(text);
        view.setTextSize(heading ? 16 : 14); view.setTextColor(heading ? UiKit.text(context) : UiKit.secondary(context));
        view.setPadding(0, UiKit.dp(context, heading ? 12 : 4), 0, UiKit.dp(context, 4));
        androidx.core.view.ViewCompat.setAccessibilityHeading(view, heading);
        parent.addView(view, new LayoutParams(-1, -2)); return view;
    }
}
