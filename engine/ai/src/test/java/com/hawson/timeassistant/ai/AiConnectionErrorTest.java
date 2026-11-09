package com.hawson.timeassistant.ai;

import com.hawson.timeassistant.connections.AiConnectionException;
import org.junit.Test;
import static org.junit.Assert.*;

public class AiConnectionErrorTest {
    @Test public void authorizationAndUsageAreNotConvertedToNetworkErrors() {
        for (int status : new int[]{401, 403, 429, 503}) {
            AiConnectionException source = AiConnectionException.http(status, "", "req_fixture");
            AiClient.AiError error = AiClient.AiError.connection(source);
            assertEquals(AiClient.AiError.Kind.HTTP, error.kind);
            assertEquals(status, error.httpStatus); assertEquals(source.status, error.connectionStatus);
            assertEquals(source.getMessage(), error.getMessage()); assertNull(error.getCause());
        }
    }
    @Test public void refreshFailureRetainsItsActionableCategory() {
        AiClient.AiError error = AiClient.AiError.connection(new AiConnectionException(
                AiConnectionException.Status.LOGIN_REQUIRED, "登录已失效，请重新授权 ChatGPT"));
        assertEquals(AiConnectionException.Status.LOGIN_REQUIRED, error.connectionStatus);
        assertNotEquals(AiClient.AiError.Kind.NETWORK, error.kind);
    }
}
