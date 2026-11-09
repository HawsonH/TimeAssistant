package com.hawson.timeassistant.ai;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AiClientTest {
    @Test
    public void normalizesBaseAndEndpointUrls() {
        assertEquals("https://api.example.com/v1",
                AiClient.normalizeBaseUrl("  api.example.com/v1///  "));
        assertEquals("https://api.example.com/v1",
                AiClient.normalizeBaseUrl("https://api.example.com/v1/chat/completions"));
        assertEquals("https://api.example.com/v1",
                AiClient.normalizeBaseUrl("https://api.example.com/v1/models"));
        assertEquals("https://api.example.com/v1/chat/completions",
                AiClient.buildChatCompletionsUrl("https://api.example.com/v1/"));
    }

    @Test
    public void rejectsUnsafeOrAmbiguousBaseUrls() {
        assertInvalidUrl("ftp://api.example.com/v1");
        assertInvalidUrl("https://user:password@api.example.com/v1");
        assertInvalidUrl("https://api.example.com/v1?token=secret");
    }

    @Test
    public void parsesStringContentFromOpenAiResponse() throws Exception {
        String planJson = "{\"message\":\"已整理\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"create_task\",\"title\":\"晨跑\"}]}";
        String response = responseWithContent(planJson);

        AiPlan plan = AiClient.parseChatResponse(response);

        assertEquals("已整理", plan.message);
        assertEquals(AiPlan.Action.Type.CREATE_TASK, plan.actions.get(0).type);
    }

    @Test
    public void parsesArrayContentAndFencedJson() throws Exception {
        String fenced = "```json\n{\"message\":\"请确认完成\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"complete_task\",\"taskId\":\"task-7\"}]}\n```";
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", fenced);

        AiPlan plan = AiClient.parseChatResponse(responseWithContent(Arrays.asList(textPart)));

        assertEquals("请确认完成", plan.message);
        assertEquals(AiPlan.Action.Type.COMPLETE_TASK, plan.actions.get(0).type);
    }

    @Test
    public void rejectsBadOpenAiResponse() throws Exception {
        try {
            AiClient.parseChatResponse("{\"choices\":[]}");
            fail("Expected empty choices to fail");
        } catch (AiPlan.ParseException error) {
            assertTrue(error.getMessage().contains("choices"));
        }

        try {
            AiClient.parseChatResponse("{not-json}");
            fail("Expected invalid JSON to fail");
        } catch (AiPlan.ParseException error) {
            assertTrue(error.getMessage().contains("有效 JSON"));
        }
    }

    @Test
    public void sanitizesApiKeyFromProviderErrors() {
        String secret = "sk-super-secret";
        String sanitized = AiClient.sanitize("bad key " + secret + " Authorization: Bearer " + secret,
                secret);

        assertFalse(sanitized.contains(secret));
        assertTrue(sanitized.contains("[REDACTED]"));
    }

    @Test
    public void responseInstructionRequiresInlineSubtasksAndDedicatedStatusActions() {
        assertTrue(AiClient.PLAN_RESPONSE_INSTRUCTION.contains(
                "embed subtasks inside create_task"));
        assertTrue(AiClient.PLAN_RESPONSE_INSTRUCTION.contains(
                "never emit split_task for a task that does not exist yet"));
        assertTrue(AiClient.PLAN_RESPONSE_INSTRUCTION.contains(
                "Use complete_task or cancel_task"));
        assertTrue(AiClient.PLAN_RESPONSE_INSTRUCTION.contains("but never status"));
        assertTrue(AiClient.PLAN_RESPONSE_INSTRUCTION.contains("set_greeting"));
    }

    @Test
    public void buildsMultimodalMessageWithImageAndFileParts() {
        AiClient.Message source = AiClient.Message.userWithAttachments("整理这些附件", Arrays.asList(
                new AiClient.Attachment("photo.png", "image/png", "aW1hZ2U="),
                new AiClient.Attachment("notes.pdf", "application/pdf", "cGRm")
        ));

        Map<String, Object> message = AiClient.messageObject(source);
        assertEquals("user", message.get("role"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) message.get("content");
        assertEquals(3, parts.size());
        assertEquals("text", parts.get(0).get("type"));
        assertEquals("image_url", parts.get(1).get("type"));
        assertEquals("file", parts.get(2).get("type"));
        assertTrue(MiniJson.stringify(message).contains("data:image/png;base64,aW1hZ2U="));
        assertTrue(MiniJson.stringify(message).contains("notes.pdf"));
    }

    private static String responseWithContent(Object content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("content", content);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("message", message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("choices", Arrays.asList(choice));
        return MiniJson.stringify(response);
    }

    private static void assertInvalidUrl(String value) {
        try {
            AiClient.normalizeBaseUrl(value);
            fail("Expected invalid URL: " + value);
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
