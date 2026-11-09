package com.hawson.timeassistant.focus;

import static org.junit.Assert.assertEquals;

import com.hawson.timeassistant.domain.FocusState;

import org.junit.Test;

public final class FocusCompletionAlarmTest {
    @Test
    public void triggerAtMillisUsesLiveRemainingTime() {
        long startedAt = 1_000_000L;
        FocusState state = FocusState.ready(null, FocusState.Mode.FOCUS,
                60_000L, 1).startOrResume(startedAt);

        assertEquals(startedAt + 60_000L,
                FocusCompletionAlarm.triggerAtMillis(state, startedAt + 12_000L));
    }
}
