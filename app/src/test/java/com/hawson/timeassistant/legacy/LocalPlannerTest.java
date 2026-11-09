package com.hawson.timeassistant.legacy;

import org.junit.Test;

import java.util.Calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalPlannerTest {
    private final LocalPlanner planner = new LocalPlanner();

    @Test
    public void parsesTomorrowAfternoonAndDuration() {
        LocalPlanner.PlanProposal proposal = planner.plan("明天下午3点健身1小时");
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(proposal.event.startAt);
        Calendar tomorrow = Calendar.getInstance();
        tomorrow.add(Calendar.DAY_OF_YEAR, 1);

        assertEquals(tomorrow.get(Calendar.YEAR), start.get(Calendar.YEAR));
        assertEquals(tomorrow.get(Calendar.DAY_OF_YEAR), start.get(Calendar.DAY_OF_YEAR));
        assertEquals(15, start.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, start.get(Calendar.MINUTE));
        assertEquals(60, proposal.event.durationMinutes);
        assertEquals("健身", proposal.event.title);
        assertEquals("健康", proposal.event.category);
        assertFalse(proposal.inferredTime);
    }

    @Test
    public void parsesMinuteDurationAndChineseColon() {
        LocalPlanner.PlanProposal proposal = planner.plan("今天上午 9：15 阅读 45 分钟");
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(proposal.event.startAt);

        assertEquals(9, start.get(Calendar.HOUR_OF_DAY));
        assertEquals(15, start.get(Calendar.MINUTE));
        assertEquals(45, proposal.event.durationMinutes);
        assertEquals("阅读", proposal.event.title);
        assertEquals("成长", proposal.event.category);
        assertFalse(proposal.inferredTime);
    }

    @Test
    public void infersFutureHalfHourWhenTimeMissing() {
        long before = System.currentTimeMillis();
        LocalPlanner.PlanProposal proposal = planner.plan("写周报 30 分钟");
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(proposal.event.startAt);

        assertTrue(proposal.inferredTime);
        assertTrue(proposal.event.startAt >= before);
        assertTrue(start.get(Calendar.MINUTE) == 0 || start.get(Calendar.MINUTE) == 30);
        assertEquals(30, proposal.event.durationMinutes);
    }

    @Test
    public void clampsUnreasonableDuration() {
        assertEquals(5, planner.plan("整理桌面 1 分钟").event.durationMinutes);
        assertEquals(8 * 60, planner.plan("完成项目 20 小时").event.durationMinutes);
    }
}
