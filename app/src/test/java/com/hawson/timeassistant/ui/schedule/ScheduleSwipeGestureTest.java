package com.hawson.timeassistant.ui.schedule;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScheduleSwipeGestureTest {
    private ScheduleSwipeGesture model(int count, float width) {
        return new ScheduleSwipeGesture(56, count, width, 96, 16);
    }
    private float travelFor(float rendered, float reveal) { return reveal + (rendered - reveal) / .55f; }

    @Test public void normalRevealIs56PerAction() {
        ScheduleSwipeGesture model = model(2, 400);
        model.begin(false);
        model.move(100);
        assertEquals(112, model.getReveal(), .001);
        assertEquals(100, model.getDistance(), .001);
    }
    @Test public void excessTravelHasPoint55Resistance() {
        ScheduleSwipeGesture model = model(2, 400);
        model.begin(false);
        model.move(212);
        assertEquals(167, model.getDistance(), .001);
        assertFalse(model.isArmed());
    }
    @Test public void thresholdUsesSmallerOf96AndQuarterWidth() {
        assertEquals(208, model(2, 600).getThreshold(), .001);
        assertEquals(192, model(2, 320).getThreshold(), .001);
        assertEquals(136, model(1, 320).getThreshold(), .001);
    }
    @Test public void armsAtThresholdAndCommitsOnlyOnRelease() {
        ScheduleSwipeGesture model = model(2, 400);
        model.begin(false);
        assertTrue(model.move(travelFor(209, 112)));
        assertTrue(model.isArmed());
        assertEquals(ScheduleSwipeGesture.Release.COMMIT, model.release());
        assertEquals(ScheduleSwipeGesture.Release.CLOSED, model.release());
    }
    @Test public void hysteresisAndHapticAreStableAcrossRearming() {
        ScheduleSwipeGesture model = model(2, 400);
        model.begin(false);
        assertTrue(model.move(travelFor(209, 112)));
        assertFalse(model.move(travelFor(193, 112)));
        assertTrue(model.isArmed());
        model.move(travelFor(191, 112));
        assertFalse(model.isArmed());
        assertFalse(model.move(travelFor(209, 112)));
        assertTrue(model.isArmed());
    }
    @Test public void retreatBeforeReleaseDoesNotCommit() {
        ScheduleSwipeGesture model = model(2, 400);
        model.begin(false);
        model.move(500);
        model.move(112);
        assertEquals(ScheduleSwipeGesture.Release.OPEN, model.release());
    }
    @Test public void cancelWhileArmedNeverCommitsOrRearms() {
        ScheduleSwipeGesture model = model(2, 400);
        model.begin(false);
        model.move(500);
        model.cancel();
        assertFalse(model.move(500));
        assertFalse(model.isArmed());
        assertEquals(ScheduleSwipeGesture.Release.CLOSED, model.release());
    }
    @Test public void smallDragClosesHalfRevealOpens() {
        ScheduleSwipeGesture model = model(2, 400);
        model.begin(false);
        model.move(55);
        assertEquals(ScheduleSwipeGesture.Release.CLOSED, model.release());
        model.begin(false);
        model.move(56);
        assertEquals(ScheduleSwipeGesture.Release.OPEN, model.release());
    }
    @Test public void openRowCanCloseWithoutCrossingToOppositeSide() {
        ScheduleSwipeGesture model = model(2, 400);
        model.begin(true);
        assertEquals(112, model.getDistance(), .001);
        model.move(-20);
        assertEquals(0, model.getDistance(), .001);
        assertEquals(ScheduleSwipeGesture.Release.CLOSED, model.release());
    }
    @Test public void newGestureResetsHapticBudget() {
        ScheduleSwipeGesture model = model(1, 400);
        model.begin(false);
        assertTrue(model.move(500));
        model.cancel();
        model.begin(false);
        assertTrue(model.move(500));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsEmptyActions() { model(0, 400); }
    @Test(expected = IllegalArgumentException.class) public void rejectsUnmeasuredWidth() { model(1, 0); }
}
