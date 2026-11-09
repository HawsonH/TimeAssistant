package com.hawson.timeassistant.compatible;

import com.hawson.timeassistant.connections.*;
import java.io.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResponsesStreamTest {
    private String completed() {
        return "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                + "\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}}\n\n";
    }
    @Test public void completeEventRequired() throws Exception {
        assertEquals("OK", ResponsesStream.read(new StringReader(": comment\n\n" + completed()), new AiCancellation()));
    }
    @Test public void deltaOnlyRejected() throws Exception {
        try {
            ResponsesStream.read(new StringReader("data: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}\n\n"),
                    new AiCancellation()); fail();
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("response.completed")); }
    }
    private String event(String json) { return "data: " + json + "\n\n"; }
    private String emptyCompleted() {
        return event("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}");
    }
    @Test public void streamedTextAcceptedOnlyAfterCompleted() throws Exception {
        String stream = event("{\"type\":\"response.output_text.delta\",\"delta\":\"Hello \"}")
                + event("{\"type\":\"response.output_text.delta\",\"delta\":\"world\"}") + emptyCompleted();
        assertEquals("Hello world", ResponsesStream.read(new StringReader(stream), new AiCancellation()));
    }
    @Test public void doneSnapshotReplacesDeltasAndPartsKeepOutputOrder() throws Exception {
        String stream = event("{\"type\":\"response.output_text.delta\",\"output_index\":1,\"delta\":\"B\"}")
                + event("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"partial\"}")
                + event("{\"type\":\"response.output_text.done\",\"output_index\":0,\"text\":\"A\"}")
                + emptyCompleted();
        assertEquals("AB", ResponsesStream.read(new StringReader(stream), new AiCancellation()));
    }
    @Test public void terminalTextIsNotDuplicatedWithStreamedText() throws Exception {
        String stream = event("{\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}") + completed();
        assertEquals("OK", ResponsesStream.read(new StringReader(stream), new AiCancellation()));
    }
    @Test public void emptyCompletedStillRejected() throws Exception {
        try {
            ResponsesStream.read(new StringReader(emptyCompleted()), new AiCancellation()); fail();
        } catch (JSONException expected) { assertEquals("模型返回空内容", expected.getMessage()); }
    }
    @Test public void failureAfterActualTextStillRejected() throws Exception {
        String stream = event("{\"type\":\"response.output_text.delta\",\"delta\":\"unconfirmed\"}")
                + event("{\"type\":\"response.failed\"}") + emptyCompleted();
        try {
            ResponsesStream.read(new StringReader(stream), new AiCancellation()); fail();
        } catch (AiConnectionException expected) {
            assertEquals(AiConnectionException.Status.INCOMPLETE, expected.status);
        }
    }
    @Test public void failureAfterDeltaRejected() throws Exception {
        try {
            ResponsesStream.read(new StringReader("data: {\"type\":\"response.failed\"}\n\n" + completed()),
                    new AiCancellation()); fail();
        } catch (IOException expected) { }
    }
    @Test public void incompleteStatusRejectedEvenWithCompletedEvent() throws Exception {
        try {
            ResponsesStream.read(new StringReader(completed().replace("\"completed\"", "\"incomplete\"")),
                    new AiCancellation()); fail();
        } catch (JSONException expected) { }
    }
    @Test public void cancelledReadRejected() throws Exception {
        AiCancellation cancel = new AiCancellation(); cancel.cancel();
        try { ResponsesStream.read(new StringReader(completed()), cancel); fail(); }
        catch (InterruptedIOException expected) { }
    }
    @Test public void requestOmitsUnsupportedFieldsAndSystemRole() throws Exception {
        AiProfile profile = new AiProfile("1", "ChatGPT", AiProfile.Kind.CHATGPT,
                AiProfile.Protocol.RESPONSES, "https://api.openai.com/v1", "model", "oauth:1");
        JSONObject body = AiWireCodec.encode(profile, new AiRequest("instructions",
                Collections.singletonList(new AiRequest.Message("user", "hello", Collections.emptyList()))));
        assertFalse(body.getBoolean("store")); assertTrue(body.getBoolean("stream"));
        assertFalse(body.has("messages")); assertFalse(body.has("previous_response_id"));
        assertFalse(body.has("temperature")); assertFalse(body.has("max_output_tokens"));
        assertEquals("user", body.getJSONArray("input").getJSONObject(0).getString("role"));
    }
    @Test public void chatCompatibilityPreserved() throws Exception {
        assertEquals("OK", AiWireCodec.chatText("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"));
    }
    @Test public void planLimitDoesNotExposeProviderMessage() throws Exception {
        String response = "data: {\"type\":\"response.failed\",\"response\":{\"error\":{"
                + "\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"secret-token\"}}}\n\n";
        try {
            ResponsesStream.read(new StringReader(response), new AiCancellation()); fail();
        } catch (AiConnectionException expected) {
            assertEquals(AiConnectionException.Status.USAGE_LIMITED, expected.status);
            assertEquals("subscription_sharing_usage_limit_exceeded", expected.providerCode);
            assertTrue(expected.getMessage().contains("无需重新登录"));
            assertFalse(expected.getMessage().contains("secret-token"));
        }
    }
    @Test public void unavailableUsageKeepsDistinctSafeErrorCode() throws Exception {
        String response = event("{\"type\":\"response.failed\",\"response\":{\"error\":{"
                + "\"code\":\"subscription_sharing_usage_unavailable\",\"message\":\"fixture-secret\"}}}");
        try {
            ResponsesStream.read(new StringReader(response), new AiCancellation()); fail();
        } catch (AiConnectionException expected) {
            assertEquals(AiConnectionException.Status.UNAVAILABLE, expected.status);
            assertEquals("subscription_sharing_usage_unavailable", expected.providerCode);
            assertFalse(expected.getMessage().contains("fixture-secret"));
        }
    }
}
