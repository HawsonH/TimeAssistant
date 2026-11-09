package com.hawson.timeassistant.domain;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public final class ChatMessageProactiveTest {
    @Test public void proposalKindUsesStableStorageValue() {
        ChatMessage message = new ChatMessage(ChatMessage.Role.ASSISTANT,
                "午间整理｜有两个待办可以梳理", null, ChatMessage.ActionState.NONE,
                ChatMessage.ProposalKind.PROACTIVE_PLAN, Collections.emptyList());
        assertEquals("proactive_plan", message.toJsonValue().get("proposalKind"));
        assertEquals(ChatMessage.ProposalKind.PROACTIVE_PLAN,
                ChatMessage.ProposalKind.fromStorage("proactive_plan"));
    }
}
