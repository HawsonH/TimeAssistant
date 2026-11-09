package com.hawson.timeassistant.settings;

import android.content.Context;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.LifecycleOwner;
import com.google.android.material.button.MaterialButton;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.settingsfeature.databinding.*;
import java.util.*;
import com.hawson.timeassistant.design.UiKit;

/** The feature owns presentation and drafts; the shell only launches the browser and cancels work. */
public final class AiConnectionsView extends LinearLayout {
    public interface Host {
        void beforeChange(Runnable change);
        void openBrowser(String url);
        default void keepSignInAlive() { }
        default void signInBackgroundSettings() { }
        default void diagnostics() { }
        default void usage() { }
        default void speech() { }
    }
    private final AiSettingsViewModel vm;
    private final Host host;
    private final ViewAiConnectionsBinding binding;
    private boolean bindingFields;
    private final androidx.lifecycle.Observer<String> noticeObserver;
    private final androidx.lifecycle.Observer<AiAccountPort.Account> accountObserver;
    private AiSignInStatus displayedSignInStatus = AiSignInStatus.IDLE;
    private final Runnable connectionChanged;
    private final Runnable refreshSignInStatus = new Runnable() {
        @Override public void run() {
            AiSignInStatus status = vm.accounts.signInStatus();
            if (status != displayedSignInStatus && status != AiSignInStatus.IDLE) {
                displayedSignInStatus = status;
                if (vm.editing == null) {
                    String ready = vm.settings.connectionNotice();
                    vm.notice.setValue(status == AiSignInStatus.CONNECTED && !ready.isEmpty() ? ready : status.message);
                    if (!status.active) showList();
                }
            }
            postDelayed(this, 1000);
        }
    };
    public AiConnectionsView(Context context, LifecycleOwner owner, AiSettingsViewModel vm, Host host) {
        super(context); this.vm = vm; this.host = host;
        connectionChanged = () -> {
            if (vm.editing != null) return;
            showList();
            String notice = vm.settings.connectionNotice();
            if (!notice.isEmpty()) vm.notice.setValue(notice);
        };
        setOrientation(VERTICAL);
        binding = ViewAiConnectionsBinding.inflate(LayoutInflater.from(context), this, true);
        setId(com.hawson.timeassistant.settingsfeature.R.id.aiSettingsContainer);
        ViewAiEditorBinding editor = binding.editor;
        editor.protocol.setSimpleItems(new String[]{"Chat Completions", "Responses"});
        editor.protocol.setOnItemClickListener((parent, view, position, id) -> {
            vm.protocol = position == 0 ? AiProfile.Protocol.CHAT_COMPLETIONS : AiProfile.Protocol.RESPONSES;
            vm.changed();
        });
        watch(editor.name, value -> vm.name = value);
        watch(editor.url, value -> vm.url = value);
        watch(editor.model, value -> vm.model = value);
        watch(editor.key, value -> vm.key = value);
        editor.back.setOnClickListener(v -> leave(this::showList));
        editor.save.setOnClickListener(v -> save(this::showList));
        editor.test.setOnClickListener(v -> new com.hawson.timeassistant.design.SoftAlertDialogBuilder(getContext()).setTitle("连接选项")
                .setItems(new String[]{"测试连接"}, (d, w) -> probe(false)).show());
        editor.models.setOnClickListener(v -> chooseModels());
        editor.modelLayout.setEndIconOnClickListener(v -> chooseModels());
        editor.model.setOnClickListener(v -> { if (vm.editing != null && vm.editing.kind == AiProfile.Kind.CHATGPT) chooseModels(); });
        noticeObserver = text -> {
            binding.notice.setText(text);
            editor.status.setText(text);
            boolean empty = text == null || text.trim().isEmpty();
            binding.notice.setVisibility(empty || vm.editing != null ? GONE : VISIBLE);
            editor.status.setVisibility(empty || vm.editing == null ? GONE : VISIBLE);
            editor.save.setEnabled(!vm.busy); editor.test.setEnabled(!vm.busy);
            editor.models.setEnabled(!vm.busy);
        };
        accountObserver = account -> {
            if (account == null) return;
            if (vm.editing != null) {
                vm.signedIn.setValue(null);
                return;
            }
            showList();
            android.content.SharedPreferences welcome = getContext().getSharedPreferences("ai_oauth_welcome", Context.MODE_PRIVATE);
            if (account.authorized && !welcome.getBoolean(account.reference, false)) {
                new com.hawson.timeassistant.design.SoftAlertDialogBuilder(getContext()).setTitle("你正在使用 ChatGPT 计划")
                        .setMessage("官方连接的合资格请求将使用你的 ChatGPT 计划或额度，可在 ChatGPT 设置中管理。")
                        .setPositiveButton("知道了", (d, w) -> welcome.edit().putBoolean(account.reference, true).apply()).show();
            }
            vm.signedIn.setValue(null);
        };
        vm.notice.observe(owner, noticeObserver);
        vm.signedIn.observe(owner, accountObserver);
        if (vm.editing == null) showList(); else showEditor(vm.editing, false);
    }
    private void watch(EditText field, java.util.function.Consumer<String> update) {
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (bindingFields) return;
                update.accept(s.toString()); vm.changed();
            }
            public void afterTextChanged(Editable value) { }
        });
    }
    public void leave(Runnable continuation) {
        if (!vm.dirty) { vm.cancel(); continuation.run(); return; }
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(getContext()).setTitle("保存连接修改？")
                .setPositiveButton("保存", (d, w) -> save(continuation))
                .setNegativeButton("放弃", (d, w) -> { vm.dirty = false; vm.key = ""; vm.cancel(); continuation.run(); })
                .setNeutralButton("继续编辑", null).show();
    }
    public boolean hasPendingWork() { return vm.dirty || vm.busy || vm.editing != null; }
    private View action(LinearLayout group, String label, Runnable callback) {
        String[] lines = label.split("\n", 2);
        View row = UiKit.row(getContext(), lines[0], lines.length > 1 ? lines[1] : "", 0, callback);
        UiKit.addRows(group, 16, row);
        return row;
    }
    /** Section header followed by an edge-to-edge framed list. */
    private LinearLayout heading(LinearLayout parent, String label) {
        parent.addView(UiKit.sectionHeader(getContext(), label));
        LinearLayout group = UiKit.groupList(getContext());
        parent.addView(group, new LayoutParams(-1, -2));
        return group;
    }
    private void markCurrent(View row) {
        if (!(row instanceof LinearLayout)) return;
        LinearLayout line = (LinearLayout) row;
        LayoutParams params = new LayoutParams(-2, -2); params.setMarginStart(dp(8));
        // Place the tag just before the trailing chevron.
        line.addView(UiKit.tag(getContext(), "当前", UiKit.accent(getContext())),
                Math.max(0, line.getChildCount() - 1), params);
    }
    private void showList() {
        boolean switching = isAttachedToWindow() && binding.editor.getRoot().getVisibility() == VISIBLE;
        com.hawson.timeassistant.design.MotionSpec.cancelTree(binding.editor.getRoot());
        vm.editing = null; vm.key = ""; vm.dirty = false;
        binding.editor.getRoot().setVisibility(GONE); binding.list.setVisibility(VISIBLE);
        binding.list.removeAllViews();
        LinearLayout connections = heading(binding.list, "连接");
        AiProfile active = vm.profiles.active();
        for (AiProfile profile : vm.profiles.list()) {
            boolean current = active != null && active.id.equals(profile.id);
            String state = current ? "当前连接" : "未选用";
            View row = action(connections, profile.name + " · " + state + "\n"
                    + (profile.model.isEmpty() ? "未选择模型" : profile.model), () -> profileMenu(profile));
            if (current) markCurrent(row);
        }
        action(connections, "添加连接", this::showAddConnectionMenu);
        LinearLayout account = heading(binding.list, "账号与用量");
        action(account, "查看账号与用量", host::usage);
        action(account, "语音输入", host::speech);
        LinearLayout advanced;
        if (vm.accounts.available()) {
            action(account, getContext().getString(com.hawson.timeassistant.settingsfeature.R.string.oauth_login_help_title),
                    () -> OAuthSignInHelp.show(getContext(), vm.hasPendingSignIn(), this::completePendingSignIn));
            if (!vm.accounts.accounts().isEmpty()) {
                LinearLayout accounts = heading(binding.list, "ChatGPT 账号");
                for (AiAccountPort.Account item : vm.accounts.accounts()) {
                    action(accounts, item.label + "\n" + (item.authorized ? "已授权" : "需要登录或补充授权"),
                            () -> accountMenu(item));
                }
            }
            advanced = heading(binding.list, "高级");
            if (vm.hasPendingSignIn()) {
                action(advanced, "完成浏览器授权\n浏览器无法自动返回时，粘贴完整回调地址", this::completePendingSignIn);
                action(advanced, "登录后台运行设置\n回调超时时检查系统后台限制", host::signInBackgroundSettings);
                action(advanced, "取消本次授权", () -> { vm.accounts.cancelSignIn(); showList(); });
            }
        } else {
            advanced = heading(binding.list, "高级");
            TextView note = new TextView(getContext());
            note.setText("此构建未包含 OAuth 扩展");
            note.setTextSize(12); note.setTextColor(UiKit.secondary(getContext()));
            note.setPadding(dp(16), dp(12), dp(16), dp(12));
            UiKit.addRows(advanced, 16, note);
        }
        action(advanced, "更多操作\n诊断与私网 HTTP 授权", () -> new com.hawson.timeassistant.design.SoftAlertDialogBuilder(getContext()).setTitle("连接选项")
                .setItems(new String[]{"诊断", "撤销私网 HTTP 授权"}, (dialog, item) -> {
                    if (item == 0) { host.diagnostics(); return; }
                    new com.hawson.timeassistant.design.SoftAlertDialogBuilder(getContext())
                .setTitle("撤销 HTTP 授权？").setMessage("HTTPS 连接不受影响。")
                .setNegativeButton("取消", null).setPositiveButton("撤销", (d, w) ->
                        host.beforeChange(() -> { vm.settings.revokePrivateHttp(); vm.notice.setValue("HTTP 授权已撤销"); })).show();
                }).show());
        noticeObserver.onChanged(vm.notice.getValue());
        if (switching) com.hawson.timeassistant.design.MotionSpec.enterHorizontal(binding.list, -1);
    }
    private void showAddConnectionMenu() {
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(getContext()).setTitle("添加连接")
                .setItems(vm.accounts.available() ? new String[]{"ChatGPT", "官方 API Key", "自定义服务"}
                        : new String[]{"官方 API Key", "自定义服务"}, (dialog, which) -> {
                    if (vm.accounts.available() && which == 0) { login(""); return; }
                    int index = vm.accounts.available() ? which - 1 : which;
                    newProfile(index == 0 ? AiProfile.Kind.OPENAI_KEY : AiProfile.Kind.RELAY, "");
                })
                .show();
    }
    private void profileMenu(AiProfile profile) {
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(getContext()).setTitle(profile.name)
                .setItems(new String[]{"使用此连接", "编辑", "复制（不复制凭据）", "删除"}, (dialog, which) -> {
                    if (which == 1) { showEditor(profile, true); return; }
                    if (which == 2) { vm.run(() -> vm.profiles.duplicate(profile.id), value -> showEditor(value, true)); return; }
                    if (which == 3) {
                        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(getContext()).setTitle("删除连接？").setMessage("不会删除任务或其他账号。")
                                .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) ->
                                        host.beforeChange(() -> vm.run(() -> { vm.profiles.delete(profile.id); return true; },
                                                ok -> { showList(); vm.notice.setValue("连接已删除"); }))).show();
                        return;
                    }
                    host.beforeChange(() -> vm.run(() -> { vm.profiles.activate(profile.id); return true; },
                            ok -> { showList(); vm.notice.setValue("已切换连接；新请求不携带旧连接聊天上下文"); }));
                }).show();
    }
    private void newProfile(AiProfile.Kind kind, String account) {
        showEditor(new AiProfile("", kind == AiProfile.Kind.RELAY ? "新中转" : kind == AiProfile.Kind.CHATGPT ? "ChatGPT" : "OpenAI",
                kind, kind == AiProfile.Kind.CHATGPT ? AiProfile.Protocol.RESPONSES : AiProfile.Protocol.CHAT_COMPLETIONS,
                kind == AiProfile.Kind.RELAY ? "" : "https://api.openai.com/v1", "", account), true);
    }
    private void showEditor(AiProfile profile, boolean reset) {
        boolean switching = isAttachedToWindow() && binding.list.getVisibility() == VISIBLE;
        com.hawson.timeassistant.design.MotionSpec.cancelTree(binding.list);
        if (reset) vm.edit(profile);
        binding.list.setVisibility(GONE); binding.editor.getRoot().setVisibility(VISIBLE);
        bindingFields = true;
        ViewAiEditorBinding editor = binding.editor;
        editor.name.setText(vm.name); editor.url.setText(vm.url); editor.model.setText(vm.model); editor.key.setText(vm.key);
        editor.protocol.setText(vm.protocol == AiProfile.Protocol.RESPONSES ? "Responses" : "Chat Completions", false);
        editor.url.setEnabled(profile.kind == AiProfile.Kind.RELAY);
        editor.urlLayout.setVisibility(profile.kind == AiProfile.Kind.RELAY ? VISIBLE : GONE);
        editor.protocolLayout.setVisibility(profile.kind == AiProfile.Kind.CHATGPT ? GONE : VISIBLE);
        editor.protocol.setEnabled(profile.kind != AiProfile.Kind.CHATGPT);
        editor.model.setEnabled(true);
        editor.model.setFocusableInTouchMode(profile.kind != AiProfile.Kind.CHATGPT);
        editor.model.setContentDescription(profile.kind == AiProfile.Kind.CHATGPT ? "模型，点击选择" : "模型 ID");
        editor.keyLayout.setVisibility(profile.kind == AiProfile.Kind.CHATGPT ? GONE : VISIBLE);
        editor.models.setVisibility(profile.kind == AiProfile.Kind.CHATGPT ? GONE : VISIBLE);
        editor.keyLayout.setHelperText(profile.credentialRef.isEmpty() ? null : "已安全保存；留空保留原 Key");
        bindingFields = false;
        noticeObserver.onChanged(vm.notice.getValue());
        if (switching) com.hawson.timeassistant.design.MotionSpec.enterHorizontal(binding.editor.getRoot(), 1);
    }
    private boolean allowed(AiProfile draft, Runnable continuation) {
        try { vm.settings.requireAllowed(draft.baseUrl); return true; }
        catch (IllegalArgumentException rejected) {
            if (!draft.baseUrl.startsWith("http://")) { vm.notice.setValue(rejected.getMessage()); return false; }
            new com.hawson.timeassistant.design.SoftAlertDialogBuilder(getContext()).setTitle("允许此私网 HTTP 地址？")
                    .setMessage(draft.baseUrl + "\nHTTP 不加密，Key 和发送内容可能被网络中的其他人读取。授权只适用于此 IP 与端口。")
                    .setNegativeButton("取消", null).setPositiveButton("授权", (d, w) -> {
                        try { vm.settings.approvePrivateHttp(draft.baseUrl); continuation.run(); }
                        catch (IllegalArgumentException invalid) { vm.notice.setValue(invalid.getMessage()); }
                    }).show();
            return false;
        }
    }
    private void save(Runnable after) {
        try {
            AiProfile draft = vm.draft(); String key = vm.key;
            if (!allowed(draft, () -> save(after))) return;
            host.beforeChange(() -> vm.run(() -> vm.profiles.save(draft, key), saved -> {
                vm.dirty = false; vm.key = ""; vm.editing = saved;
                after.run(); vm.notice.setValue("连接已保存；需在列表选择后使用");
            }));
        } catch (IllegalArgumentException invalid) { vm.notice.setValue(invalid.getMessage()); }
    }
    private void probe(boolean models) {
        try {
            AiProfile draft = vm.draft(); String key = vm.key;
            if (!models && draft.model.isEmpty()) { vm.notice.setValue("请先选择或填写模型"); return; }
            if (!allowed(draft, () -> probe(models))) return;
            if (models) vm.runCancellable(cancel -> vm.settings.models(draft, key, cancel), values -> {
                if (values.isEmpty()) { vm.notice.setValue("没有可选模型"); return; }
                new com.hawson.timeassistant.design.SoftAlertDialogBuilder(getContext()).setTitle("选择模型")
                        .setItems(values.toArray(new String[0]), (d, w) -> binding.editor.model.setText(values.get(w))).show();
                vm.notice.setValue("已获取模型");
            });
            else vm.runCancellable(cancel -> vm.settings.test(draft, key, cancel), result -> vm.notice.setValue(result));
        } catch (IllegalArgumentException invalid) { vm.notice.setValue(invalid.getMessage()); }
    }
    private void chooseModels() {
        try {
            AiProfile draft = vm.draft(); AiCancellation cancel = new AiCancellation();
            String draftKey = vm.key;
            AiProfile editing = vm.editing;
            com.hawson.timeassistant.design.ModelSelectionSheet.show(getContext(), "模型", vm.model, () -> {
                java.util.List<com.hawson.timeassistant.design.ModelSelectionSheet.Option> result = new java.util.ArrayList<>();
                for (AiModelOption option : vm.settings.modelOptions(draft, draftKey, cancel))
                    result.add(new com.hawson.timeassistant.design.ModelSelectionSheet.Option(option.id, option.name));
                return result;
            }, cancel::cancel, selected -> {
                if (vm.editing == editing) binding.editor.model.setText(selected.id);
            }, java.util.Collections.emptyList());
        } catch (Exception invalid) { vm.notice.setValue("请先补全连接配置"); }
    }
    private void accountMenu(AiAccountPort.Account account) {
        new com.hawson.timeassistant.design.SoftAlertDialogBuilder(getContext()).setTitle(account.label)
                .setItems(new String[]{"使用此账号", "重新登录", "账号与用量", "退出此账号"}, (d, w) -> {
                    if (w == 0) host.beforeChange(() -> vm.settings.recoverAccount(account.reference));
                    if (w == 1) login(account.reference);
                    if (w == 2) host.usage();
                    if (w == 3) host.beforeChange(() -> vm.run(() -> vm.accounts.signOut(account.reference), revoked -> {
                        showList(); vm.notice.setValue(revoked ? "账号已退出"
                                : "已清除本机登录；远端撤销未确认，请在 ChatGPT 设置中断开应用");
                    }));
                }).show();
    }
    private void login(String reference) {
        host.keepSignInAlive();
        try {
            String url = vm.login(reference);
            showList();
            vm.notice.setValue(getContext().getString(com.hawson.timeassistant.settingsfeature.R.string.oauth_login_pending_notice));
            host.openBrowser(url);
        } catch (Exception failed) { vm.notice.setValue("无法开始官方登录，请检查浏览器与网络"); }
    }
    private void completePendingSignIn() {
        EditText input = new EditText(getContext());
        input.setHint("粘贴完整回调地址");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(false); input.setMinLines(3); input.setMaxLines(5); input.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        int inset = dp(24);
        FrameLayout content = new FrameLayout(getContext());
        content.setPadding(inset, 0, inset, 0); content.addView(input);
        AlertDialog dialog = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(getContext()).setTitle("完成浏览器授权")
                .setMessage(com.hawson.timeassistant.settingsfeature.R.string.oauth_login_callback_message)
                .setView(content).setNegativeButton("取消", null).setPositiveButton("继续", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String callback = input.getText().toString().trim();
            if (callback.isEmpty()) { input.setError("请粘贴完整回调地址"); return; }
            try {
                vm.completePendingSignIn(callback);
                host.keepSignInAlive();
                input.setText("");
                dialog.dismiss();
                vm.notice.setValue("正在安全处理浏览器回调…");
            } catch (AiSignInException rejected) {
                input.setError(rejected.getMessage());
            } catch (Exception failed) {
                input.setError("无法读取本次登录会话，请取消授权后重新登录");
            }
        }));
        dialog.setOnDismissListener(ignored -> input.setText(""));
        dialog.show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        vm.settings.addConnectionListener(connectionChanged);
        String restored = vm.settings.connectionNotice();
        if (vm.editing == null && !restored.isEmpty() && !vm.accounts.signInStatus().active)
            vm.notice.setValue(restored);
        removeCallbacks(refreshSignInStatus);
        post(refreshSignInStatus);
    }
    @Override protected void onDetachedFromWindow() {
        vm.settings.removeConnectionListener(connectionChanged);
        removeCallbacks(refreshSignInStatus);
        vm.cancel();
        vm.notice.removeObserver(noticeObserver);
        vm.signedIn.removeObserver(accountObserver);
        super.onDetachedFromWindow();
    }
    @Override protected Parcelable onSaveInstanceState() {
        Bundle saved = new Bundle();
        saved.putParcelable("super", super.onSaveInstanceState());
        if (vm.editing != null) {
            saved.putString("id", vm.editing.id); saved.putString("kind", vm.editing.kind.name());
            saved.putString("credential", vm.editing.credentialRef);
            saved.putString("name", vm.name); saved.putString("url", vm.url);
            saved.putString("model", vm.model); saved.putString("protocol", vm.protocol.name());
            saved.putBoolean("dirty", vm.dirty);
        }
        return saved;
    }
    @Override protected void onRestoreInstanceState(Parcelable value) {
        if (!(value instanceof Bundle)) { super.onRestoreInstanceState(value); return; }
        Bundle saved = (Bundle) value;
        super.onRestoreInstanceState(saved.getParcelable("super"));
        if (!saved.containsKey("kind")) return;
        try {
            AiProfile draft = new AiProfile(saved.getString("id", ""), saved.getString("name", ""),
                    AiProfile.Kind.valueOf(saved.getString("kind")),
                    AiProfile.Protocol.valueOf(saved.getString("protocol")),
                    saved.getString("url", ""), saved.getString("model", ""), saved.getString("credential", ""));
            String memoryKey = vm.editing != null && vm.editing.id.equals(draft.id) ? vm.key : "";
            vm.edit(draft); vm.key = memoryKey; vm.dirty = saved.getBoolean("dirty");
            showEditor(draft, false);
        } catch (IllegalArgumentException ignored) { showList(); }
    }
}
