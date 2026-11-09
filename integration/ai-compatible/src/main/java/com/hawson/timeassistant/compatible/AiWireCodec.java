package com.hawson.timeassistant.compatible;

import com.hawson.timeassistant.connections.*;
import org.json.*;

public final class AiWireCodec {
    private AiWireCodec() { }
    public static AiConnectionException httpFailure(int status, String raw, String requestId) {
        String code = "";
        try {
            JSONObject error = new JSONObject(raw).optJSONObject("error");
            String candidate = error == null ? "" : error.optString("code");
            if (java.util.Arrays.asList("invalid_token", "token_expired", "access_token_expired",
                    "subscription_sharing_user_not_eligible", "subscription_sharing_usage_limit_exceeded",
                    "subscription_sharing_usage_unavailable", "subscription_sharing_unsupported_capability",
                    "subscription_sharing_route_not_supported", "subscription_sharing_invalid_user",
                    "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context",
                    "subscription_sharing_user_unavailable").contains(candidate)) code = candidate;
        } catch (JSONException ignored) { }
        return AiConnectionException.http(status, code, requestId);
    }
    public static JSONObject encode(AiProfile profile, AiRequest request) throws JSONException {
        boolean responses = profile.protocol == AiProfile.Protocol.RESPONSES;
        JSONArray messages = new JSONArray();
        if (!responses) messages.put(new JSONObject().put("role", "system").put("content", request.instructions));
        for (AiRequest.Message source : request.messages) {
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("type", responses
                    ? ("assistant".equals(source.role) ? "output_text" : "input_text") : "text")
                    .put("text", source.text));
            for (AiRequest.Part part : source.parts) {
                if (part.mime.startsWith("image/")) {
                    parts.put(responses
                            ? new JSONObject().put("type", "input_image").put("image_url", part.dataUrl())
                            : new JSONObject().put("type", "image_url").put("image_url",
                            new JSONObject().put("url", part.dataUrl())));
                } else {
                    parts.put(responses
                            ? new JSONObject().put("type", "input_file").put("filename", part.name)
                            .put("file_data", part.dataUrl())
                            : new JSONObject().put("type", "file").put("file",
                            new JSONObject().put("filename", part.name).put("file_data", part.dataUrl())));
                }
            }
            messages.put(new JSONObject().put("role", source.role).put("content",
                    !responses && source.parts.isEmpty() ? source.text : parts));
        }
        JSONObject body = new JSONObject().put("model", profile.model);
        if (responses) return body.put("instructions", request.instructions).put("input", messages)
                .put("store", false).put("stream", true);
        return body.put("messages", messages);
    }
    public static String chatText(String raw) throws JSONException {
        Object content = new JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").get("content");
        if (content instanceof String) return (String) content;
        StringBuilder result = new StringBuilder();
        JSONArray parts = (JSONArray) content;
        for (int i = 0; i < parts.length(); i++) result.append(parts.getJSONObject(i).optString("text"));
        return result.toString();
    }
    public static String responseText(JSONObject response) throws JSONException {
        return responseText(response, "");
    }
    static String responseText(JSONObject response, String streamedText) throws JSONException {
        if (!"completed".equals(response.optString("status"))) {
            throw new JSONException("响应未完整完成");
        }
        StringBuilder result = new StringBuilder();
        JSONArray output = response.getJSONArray("output");
        for (int i = 0; i < output.length(); i++) {
            JSONArray content = output.getJSONObject(i).optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.getJSONObject(j);
                if ("output_text".equals(part.optString("type"))) result.append(part.getString("text"));
            }
        }
        // Some streaming completions omit text already delivered in output_text events.
        if (result.length() == 0) result.append(streamedText);
        if (result.toString().trim().isEmpty()) throw new JSONException("模型返回空内容");
        return result.toString();
    }
}
