package com.hawson.timeassistant.connections;

import java.io.IOException;

/** Only application-owned text may be exposed to UI or persisted in diagnostics. */
public final class AiConnectionException extends IOException {
    public enum Status { LOGIN_REQUIRED, PERMISSION_DENIED, USAGE_LIMITED, UNAVAILABLE, TIMEOUT, FAILED, INCOMPLETE }
    public final Status status;
    public final int httpStatus;
    public final String providerCode, requestId;
    public final boolean refreshable;

    public AiConnectionException(Status status, String safeMessage) {
        this(status, -1, "", "", false, safeMessage);
    }
    public AiConnectionException(Status status, int httpStatus, String providerCode,
                                 String requestId, boolean refreshable, String safeMessage) {
        super(safeMessage);
        this.status = status; this.httpStatus = httpStatus;
        this.providerCode = providerCode; this.requestId = requestId; this.refreshable = refreshable;
    }
    public static AiConnectionException http(int httpStatus, String code, String requestId) {
        Status state = httpStatus == 401 ? Status.LOGIN_REQUIRED
                : httpStatus == 403 ? Status.PERMISSION_DENIED
                : httpStatus == 429 ? Status.USAGE_LIMITED
                : httpStatus >= 500 ? Status.UNAVAILABLE : Status.FAILED;
        String message = state == Status.LOGIN_REQUIRED ? "授权未被接受，请检查账号或重新授权"
                : state == Status.PERMISSION_DENIED ? "此账号或所在地区暂不允许该请求，请检查权限"
                : state == Status.USAGE_LIMITED ? "额度或速率受限，请管理用量或稍后重试"
                : state == Status.UNAVAILABLE ? "AI 服务暂不可用，登录信息已保留，请稍后重试"
                : "AI 服务拒绝请求，请检查模型与输入";
        if ("subscription_sharing_unsupported_capability".equals(code)) message = "当前模型或授权不支持此输入，请调整内容";
        if ("subscription_sharing_usage_limit_exceeded".equals(code)) message = "ChatGPT 计划额度受限，请在 ChatGPT 设置中管理用量";
        if ("subscription_sharing_user_not_eligible".equals(code)) message = "此账号或工作区不支持 ChatGPT 计划使用";
        return new AiConnectionException(state, httpStatus, code, requestId,
                httpStatus == 401 && ("token_expired".equals(code) || "invalid_token".equals(code)
                        || "access_token_expired".equals(code)), "HTTP " + httpStatus + "：" + message);
    }
}
