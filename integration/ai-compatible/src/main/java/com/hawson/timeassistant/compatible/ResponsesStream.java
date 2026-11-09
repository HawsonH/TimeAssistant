package com.hawson.timeassistant.compatible;

import com.hawson.timeassistant.connections.AiCancellation;
import com.hawson.timeassistant.connections.AiConnectionException;
import com.hawson.timeassistant.network.AiHttp;
import java.io.*;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONObject;

/** SSE framing is independent of socket chunking; partial text never becomes an executable plan. */
public final class ResponsesStream {
    private ResponsesStream() { }
    public static String read(Reader source, AiCancellation cancel) throws Exception {
        return read(source, cancel, ignored -> { });
    }
    public static String read(Reader source, AiCancellation cancel,
                            java.util.function.Consumer<com.hawson.timeassistant.connections.AiCallObserver.Usage> usage) throws Exception {
        BufferedReader reader = new BufferedReader(source);
        StringBuilder data = new StringBuilder();
        int total = 0, next;
        StringBuilder line = new StringBuilder();
        Map<Long, StringBuilder> textParts = new TreeMap<>();
        while ((next = reader.read()) != -1) {
            cancel.check();
            if (++total > AiHttp.LIMIT) throw new IOException("流式响应过大");
            if (next != '\n') { if (next != '\r') line.append((char) next); continue; }
            String current = line.toString(); line.setLength(0);
            if (current.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(current.substring(5).trim());
            }
            if (!current.isEmpty() || data.length() == 0) continue;
            JSONObject event = new JSONObject(data.toString()); data.setLength(0);
            String type = event.optString("type");
            if ("response.completed".equals(type) || "response.failed".equals(type) || "response.incomplete".equals(type)) {
                JSONObject response = event.optJSONObject("response");
                if (response != null) usage.accept(AiUsageCodec.read(response.optJSONObject("usage")));
            }
            if ("response.output_text.delta".equals(type) || "response.output_text.done".equals(type)) {
                long part = ((long) event.optInt("output_index", 0) << 32)
                        | (event.optInt("content_index", 0) & 0xffffffffL);
                StringBuilder text = textParts.get(part);
                if (text == null) { text = new StringBuilder(); textParts.put(part, text); }
                if ("response.output_text.done".equals(type)) {
                    text.setLength(0);
                    text.append(event.getString("text"));
                } else text.append(event.getString("delta"));
            }
            if ("response.completed".equals(type)) {
                StringBuilder streamed = new StringBuilder();
                for (StringBuilder text : textParts.values()) streamed.append(text);
                return AiWireCodec.responseText(event.getJSONObject("response"), streamed.toString());
            }
            if ("response.failed".equals(type) || "response.incomplete".equals(type) || "error".equals(type)) {
                // Never echo arbitrary provider payloads, which may contain secrets.
                JSONObject response = event.optJSONObject("response");
                JSONObject error = response == null ? event.optJSONObject("error") : response.optJSONObject("error");
                String code = error == null ? event.optString("code") : error.optString("code");
                if ("subscription_sharing_usage_limit_exceeded".equals(code)) {
                    throw new AiConnectionException(AiConnectionException.Status.USAGE_LIMITED,
                            -1, code, "", false,
                            "ChatGPT 计划使用受限，请在 ChatGPT 设置中检查用量与应用限额；无需重新登录，未生成可执行提案");
                }
                if ("subscription_sharing_usage_unavailable".equals(code)) {
                    throw new AiConnectionException(AiConnectionException.Status.UNAVAILABLE,
                            -1, code, "", false,
                            "ChatGPT 计划暂不可用，登录信息已保留，请稍后重试");
                }
                if ("subscription_sharing_invalid_user".equals(code)) throw AiConnectionException.http(401, code, "");
                if ("subscription_sharing_user_not_eligible".equals(code)
                        || "chatpass_v2_scope_not_authorized".equals(code)
                        || "chatpass_v2_invalid_authorization_context".equals(code)) {
                    throw AiConnectionException.http(403, code, "");
                }
                if ("subscription_sharing_user_unavailable".equals(code)) throw AiConnectionException.http(503, code, "");
                throw new AiConnectionException(AiConnectionException.Status.INCOMPLETE,
                        "模型请求未完成，请检查授权与额度；未生成可执行提案");
            }
        }
        throw new AiConnectionException(AiConnectionException.Status.INCOMPLETE,
                "连接提前中断，未收到 response.completed");
    }
}
