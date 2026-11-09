package com.hawson.timeassistant.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class FocusDialGestureTest {
    private final FocusDialGesture gesture = new FocusDialGesture(8, 24, 52);

    @Test public void eachTwentyFourPixelsAddsOneMinute() {
        gesture.begin(100, 200, 25, 180);
        gesture.move(100, 196);
        gesture.move(100, 190);
        assertTrue(gesture.move(100, 176));
        assertEquals(26, gesture.value());
        assertEquals(28, gesture.end(100, 128).minutes);
        assertEquals(0, gesture.end(100, 128).minutes);
    }
    @Test public void downDecreasesAndReverseTracksImmediatelyAtLimit() {
        gesture.begin(0, 500, 59, 60);
        gesture.move(0, 0);
        assertEquals(60, gesture.value());
        gesture.move(0, 24);
        assertEquals(59, gesture.value());
        assertEquals(0, gesture.end(0, 24).minutes);
    }
    @Test public void lowerLimitDoesNotAccumulateOverscroll() {
        gesture.begin(0, 0, 2, 60);
        gesture.move(0, 400);
        assertEquals(1, gesture.value());
        gesture.move(0, 376);
        assertEquals(2, gesture.value());
        assertEquals(0, gesture.end(0, 376).minutes);
    }
    @Test public void horizontalDirectionCannotBecomeVertical() {
        gesture.begin(100, 200, 25, 180);
        gesture.move(80, 201);
        gesture.move(40, 0);
        FocusDialGesture.Result result = gesture.end(40, 0);
        assertEquals(1, result.modeDirection);
        assertEquals(0, result.minutes);
        assertFalse(result.tap);
    }
    @Test public void verticalDirectionCannotBecomeHorizontal() {
        gesture.begin(0, 100, 25, 180);
        gesture.move(1, 80);
        FocusDialGesture.Result result = gesture.end(100, 52);
        assertEquals(27, result.minutes);
        assertEquals(0, result.modeDirection);
    }
    @Test public void diagonalOrShortDragDoesNotOpenSettings() {
        gesture.begin(0, 0, 25, 180);
        assertFalse(gesture.end(20, 20).tap);
        gesture.begin(0, 0, 25, 180);
        assertFalse(gesture.end(0, 12).tap);
    }
    @Test public void onlySmallMovementIsTap() {
        gesture.begin(0, 0, 25, 180);
        assertTrue(gesture.end(3, 4).tap);
    }
    @Test public void cancellationNeverSubmitsOrClicks() {
        gesture.begin(0, 100, 25, 180);
        gesture.move(0, 20);
        gesture.cancel();
        FocusDialGesture.Result result = gesture.end(0, 20);
        assertFalse(result.tap);
        assertEquals(0, result.minutes);
    }
    @Test public void newGestureUsesNewModeRange() {
        gesture.begin(0, 500, 60, 180);
        gesture.move(0, 0);
        assertTrue(gesture.value() > 60);
        gesture.begin(0, 500, 60, 60);
        assertEquals(60, gesture.value());
        assertEquals(0, gesture.end(0, 0).minutes);
    }
}
