package com.hawson.timeassistant.quota.sub2api;

import com.hawson.timeassistant.connections.AiQuotaSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Whitelisted projection of sub2api's read-only OpenAI quota response, not its admin payload. */
public final class Sub2ApiQuotaCodec {
    private Sub2ApiQuotaCodec() { }
    public static AiQuotaSnapshot decode(JSONObject data, long receivedAt) {
        long received = receivedAt > 0 ? receivedAt : positive(data.opt("local_fetched_at"), 1);
        long observed = positive(data.opt("fetched_at"), 1000);
        List<AiQuotaSnapshot.Limit> limits = new ArrayList<>();
        addLimit(limits, "账号额度", data.optJSONObject("rate_limit"), observed);
        JSONArray extra = data.optJSONArray("additional_rate_limits");
        if (extra != null) for (int i = 0; i < Math.min(extra.length(), 32); i++) {
            JSONObject item = extra.optJSONObject(i);
            if (item != null) addLimit(limits, text(item, "limit_name", text(item, "metered_feature", "其他额度")),
                    item.optJSONObject("rate_limit"), observed);
        }
        JSONObject rawCredits = data.optJSONObject("credits");
        AiQuotaSnapshot.Credits credits = rawCredits == null ? null : new AiQuotaSnapshot.Credits(
                bool(rawCredits, "has_credits"), bool(rawCredits, "unlimited"), balance(rawCredits.opt("balance")));
        JSONObject reset = data.optJSONObject("rate_limit_reset_credits");
        Long count = reset == null ? null : integer(reset.opt("available_count"));
        return new AiQuotaSnapshot(text(data, "account_id", ""), text(data, "email", ""), text(data, "plan_type", ""),
                observed, received, limits, credits, count == null || count < 0 || count > Integer.MAX_VALUE ? null : count.intValue());
    }
    private static void addLimit(List<AiQuotaSnapshot.Limit> limits, String name, JSONObject rate, long observed) {
        if (rate == null) return;
        List<AiQuotaSnapshot.Window> windows = new ArrayList<>();
        for (String key : new String[]{"primary_window", "secondary_window"}) {
            JSONObject window = rate.optJSONObject(key);
            if (window == null) continue;
            Long seconds = integer(window.opt("limit_window_seconds"));
            if (seconds != null && seconds <= 0) seconds = null;
            Double used = percent(window.opt("used_percent"));
            long absolute = positive(window.opt("reset_at"), 1000);
            Long reset = absolute == 0 ? null : absolute;
            Long after = integer(window.opt("reset_after_seconds"));
            if (reset == null && observed > 0 && after != null && after >= 0 && after <= (Long.MAX_VALUE - observed) / 1000)
                reset = observed + after * 1000;
            windows.add(new AiQuotaSnapshot.Window(key, seconds, used, reset));
        }
        // Duration, not primary/secondary position, determines the short/week order.
        windows.sort(Comparator.comparingLong(w -> w.seconds == null ? Long.MAX_VALUE : w.seconds));
        limits.add(new AiQuotaSnapshot.Limit(name, bool(rate, "allowed"), bool(rate, "limit_reached"), windows));
    }
    public static JSONObject encode(AiQuotaSnapshot value) throws org.json.JSONException {
        JSONObject result = new JSONObject();
        result.put("account_id", value.accountId).put("email", value.email).put("plan_type", value.plan);
        result.put("fetched_at", value.observedAt / 1000).put("local_fetched_at", value.receivedAt);
        JSONArray extra = new JSONArray();
        for (int i = 0; i < value.limits.size(); i++) {
            AiQuotaSnapshot.Limit limit = value.limits.get(i);
            JSONObject rate = new JSONObject().put("allowed", limit.allowed).put("limit_reached", limit.reached);
            for (AiQuotaSnapshot.Window window : limit.windows) {
                JSONObject item = new JSONObject().put("used_percent", window.usedPercent)
                        .put("limit_window_seconds", window.seconds);
                if (window.resetAt != null) item.put("reset_at", window.resetAt / 1000);
                rate.put(window.slot, item);
            }
            if (i == 0 && "账号额度".equals(limit.name)) result.put("rate_limit", rate);
            else extra.put(new JSONObject().put("limit_name", limit.name).put("rate_limit", rate));
        }
        result.put("additional_rate_limits", extra);
        if (value.credits != null) result.put("credits", new JSONObject().put("has_credits", value.credits.available)
                .put("unlimited", value.credits.unlimited).put("balance", value.credits.balance));
        if (value.resetCredits != null) result.put("rate_limit_reset_credits", new JSONObject().put("available_count", value.resetCredits));
        return result;
    }
    private static String text(JSONObject item, String key, String fallback) {
        Object raw = item.opt(key);
        if (!(raw instanceof String)) return fallback;
        String value = ((String) raw).replaceAll("[\\p{Cntrl}]", "").trim();
        return value.isEmpty() ? fallback : value.substring(0, Math.min(value.length(), 200));
    }
    private static Boolean bool(JSONObject data, String key) { return data.opt(key) instanceof Boolean ? (Boolean) data.opt(key) : null; }
    private static Long integer(Object value) {
        if (!(value instanceof Number)) return null;
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) { return null; }
    }
    private static long positive(Object value, long multiplier) {
        Long number = integer(value);
        return number == null || number <= 0 || number > Long.MAX_VALUE / multiplier ? 0 : number * multiplier;
    }
    private static Double percent(Object raw) {
        if (!(raw instanceof Number)) return null;
        double value = ((Number) raw).doubleValue();
        return Double.isNaN(value) || Double.isInfinite(value) || value < 0 || value > 10000 ? null : value;
    }
    private static String balance(Object raw) {
        if (!(raw instanceof String) || ((String) raw).length() > 80) return null;
        String value = ((String) raw).trim();
        if (!value.matches("[0-9]+(?:\\.[0-9]+)?")) return null;
        return value;
    }
}
