package com.hawson.timeassistant.ai;

import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AiPlanDynamicReadTest {
    @Test public void parsesOptionalReadRequestsAndKeepsOldPlansCompatible() throws Exception {
        AiPlan read = AiPlan.parse("{\"message\":\"先读取今天\",\"needsClarification\":false,"
                + "\"readRequests\":[{\"requestId\":\"today\",\"type\":\"tasks.query\","
                + "\"args\":{\"startAt\":1900000000000,\"limit\":20}}],\"actions\":[]}");
        assertTrue(read.needsContextRead());
        assertEquals("tasks.query", read.readRequests.get(0).type);
        assertEquals(new BigDecimal("20"), read.readRequests.get(0).args.get("limit"));

        AiPlan legacy = AiPlan.parse("{\"message\":\"完成\",\"needsClarification\":false,"
                + "\"actions\":[]}");
        assertTrue(legacy.readRequests.isEmpty());
    }

    @Test public void rejectsReadAndWriteInSameTurn() throws Exception {
        try {
            AiPlan.parse("{\"message\":\"冲突\",\"needsClarification\":false,"
                    + "\"readRequests\":[{\"requestId\":\"one\",\"type\":\"tasks.get\","
                    + "\"args\":{\"taskId\":\"t1\"}}],"
                    + "\"actions\":[{\"type\":\"complete_task\",\"taskId\":\"t1\"}]}");
            fail("expected mutual-exclusion validation");
        } catch (AiPlan.ParseException expected) {
            assertTrue(expected.getMessage().contains("不能同时"));
        }
    }

    @Test public void parsesPreferenceAndCalendarProposalTypes() throws Exception {
        AiPlan plan = AiPlan.parse("{\"message\":\"建议\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"create_preference\","
                + "\"preferenceType\":\"preferred_window\",\"scopeType\":\"global\","
                + "\"startMinute\":0,\"endMinute\":480},"
                + "{\"type\":\"compose_calendar_event\",\"title\":\"会议\","
                + "\"startAt\":1900000000000,\"endAt\":1900003600000}]}");
        assertEquals(AiPlan.Action.Type.CREATE_PREFERENCE, plan.actions.get(0).type);
        assertEquals(AiPlan.Action.Type.COMPOSE_CALENDAR_EVENT, plan.actions.get(1).type);
    }
}
