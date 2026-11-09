package com.hawson.timeassistant.ui;

import android.content.Context;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hawson.timeassistant.AiServices;
import com.hawson.timeassistant.ai.AiAuxiliaryServices;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.design.UiKit;
import com.hawson.timeassistant.settings.AiQuotaView;
import com.hawson.timeassistant.quota.sub2api.Sub2ApiQuotaCodec;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

final class AiToolsController {
    final AiAuxiliaryServices services;
    private final android.app.Activity activity;
    private final AiServices ai;
    private final Runnable connections;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<BottomSheetDialog> sheets = new HashSet<>();
    private final Set<AiCancellation> requests = new HashSet<>();
    AiToolsController(android.app.Activity activity, AiServices ai, Runnable connections) {
        this.activity = activity; this.ai = ai; this.connections = connections;
        services = new AiAuxiliaryServices(activity, ai);
    }
    private int dp(int v) { return Math.round(v * activity.getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView v = new TextView(activity); v.setText(value); v.setTextSize(size); v.setTextColor(UiKit.text(activity));
        v.setPadding(0, dp(10), 0, dp(10)); parent.addView(v); return v;
    }
    private void row(LinearLayout parent, String title, String subtitle, Runnable action) {
        parent.addView(UiKit.row(activity, title, subtitle, 0, action));
    }
    private BottomSheetDialog sheet(String title, LinearLayout body) {
        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        com.hawson.timeassistant.design.MotionSpec.configureDialog(sheet);
        LinearLayout root = column(); root.setPadding(dp(16), dp(12), dp(16), dp(24));
        root.setBackground(UiKit.background(activity, UiKit.surface(activity), 0)); root.setClipToOutline(true);
        root.addView(UiKit.sheetHeading(activity, title, sheet::dismiss));
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(false); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        sheet.setContentView(root); sheet.getBehavior().setShouldRemoveExpandedCorners(false);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        sheet.show();
        sheets.add(sheet); sheet.setOnDismissListener(ignored -> sheets.remove(sheet)); return sheet;
    }
    private EditText field(LinearLayout body, String title, String value, boolean password) {
        com.google.android.material.textfield.TextInputLayout box = new com.google.android.material.textfield.TextInputLayout(activity);
        box.setHint(title); box.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        box.setBoxCornerRadii(dp(20), dp(20), dp(20), dp(20));
        box.setBoxBackgroundColor(UiKit.surface(activity));
        box.setBoxStrokeColorStateList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{UiKit.accent(activity), UiKit.border(activity)}));
        box.setDefaultHintTextColor(android.content.res.ColorStateList.valueOf(UiKit.secondary(activity)));
        com.google.android.material.textfield.TextInputEditText input = new com.google.android.material.textfield.TextInputEditText(box.getContext());
        input.setText(value); input.setMinHeight(dp(56)); input.setTextSize(14);
        input.setTextColor(UiKit.text(activity)); input.setBackground(null);
        input.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT);
        if (password) {
            input.setSaveEnabled(false);
            if (Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            box.setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        }
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12); body.addView(box, lp);
        return input;
    }
    private Button button(LinearLayout parent, String value, Runnable action) {
        com.google.android.material.button.MaterialButton b = new com.google.android.material.button.MaterialButton(activity);
        b.setText(value); b.setMinHeight(dp(48)); b.setCornerRadius(dp(UiKit.RADIUS_CONTAINER)); b.setAllCaps(false);
        parent.addView(b, new LinearLayout.LayoutParams(-1, -2)); b.setOnClickListener(v -> action.run()); return b;
    }
    void showAccountsAndUsage() {
        LinearLayout body = column();
        row(body, "本应用统计", "全部连接与后台调用", () -> showUsage(null));
        for (AiAccountPort.Account account : ai.accounts.accounts())
            row(body, account.label.split(" · ")[0], account.authorized ? "已授权" : "需要授权", () -> showAccount(account));
        for (AiProfile profile : ai.profiles.list()) if (profile.kind != AiProfile.Kind.CHATGPT)
            row(body, profile.name, profile.model, () -> showUsage(profile.credentialRef));
        row(body, "连接与账号设置", "", this::openConnections);
        row(body, "语音输入", speechLabel(), this::showSpeechSettings);
        sheet("账号与用量", body);
    }
    private void showAccount(AiAccountPort.Account account) {
        LinearLayout body = column();
        text(body, "官方计划额度", 17);
        text(body, activity.getString(com.hawson.timeassistant.settingsfeature.R.string.quota_official_unavailable), 14);
        row(body, "查看官方用量", "计划用量与本应用限额", () -> {
            try { activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://chatgpt.com/settings/usage"))); }
            catch (android.content.ActivityNotFoundException missing) {
                Toast.makeText(activity, "未找到浏览器，请先启用系统浏览器", Toast.LENGTH_SHORT).show();
            }
        });
        text(body, "可选来源快照 · sub2api", 17);
        JSONObject cached = services.cachedQuota(account.reference);
        AiQuotaView value = new AiQuotaView(activity); body.addView(value);
        value.render(cached == null ? null : Sub2ApiQuotaCodec.decode(cached, 0), System.currentTimeMillis());
        String prefix = "quota." + account.reference + ".";
        AiCancellation cancel = new AiCancellation();
        boolean[] refreshing = {false};
        Runnable refresh = () -> {
            if (services.get(prefix + "url", "").isEmpty()) { showQuotaSettings(account); return; }
            if (refreshing[0]) return;
            refreshing[0] = true;
            String revision = services.get(prefix + "revision", "");
            JSONObject latest = services.cachedQuota(account.reference);
            value.render(latest == null ? null : Sub2ApiQuotaCodec.decode(latest, 0), System.currentTimeMillis());
            value.setStatus("正在刷新 sub2api 快照…");
            requests.add(cancel);
            worker.execute(() -> {
                try {
                    JSONObject result = services.quota(account.reference, cancel);
                    String remote = result.optString("account_id", "") + "|" + result.optString("email", "");
                    if ("|".equals(remote)) throw new IllegalStateException("未返回远端账号标识");
                    main.post(() -> {
                        refreshing[0] = false;
                        try { cancel.check(); } catch (Exception ignored) { return; }
                        requests.remove(cancel);
                        if (!revision.equals(services.get(prefix + "revision", ""))) { value.setStatus("额度来源已更新，请重新刷新"); return; }
                        String previous = services.get(prefix + "identity", "");
                        if (!previous.equals(remote)) {
                            value.setStatus("请核对额度来源账号；尚未采用新数据");
                            new com.hawson.timeassistant.design.SoftAlertDialogBuilder(activity).setTitle("绑定此额度账号？")
                                .setMessage("本机账号：" + account.label.split(" · ")[0] + "\n远端账号：" + remote.replace('|', '\n'))
                                .setNegativeButton("取消", null).setPositiveButton("确认绑定", (d, w) -> {
                                    if (!dialogVisible(value, cancel)) return;
                                    saveQuota(account.reference, prefix, revision, remote, result, value);
                                }).show();
                        } else { saveQuota(account.reference, prefix, revision, remote, result, value); }
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        refreshing[0] = false;
                        try { cancel.check(); } catch (Exception ignored) { return; }
                        requests.remove(cancel);
                        if (!revision.equals(services.get(prefix + "revision", ""))) { value.setStatus("额度来源已更新，请重新刷新"); return; }
                        JSONObject last = services.cachedQuota(account.reference);
                        value.render(last == null ? null : Sub2ApiQuotaCodec.decode(last, 0), System.currentTimeMillis());
                        value.setStatus("刷新失败；下方仅为历史快照，不代表当前可用额度");
                    });
                }
            });
        };
        button(body, "刷新来源快照", refresh);
        row(body, "可选额度来源", services.get(prefix + "url", "").isEmpty() ? "不影响官方登录；仅使用独立 sub2api 凭据"
                : services.get(prefix + "url", ""), () -> showQuotaSettings(account));
        row(body, "本应用统计", "此账号", () -> showUsage(account.reference));
        row(body, "管理账号", "", this::openConnections);
        BottomSheetDialog dialog = sheet(account.label.split(" · ")[0], body);
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnDismissListener(ignored -> { cancel.cancel(); requests.remove(cancel); sheets.remove(dialog); });
    }
    private boolean dialogVisible(View value, AiCancellation cancel) {
        try { cancel.check(); } catch (Exception ignored) { return false; }
        return value.isAttachedToWindow() && !activity.isFinishing() && !activity.isDestroyed();
    }
    private void saveQuota(String reference, String prefix, String revision, String identity, JSONObject result, AiQuotaView value) {
        if (!revision.equals(services.get(prefix + "revision", ""))) { value.setStatus("额度来源已更新，请重新刷新"); return; }
        try {
            services.cacheQuota(reference, result);
            services.put(prefix + "identity", identity);
            value.render(Sub2ApiQuotaCodec.decode(result, 0), System.currentTimeMillis());
        } catch (Exception unavailable) { value.setStatus("额度已读取，但无法保存快照，请重试"); }
    }
    private void showUsage(String reference) {
        LinearLayout body = column();
        LinearLayout periods = new LinearLayout(activity); body.addView(periods);
        TextView summary = text(body, "", 14);
        List<Button> periodButtons = new ArrayList<>();
        int[] generation = {0};
        java.util.function.IntConsumer display = days -> {
            int version = ++generation[0];
            for (Button button : periodButtons) {
                boolean active = ((Integer) button.getTag()) == days;
                button.setSelected(active);
                button.setTextColor(active ? UiKit.accent(activity) : UiKit.secondary(activity));
                button.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                button.setBackground(UiKit.background(activity, active ? UiKit.surface(activity) : android.graphics.Color.TRANSPARENT, 0, UiKit.RADIUS_PILL));
                androidx.core.view.ViewCompat.setStateDescription(button, active ? "已选择" : "未选择");
            }
            Calendar date = Calendar.getInstance(); date.set(Calendar.HOUR_OF_DAY, 0); date.set(Calendar.MINUTE, 0); date.set(Calendar.SECOND, 0); date.set(Calendar.MILLISECOND, 0); date.add(Calendar.DATE, 1 - days);
            long since = date.getTimeInMillis(); summary.setText("正在读取…");
            worker.execute(() -> {
                String result;
                try { result = ai.usage.summary(since, reference); }
                catch (Exception unavailable) { result = "统计暂不可用，请稍后重试"; }
                String message = result;
                main.post(() -> { if (version == generation[0] && summary.isAttachedToWindow()) summary.setText(message); });
            });
        };
        for (int day : new int[]{1, 7, 30}) {
            Button b = new com.google.android.material.button.MaterialButton(activity, null, android.R.attr.borderlessButtonStyle);
            b.setText(day == 1 ? "今日" : day + " 天"); b.setMinHeight(dp(48)); periods.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
            b.setTag(day); b.setTextSize(14); b.setAllCaps(false); periodButtons.add(b);
            b.setOnClickListener(v -> display.accept(day));
        }
        periods.setPadding(dp(4), dp(4), dp(4), dp(4));
        periods.setBackground(UiKit.background(activity, UiKit.border(activity), 0, UiKit.RADIUS_PILL));
        text(body, "从此版本开始记录；Token 仅统计服务返回值。", 12);
        BottomSheetDialog sheet = sheet("本应用统计", body); sheet.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); display.accept(1);
    }
    void showQuotaSettings(AiAccountPort.Account account) {
        String prefix = "quota." + account.reference + ".";
        LinearLayout body = column();
        EditText url = field(body, "sub2api 服务地址", services.get(prefix + "url", ""), false);
        EditText id = field(body, "远端账号 ID", services.get(prefix + "id", ""), false); id.setInputType(InputType.TYPE_CLASS_NUMBER);
        Spinner auth = new Spinner(activity);
        auth.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new String[]{"Admin API Key", "管理员 Bearer Token"}));
        auth.setSelection("bearer".equals(services.get(prefix + "auth", "key")) ? 1 : 0); body.addView(auth);
        EditText key = field(body, services.hasQuotaSecret(account.reference) ? "凭据（留空保留）" : "凭据", "", true);
        TextView error = text(body, "", 14);
        BottomSheetDialog[] dialog = new BottomSheetDialog[1];
        button(body, "保存", () -> {
            try {
                String root = AiAuxiliaryServices.base(url.getText().toString());
                if (!id.getText().toString().matches("[1-9][0-9]{0,17}")) { id.setError("请输入有效账号 ID"); return; }
                allow(root, () -> {
                    try {
                        String nextAuth = auth.getSelectedItemPosition() == 1 ? "bearer" : "key";
                        boolean changed = !root.equals(services.get(prefix + "url", "")) || !nextAuth.equals(services.get(prefix + "auth", "key"));
                        if (key.length() == 0 && (changed || !services.hasQuotaSecret(account.reference))) { key.setError("请为此来源填写独立凭据"); return; }
                        services.configureQuota(account.reference, root, id.getText().toString(), nextAuth, key.getText().toString());
                        key.setText(""); dialog[0].dismiss(); showAccount(account);
                    } catch (Exception failed) { error.setText("保存失败，请检查输入"); }
                });
            } catch (Exception invalid) { url.setError("请输入有效服务地址"); }
        });
        row(body, "移除额度来源", "", () -> {
            try { services.removeQuota(account.reference); dialog[0].dismiss(); }
            catch (Exception failed) { error.setText("移除失败，请重试"); }
        });
        dialog[0] = sheet("额度来源", body); dialog[0].getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    String speechLabel() {
        String engine = services.get("speech.engine", "system");
        return "official".equals(engine) ? "GPT 转写" : "custom".equals(engine) ? "自定义 API" : "手机系统识别";
    }
    void showSpeechSettings() {
        LinearLayout body = column();
        Spinner engine = new Spinner(activity);
        engine.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new String[]{"手机系统识别", "GPT 转写", "自定义 API"}));
        String current = services.get("speech.engine", "system"); engine.setSelection("official".equals(current) ? 1 : "custom".equals(current) ? 2 : 0); body.addView(engine);
        LinearLayout api = column(); body.addView(api);
        EditText url = field(api, "服务地址（含 /v1）", services.get("speech.url", "https://api.openai.com/v1"), false);
        EditText model = field(api, "语音模型", services.get("speech.model", "gpt-transcribe"), false);
        EditText key = field(api, services.hasSecret("aux:speech") ? "API Key（留空保留）" : "API Key", "", true);
        row(api, "使用已有 API Key 连接", "不使用 ChatGPT 登录凭据", () -> {
            List<AiProfile> values = new ArrayList<>(); for (AiProfile p : ai.profiles.list()) if (p.kind != AiProfile.Kind.CHATGPT) values.add(p);
            String[] labels = values.stream().map(p -> p.name).toArray(String[]::new);
            new com.hawson.timeassistant.design.SoftAlertDialogBuilder(activity).setTitle("选择 API 连接").setItems(labels, (d, index) -> {
                AiProfile p = values.get(index);
                try {
                    String credential = new com.hawson.timeassistant.data.connections.EncryptedCredentialStore(activity).read(p.credentialRef);
                    if (credential == null || credential.isEmpty()) throw new IllegalArgumentException();
                    key.setText(credential); url.setText(p.baseUrl); engine.setSelection(2);
                } catch (Exception ignored) { Toast.makeText(activity, "此连接没有可用 Key", Toast.LENGTH_SHORT).show(); }
            }).setNegativeButton("取消", null).show();
        });
        engine.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                api.setVisibility(position == 0 ? View.GONE : View.VISIBLE); url.setEnabled(position == 2);
                if (position == 1 && !url.getText().toString().equals("https://api.openai.com/v1")) { url.setText("https://api.openai.com/v1"); key.setText(""); }
            }
        });
        TextView error = text(body, "", 14); BottomSheetDialog[] dialog = new BottomSheetDialog[1];
        button(body, "保存", () -> {
            int selected = engine.getSelectedItemPosition();
            Runnable save = () -> {
                try {
                    if (selected != 0) {
                        String root = selected == 1 ? "https://api.openai.com/v1" : AiAuxiliaryServices.base(url.getText().toString());
                        if (model.length() == 0) { model.setError("请填写转写模型"); return; }
                        if (key.length() == 0 && (!root.equals(services.get("speech.url", "https://api.openai.com/v1")) || !services.hasSecret("aux:speech"))) { key.setError("请为此服务填写 API Key"); return; }
                        services.secret("aux:speech", key.getText().toString()); services.put("speech.url", root); services.put("speech.model", model.getText().toString().trim());
                    }
                    services.put("speech.engine", selected == 0 ? "system" : selected == 1 ? "official" : "custom");
                    key.setText(""); dialog[0].dismiss();
                } catch (Exception failed) { error.setText("保存失败，请检查服务与凭据"); }
            };
            if (selected == 0) save.run(); else {
                try { allow(selected == 1 ? "https://api.openai.com/v1" : AiAuxiliaryServices.base(url.getText().toString()), save); }
                catch (Exception invalid) { url.setError("请输入有效服务地址"); }
            }
        });
        dialog[0] = sheet("语音输入", body); dialog[0].getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void allow(String url, Runnable next) {
        try { services.allow(url); next.run(); }
        catch (IllegalArgumentException notAllowed) {
            com.hawson.timeassistant.ai.AiTransportPolicy.privateHttpOrigin(url);
            new com.hawson.timeassistant.design.SoftAlertDialogBuilder(activity).setTitle("允许此私网 HTTP 地址？")
                .setMessage(url + "\nHTTP 不加密，仅在信任的私网使用。")
                .setNegativeButton("取消", null).setPositiveButton("允许", (d, w) -> { services.approve(url); next.run(); }).show();
        }
    }
    private void openConnections() { for (BottomSheetDialog sheet : new HashSet<>(sheets)) sheet.dismiss(); connections.run(); }
    void close() { for (AiCancellation cancel : requests) cancel.cancel(); requests.clear(); for (BottomSheetDialog sheet : new HashSet<>(sheets)) sheet.dismiss(); worker.shutdownNow(); }
}
