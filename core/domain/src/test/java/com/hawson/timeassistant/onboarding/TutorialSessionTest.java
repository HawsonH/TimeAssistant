package com.hawson.timeassistant.onboarding;

import org.junit.Test;
import static org.junit.Assert.*;

public class TutorialSessionTest {
    @Test public void newUsersSeeIntroductionOnce() {
        assertTrue(TutorialSession.shouldAutoStart(0, false));
        assertFalse(TutorialSession.shouldAutoStart(TutorialSession.VERSION, false));
        assertFalse(TutorialSession.shouldAutoStart(0, true));
    }

    @Test public void progressionStopsAtLastStep() {
        TutorialSession session = new TutorialSession(0);
        assertEquals(0, session.step());
        assertTrue(session.next());
        assertTrue(session.next());
        assertTrue(session.isLastStep());
        assertFalse(session.next());
        assertEquals(2, session.step());
    }

    @Test public void invalidRestoredProgressIsClamped() {
        assertEquals(0, new TutorialSession(-10).step());
        assertEquals(2, new TutorialSession(100).step());
    }
}
