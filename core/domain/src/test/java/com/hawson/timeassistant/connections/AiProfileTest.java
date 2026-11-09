package com.hawson.timeassistant.connections;

import org.junit.Test;
import static org.junit.Assert.*;

public class AiProfileTest {
    @Test public void officialCredentialCannotChangeOrigin() {
        try {
            new AiProfile("1", "official", AiProfile.Kind.CHATGPT, AiProfile.Protocol.RESPONSES,
                    "https://relay.example/v1", "model", "oauth:1");
            fail();
        } catch (IllegalArgumentException expected) { }
    }
    @Test public void officialRequiresResponses() {
        try {
            new AiProfile("1", "official", AiProfile.Kind.CHATGPT, AiProfile.Protocol.CHAT_COMPLETIONS,
                    "https://api.openai.com/v1", "model", "oauth:1");
            fail();
        } catch (IllegalArgumentException expected) { }
    }
    @Test public void noSecretInDescriptor() {
        AiProfile value = new AiProfile("1", "relay", AiProfile.Kind.RELAY,
                AiProfile.Protocol.CHAT_COMPLETIONS, "https://relay.example/v1", "model", "");
        assertFalse(value.isComplete());
        assertEquals("", value.credentialRef);
    }
    @Test public void cancellationIsIdempotentAndLateRegistrationRuns() {
        AiCancellation value = new AiCancellation();
        int[] count = {0};
        value.onCancel(() -> count[0]++);
        value.cancel(); value.cancel();
        value.onCancel(() -> count[0]++);
        assertEquals(2, count[0]);
    }
}
