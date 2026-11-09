package com.hawson.timeassistant.focus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FocusTimerNotificationFactoryTest {
    @Test
    public void privacyRemovesTaskTitleBeforeAnyNotificationViewIsBuilt() {
        assertEquals("", FocusTimerNotificationFactory.visibleTaskTitle(true, "机密任务"));
        assertEquals("公开任务", FocusTimerNotificationFactory.visibleTaskTitle(false, " 公开任务 "));
        assertEquals("", FocusTimerNotificationFactory.visibleTaskTitle(false, null));
    }

    @Test
    public void countdownUsesFutureElapsedRealtimeBase() {
        FocusTimerNotificationFactory.TimerSpec timer =
                FocusTimerNotificationFactory.countDownTimer(8_000L, 1_800_000L);

        assertEquals(1_808_000L, timer.getBaseElapsedRealtime());
        assertTrue(timer.isCountDown());
    }

    @Test
    public void countUpUsesPastElapsedRealtimeBase() {
        FocusTimerNotificationFactory.TimerSpec timer =
                FocusTimerNotificationFactory.countUpTimer(4_000_000L, 3_661_000L);

        assertEquals(339_000L, timer.getBaseElapsedRealtime());
        assertFalse(timer.isCountDown());
    }

    @Test
    public void timerBasesNeverMovePastElapsedRealtimeForInvalidDurations() {
        FocusTimerNotificationFactory.TimerSpec countdown =
                FocusTimerNotificationFactory.countDownTimer(8_000L, -1L);
        FocusTimerNotificationFactory.TimerSpec countUp =
                FocusTimerNotificationFactory.countUpTimer(8_000L, -1L);

        assertEquals(8_000L, countdown.getBaseElapsedRealtime());
        assertEquals(8_000L, countUp.getBaseElapsedRealtime());
    }
}
