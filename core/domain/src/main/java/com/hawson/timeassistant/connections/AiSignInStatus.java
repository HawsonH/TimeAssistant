package com.hawson.timeassistant.connections;

/** Public progress only; no account identity or authorization parameters. */
public enum AiSignInStatus {
    IDLE(false, ""),
    WAITING(true, "请在浏览器完成授权"),
    EXCHANGING(true, "已收到浏览器授权，正在完成登录…"),
    CONNECTED(false, "官方授权完成"),
    PERMISSION(false, "已登录，但未获得计划使用权限"),
    RETURN_READY(false, "浏览器已收到授权，请在回调页点“返回时屿”"),
    CALLBACK(false, "浏览器回调未收到，可使用“完成浏览器授权”继续"),
    EXPIRED(false, "登录会话已过期，请重新登录"),
    CANCELLED(false, "本次授权已取消"),
    EXCHANGE_FAILED(false, "授权码交换未完成，请检查网络后重新登录"),
    INVALID_GRANT(false, "授权码已失效或已使用，请重新登录"),
    CLIENT_FAILED(false, "授权客户端未被接受，请重新开始授权"),
    NETWORK_FAILED(false, "无法连接授权服务，请检查手机网络后重试"),
    IDENTITY_FAILED(false, "登录身份验证未完成，请重新登录"),
    INTERRUPTED(false, "上次登录处理中断，请重新登录"),
    FAILED(false, "登录未完成，请检查网络和授权后重试");

    public final boolean active;
    public final String message;
    AiSignInStatus(boolean active, String message) { this.active = active; this.message = message; }
}
