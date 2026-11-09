package com.hawson.timeassistant.connections;

import java.util.List;

/** Repairs incomplete metadata without rewriting a complete connection on each launch. */
public final class AiConnectionRecovery {
    private final AiProfilesPort profiles;
    private final AiPort transport;
    private final Runnable beforeActivate;

    public AiConnectionRecovery(AiProfilesPort profiles, AiPort transport, Runnable beforeActivate) {
        this.profiles = profiles; this.transport = transport; this.beforeActivate = beforeActivate;
    }

    public AiProfile recover(AiAccountPort.Account account, AiCancellation cancel) throws Exception {
        if (!account.authorized) throw new AiConnectionException(AiConnectionException.Status.PERMISSION_DENIED,
                "已登录，但未获得 ChatGPT 计划使用权限");
        AiProfile target = null;
        AiProfile active = profiles.active();
        for (AiProfile profile : profiles.list()) {
            if (profile.kind != AiProfile.Kind.CHATGPT || !account.reference.equals(profile.credentialRef)) continue;
            if (target == null || profile.isComplete()) target = profile;
            if (active != null && active.id.equals(profile.id)) { target = profile; break; }
        }
        if (target == null) target = new AiProfile("", "ChatGPT", AiProfile.Kind.CHATGPT,
                AiProfile.Protocol.RESPONSES, "https://api.openai.com/v1", "", account.reference);
        if (target.model.isEmpty()) {
            List<String> models = transport.models(target, cancel);
            cancel.check();
            if (models.isEmpty()) throw new AiConnectionException(AiConnectionException.Status.FAILED,
                    "账号已保留，尚无可选模型，请重试获取模型");
            target = new AiProfile(target.id, target.name, target.kind, target.protocol,
                    target.baseUrl, models.get(0), target.credentialRef);
            target = profiles.save(target, null);
        }
        cancel.check();
        // Re-read after network I/O: a user's intervening connection choice takes precedence.
        active = profiles.active();
        if (active == null || !active.isComplete()) {
            beforeActivate.run();
            profiles.activate(target.id);
        }
        return target;
    }
}
