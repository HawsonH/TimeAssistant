package com.hawson.timeassistant.compatible;

import com.hawson.timeassistant.connections.AiConnectionException;
import org.junit.Test;
import static org.junit.Assert.*;

public class AiHttpFailureTest {
    @Test public void onlyExplicitExpiredBearerCanTriggerRefresh() {
        assertTrue(AiWireCodec.httpFailure(401, "{\"error\":{\"code\":\"token_expired\"}}", "req_fixture").refreshable);
        assertFalse(AiWireCodec.httpFailure(401, "{\"detail\":\"denied\"}", "").refreshable);
        assertFalse(AiWireCodec.httpFailure(403, "{\"error\":{\"code\":\"invalid_token\"}}", "").refreshable);
    }
    @Test public void usageErrorIsDistinctFromNetworkAndDoesNotEchoPayload() {
        AiConnectionException error = AiWireCodec.httpFailure(429,
                "{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"SECRET-FIXTURE\"}}", "req_fixture");
        assertEquals(AiConnectionException.Status.USAGE_LIMITED, error.status);
        assertEquals(429, error.httpStatus); assertEquals("req_fixture", error.requestId);
        assertFalse(error.getMessage().contains("SECRET-FIXTURE")); assertNull(error.getCause());
    }
    @Test public void temporaryOutageDoesNotRequireLogin() {
        assertEquals(AiConnectionException.Status.UNAVAILABLE,
                AiWireCodec.httpFailure(503, "{\"detail\":\"temporarily unavailable\"}", "").status);
    }
    @Test public void unknownProviderCodeIsNotRetained() {
        assertEquals("", AiWireCodec.httpFailure(400, "{\"error\":{\"code\":\"secret-fixture\"}}", "").providerCode);
    }
}
