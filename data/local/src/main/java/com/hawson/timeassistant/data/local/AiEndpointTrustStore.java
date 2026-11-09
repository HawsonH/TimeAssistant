package com.hawson.timeassistant.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import com.hawson.timeassistant.ai.AiTransportPolicy;
import java.util.HashSet;
import java.util.Set;

/** Device-local address grants, intentionally excluded from backup and synchronization. */
public final class AiEndpointTrustStore {
    private final SharedPreferences preferences;

    public AiEndpointTrustStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences("ai_endpoint_trust", Context.MODE_PRIVATE);
    }

    public AiTransportPolicy policy() {
        return new AiTransportPolicy(origin ->
                preferences.getStringSet("private_http_origins", new HashSet<>()).contains(origin));
    }

    public void approve(String url) {
        String origin = AiTransportPolicy.privateHttpOrigin(url);
        Set<String> values = new HashSet<>(
                preferences.getStringSet("private_http_origins", new HashSet<>()));
        values.add(origin);
        preferences.edit().putStringSet("private_http_origins", values).apply();
    }

    public void revokeAll() { preferences.edit().clear().apply(); }
}
