package com.hawson.timeassistant.connections;

import java.util.Objects;

/** Non-secret, immutable connection descriptor. Credentials never travel with UI state. */
public final class AiProfile {
    public enum Kind { OPENAI_KEY, RELAY, CHATGPT }
    public enum Protocol { CHAT_COMPLETIONS, RESPONSES }
    public final String id, name, baseUrl, model, credentialRef;
    public final Kind kind;
    public final Protocol protocol;

    public AiProfile(String id, String name, Kind kind, Protocol protocol,
                     String baseUrl, String model, String credentialRef) {
        this.id = text(id); this.name = text(name);
        this.kind = Objects.requireNonNull(kind);
        this.protocol = Objects.requireNonNull(protocol);
        this.baseUrl = text(baseUrl); this.model = text(model);
        this.credentialRef = text(credentialRef);
        if (this.name.isEmpty()) throw new IllegalArgumentException("连接名称不能为空");
        if (kind != Kind.RELAY && !"https://api.openai.com/v1".equals(this.baseUrl)) {
            throw new IllegalArgumentException("官方连接不能修改为第三方地址");
        }
        if (kind == Kind.CHATGPT && protocol != Protocol.RESPONSES) {
            throw new IllegalArgumentException("ChatGPT 登录必须使用 Responses");
        }
    }

    public AiProfile withIdentity(String newId, String ref) {
        return new AiProfile(newId, name, kind, protocol, baseUrl, model, ref);
    }

    public boolean isComplete() { return !model.isEmpty() && !credentialRef.isEmpty(); }
    private static String text(String value) { return value == null ? "" : value.trim(); }
}
