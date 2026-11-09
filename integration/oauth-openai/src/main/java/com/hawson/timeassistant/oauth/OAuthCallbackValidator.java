package com.hawson.timeassistant.oauth;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.hawson.timeassistant.connections.AiSignInException;
import static com.hawson.timeassistant.connections.AiSignInException.Reason.*;

/** Validates a browser return before any authorization code is read or exchanged. */
final class OAuthCallbackValidator {
    private OAuthCallbackValidator() { }

    static URI validate(String callbackUri, String redirectUri, String expectedState) throws Exception {
        if (callbackUri == null || callbackUri.length() > 16_384) {
            throw new AiSignInException(INVALID);
        }
        URI actual;
        URI expected;
        try {
            actual = URI.create(callbackUri.trim());
            expected = URI.create(redirectUri);
        } catch (IllegalArgumentException malformed) {
            throw new AiSignInException(INVALID);
        }
        if (actual.isOpaque() || !Objects.equals(expected.getScheme(), actual.getScheme())
                || !Objects.equals(expected.getHost(), actual.getHost())
                || expected.getPort() != actual.getPort()
                || !Objects.equals(expected.getRawPath(), actual.getRawPath())
                || actual.getRawFragment() != null || actual.getRawUserInfo() != null) {
            throw new AiSignInException(ORIGIN);
        }
        String state = parameters(actual.getRawQuery()).get("state");
        if (state == null || !MessageDigest.isEqual(expectedState.getBytes(StandardCharsets.UTF_8),
                state.getBytes(StandardCharsets.UTF_8))) {
            throw new AiSignInException(STATE);
        }
        return actual;
    }

    private static Map<String, String> parameters(String rawQuery) throws Exception {
        if (rawQuery == null || rawQuery.isEmpty()) throw new AiSignInException(PARAMETERS);
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&", -1)) {
            int split = pair.indexOf('=');
            if (split <= 0) throw new AiSignInException(PARAMETERS);
            String key;
            String value;
            try {
                key = URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8.name());
                value = URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8.name());
            } catch (IllegalArgumentException malformed) {
                throw new AiSignInException(PARAMETERS);
            }
            if (key.isEmpty() || values.put(key, value) != null) {
                throw new AiSignInException(PARAMETERS);
            }
        }
        return values;
    }
}
