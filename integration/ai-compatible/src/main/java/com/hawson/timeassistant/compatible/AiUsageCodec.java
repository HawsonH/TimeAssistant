package com.hawson.timeassistant.compatible;

import org.json.JSONObject;
import com.hawson.timeassistant.connections.AiCallObserver;

public final class AiUsageCodec {
    private AiUsageCodec() { }
    private static Long number(JSONObject value, String name) {
        if (value == null || !value.has(name) || value.isNull(name)) return null;
        Object raw = value.opt(name);
        return raw instanceof Number && ((Number) raw).longValue() >= 0 ? ((Number) raw).longValue() : null;
    }
    public static AiCallObserver.Usage read(JSONObject value) {
        if (value == null) return new AiCallObserver.Usage(null, null, null);
        boolean responses = value.has("input_tokens");
        JSONObject details = value.optJSONObject(responses ? "input_tokens_details" : "prompt_tokens_details");
        return new AiCallObserver.Usage(number(value, responses ? "input_tokens" : "prompt_tokens"),
                number(value, responses ? "output_tokens" : "completion_tokens"), number(details, "cached_tokens"));
    }
}
