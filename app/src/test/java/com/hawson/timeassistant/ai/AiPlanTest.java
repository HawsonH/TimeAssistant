package com.hawson.timeassistant.ai;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AiPlanTest {
    @Test
    public void parsesStructuredRecurringGroupAndOccurrenceOverrideActions() throws Exception {
        String json = "{\"message\":\"创建每周计划并跳过一次\","
                + "\"needsClarification\":false,\"actions\":["
                + "{\"type\":\"create_recurring_group\",\"title\":\"周会\","
                + "\"startAt\":1900000000000,\"durationMinutes\":60,"
                + "\"unit\":\"weeks\",\"interval\":1,\"weekdays\":[2,4]},"
                + "{\"type\":\"set_recurring_override\",\"groupId\":\"series-1\","
                + "\"occurrenceAt\":1900000000000,\"operation\":\"skip\"}]}";

        AiPlan plan = AiPlan.parse(json);

        assertEquals(AiPlan.Action.Type.CREATE_RECURRING_GROUP, plan.actions.get(0).type);
        assertEquals(AiPlan.Action.Type.SET_RECURRING_OVERRIDE, plan.actions.get(1).type);
    }

    @Test
    public void createsExecutablePlanForOneConfirmedAction() throws Exception {
        AiPlan source = AiPlan.parse("{\"message\":\"两项操作\","
                + "\"needsClarification\":false,\"actions\":["
                + "{\"type\":\"create_task\",\"title\":\"第一项\"},"
                + "{\"type\":\"create_task\",\"title\":\"第二项\"}]}");

        AiPlan selected = source.withActions(Collections.singletonList(source.actions.get(1)));

        assertEquals(1, selected.actions.size());
        assertEquals("第二项", selected.actions.get(0).get("title"));
        assertEquals(source.rawJson, selected.rawJson);
    }

    @Test
    public void rejectsTimezoneLessActionTimesAndAcceptsExplicitOffset() throws Exception {
        assertPlanRejected("{\"message\":\"安排\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"create_task\",\"title\":\"会议\","
                + "\"scheduledStartAt\":\"2026-08-12T17:30:00\","
                + "\"scheduledEndAt\":\"2026-08-12T18:00:00\"}]}",
                "必须包含时区");

        AiPlan accepted = AiPlan.parse("{\"message\":\"安排\","
                + "\"needsClarification\":false,\"actions\":[{"
                + "\"type\":\"create_task\",\"title\":\"会议\","
                + "\"scheduledStartAt\":\"2026-08-12T17:30:00+08:00\","
                + "\"scheduledEndAt\":\"2026-08-12T18:00:00+08:00\"}]}");
        assertEquals(1, accepted.actions.size());
    }

    @Test
    public void parsesValidatedActionAndPreservesRawJson() throws Exception {
        String rawAction = "{ \"type\": \"create_task\", \"title\": \"写课程报告\", \"estimatedMinutes\": 90 }";
        String json = "{\"message\":\"我先给你一份提案\",\"needsClarification\":false,\"actions\":["
                + rawAction + "]}";

        AiPlan plan = AiPlan.parse(json);

        assertEquals("我先给你一份提案", plan.message);
        assertFalse(plan.needsClarification);
        assertEquals(1, plan.actions.size());
        AiPlan.Action action = plan.actions.get(0);
        assertEquals(AiPlan.Action.Type.CREATE_TASK, action.type);
        assertEquals("create_task", action.getWireType());
        assertEquals("写课程报告", action.get("title"));
        assertEquals(new BigDecimal("90"), action.get("estimatedMinutes"));
        assertEquals(rawAction, action.rawJson);
        assertEquals(json, plan.rawJson);
    }

    @Test
    public void parsesFencedJson() throws Exception {
        String content = "模型说明如下：\n```json\n"
                + "{\"message\":\"需要截止时间\",\"needsClarification\":true,\"actions\":[]}\n"
                + "```\n请确认";

        AiPlan plan = AiPlan.parse(content);

        assertTrue(plan.needsClarification);
        assertTrue(plan.actions.isEmpty());
        assertEquals("需要截止时间", plan.message);
    }

    @Test
    public void parsesCreateTaskWithInlineSubtasks() throws Exception {
        String json = "{\"message\":\"课程报告已拆分\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"create_task\",\"title\":\"课程报告\","
                + "\"scheduledStartAt\":1700000000000,\"scheduledEndAt\":1700007200000,"
                + "\"subtasks\":[\"收集资料\",{\"title\":\"撰写初稿\","
                + "\"estimatedMinutes\":60}]}]}";

        AiPlan plan = AiPlan.parse(json);

        AiPlan.Action action = plan.actions.get(0);
        assertEquals(AiPlan.Action.Type.CREATE_TASK, action.type);
        @SuppressWarnings("unchecked")
        List<Object> subtasks = (List<Object>) action.get("subtasks");
        assertEquals(2, subtasks.size());
        assertEquals("收集资料", subtasks.get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> detailed = (Map<String, Object>) subtasks.get(1);
        assertEquals("撰写初稿", detailed.get("title"));
        assertEquals(new BigDecimal("60"), detailed.get("estimatedMinutes"));
    }

    @Test
    public void rejectsInvalidInlineSubtasksAndUpdateStatusPatch() throws Exception {
        assertPlanRejected(
                "{\"message\":\"无效拆分\",\"needsClarification\":false,"
                        + "\"actions\":[{\"type\":\"create_task\",\"title\":\"报告\","
                        + "\"subtasks\":[]}]}",
                "subtasks"
        );
        assertPlanRejected(
                "{\"message\":\"无效拆分\",\"needsClarification\":false,"
                        + "\"actions\":[{\"type\":\"create_task\",\"title\":\"报告\","
                        + "\"subtasks\":[{\"description\":\"缺标题\"}]}]}",
                "title"
        );
        assertPlanRejected(
                "{\"message\":\"绕过完成动作\",\"needsClarification\":false,"
                        + "\"actions\":[{\"type\":\"update_task\",\"taskId\":\"task-1\","
                        + "\"changes\":{\"status\":\"completed\"}}]}",
                "complete_task"
        );
    }

    @Test
    public void rejectsUnknownAction() throws Exception {
        String json = "{\"message\":\"危险操作\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"wipe_everything\"}]}";

        try {
            AiPlan.parse(json);
            fail("Expected unknown action to be rejected");
        } catch (AiPlan.ParseException error) {
            assertTrue(error.getMessage().contains("未知操作"));
            assertTrue(error.getMessage().contains("wipe_everything"));
        }
    }

    @Test
    public void parsesGreetingActionAndLimitsLength() throws Exception {
        AiPlan plan = AiPlan.parse("{\"message\":\"换一句问候\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"set_greeting\",\"text\":\"慢一点也没关系\"}]}");

        assertEquals(AiPlan.Action.Type.SET_GREETING, plan.actions.get(0).type);
        assertEquals("慢一点也没关系", plan.actions.get(0).get("text"));
        assertPlanRejected("{\"message\":\"太长\",\"needsClarification\":false,"
                        + "\"actions\":[{\"type\":\"set_greeting\","
                        + "\"text\":\"123456789012345678901234567890123\"}]}",
                "32");
    }

    @Test
    public void rejectsMalformedKnownAction() throws Exception {
        String json = "{\"message\":\"安排任务\",\"needsClarification\":false,"
                + "\"actions\":[{\"type\":\"schedule_task\",\"taskId\":\"task-1\"}]}";

        try {
            AiPlan.parse(json);
            fail("Expected incomplete schedule action to be rejected");
        } catch (AiPlan.ParseException error) {
            assertTrue(error.getMessage().contains("startAt"));
        }
    }

    @Test
    public void clarificationCannotContainActions() throws Exception {
        String json = "{\"message\":\"还需要信息\",\"needsClarification\":true,"
                + "\"actions\":[{\"type\":\"create_task\",\"title\":\"不应执行\"}]}";

        try {
            AiPlan.parse(json);
            fail("Expected clarification/actions conflict to be rejected");
        } catch (AiPlan.ParseException error) {
            assertTrue(error.getMessage().contains("actions 必须为空"));
        }
    }

    private static void assertPlanRejected(String json, String expectedMessage) throws Exception {
        try {
            AiPlan.parse(json);
            fail("Expected plan to be rejected");
        } catch (AiPlan.ParseException error) {
            assertTrue(error.getMessage().contains(expectedMessage));
        }
    }
}
