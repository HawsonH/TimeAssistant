package com.hawson.timeassistant.connections;

/** Fixed messages only: callback contents and provider errors must never reach the UI. */
public final class AiSignInException extends SecurityException {
    public enum Reason {
        INVALID("地址格式无效，请复制浏览器地址栏中的完整回调地址"),
        ORIGIN("回调地址与本次登录不匹配，请使用本次授权完成后的地址"),
        STATE("登录状态不匹配，请使用本次授权完成后的地址；旧地址不能重复使用"),
        PARAMETERS("回调字段缺失或重复，请重新复制完整地址"),
        MISSING("没有待完成的授权会话，本次授权可能已取消或处理，请查看登录状态后重新开始"),
        EXPIRED("登录会话已过期，请取消本次授权后重新登录，并在十分钟内完成"),
        EXCHANGE("授权码交换未完成，请检查网络后重新发起登录"),
        IDENTITY("登录身份验证未完成，请重新发起登录"),
        BUSY("正在完成授权，请稍候");

        final String message;
        Reason(String message) { this.message = message; }
    }

    public final Reason reason;
    public AiSignInException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }
}
