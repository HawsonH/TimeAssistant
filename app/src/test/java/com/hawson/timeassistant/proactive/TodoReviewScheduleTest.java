package com.hawson.timeassistant.proactive;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public final class TodoReviewScheduleTest {
    private ProactiveSettings settings() {
        ProactiveSettings value = new ProactiveSettings();
        value.enabled = true;
        value.scheduleGeneration = "current";
        return value;
    }

    @Test public void slotIdentitySurvivesEditsAndCopies() {
        ProactiveSettings value = settings();
        TodoReviewSlot slot = value.addTodoReviewSlot(600);
        value.updateTodoReviewSlot(slot.withTime(660).withEnabled(false));
        TodoReviewSlot saved = value.copy().getTodoReviewSlots().get(1);
        assertEquals(slot.id, saved.id);
        assertEquals(660, saved.minuteOfDay);
        assertFalse(saved.enabled);
        value.removeTodoReviewSlot(slot.id);
        assertEquals(1, value.getTodoReviewSlots().size());
    }

    @Test public void copyingDoesNotShareMutableSlotList() {
        ProactiveSettings value = settings();
        ProactiveSettings copy = value.copy();
        copy.addTodoReviewSlot(500);
        assertEquals(1, value.getTodoReviewSlots().size());
    }

    @Test(expected = IllegalArgumentException.class) public void requiresAtLeastOneSlot() {
        settings().setTodoReviewSlots(Collections.emptyList());
    }

    @Test(expected = IllegalArgumentException.class) public void cannotRemoveLastSlot() {
        settings().removeTodoReviewSlot("legacy-midday");
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsSeventhSlot() {
        ProactiveSettings value = settings();
        for (int i = 0; i < 6; i++) value.addTodoReviewSlot(500 + i);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsDuplicateIds() {
        settings().setTodoReviewSlots(Arrays.asList(new TodoReviewSlot("a", 1, true),
                new TodoReviewSlot("a", 2, true)));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsInvalidMinute() {
        TodoReviewSlot.create(1440);
    }

    @Test public void reviewTimeIsIndependentOfAttentionRoutines() {
        ProactiveSettings value = settings();
        value.morningEnabled = value.middayEnabled = value.afternoonEnabled = value.eveningEnabled = false;
        value.setTodoReviewSlots(Collections.singletonList(new TodoReviewSlot("custom", 660, true)));
        assertEquals(Collections.singletonList(660), ProactiveSchedulePlan.minutes(value));
        assertNull(ProactiveSchedulePlan.attentionRoutineAt(value, 660));
        assertTrue(value.hasTodoReviewAt(660));
        value.middayMinute = 800;
        assertEquals(Collections.singletonList(660), ProactiveSchedulePlan.minutes(value));
    }

    @Test public void sameMinuteAttentionAndReviewCoalesce() {
        ProactiveSettings value = settings();
        value.morningEnabled = value.afternoonEnabled = value.eveningEnabled = false;
        value.setTodoReviewSlots(Arrays.asList(new TodoReviewSlot("a", 750, true),
                new TodoReviewSlot("disabled", 800, false)));
        assertEquals(Collections.singletonList(750), ProactiveSchedulePlan.minutes(value));
        assertEquals(ProactiveRoutine.MIDDAY, ProactiveSchedulePlan.attentionRoutineAt(value, 750));
        assertTrue(value.hasTodoReviewAt(750));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsDuplicateTimesEvenWhenDisabled() {
        settings().setTodoReviewSlots(Arrays.asList(new TodoReviewSlot("a", 750, true),
                new TodoReviewSlot("b", 750, false)));
    }

    @Test public void rejectedDuplicateEditLeavesSlotsUnchanged() {
        ProactiveSettings value = settings();
        TodoReviewSlot slot = value.addTodoReviewSlot(600);
        try {
            value.updateTodoReviewSlot(slot.withTime(750));
            fail("duplicate time accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals(600, value.getTodoReviewSlots().get(1).minuteOfDay);
        }
    }

    @Test public void switchesDisableOnlyTheirOwnTriggerSources() {
        ProactiveSettings value = settings();
        value.todoReviewEnabled = false;
        assertEquals(4, ProactiveSchedulePlan.minutes(value).size());
        value.todoReviewEnabled = true;
        value.attentionTriggerEnabled = false;
        assertEquals(Collections.singletonList(750), ProactiveSchedulePlan.minutes(value));
        assertNull(ProactiveSchedulePlan.attentionRoutineAt(value, 750));
    }

    @Test public void staleAndLegacyWorkCannotRunOrReschedule() {
        ProactiveSettings value = settings();
        assertTrue(ProactiveSchedulePlan.isCurrent(value, "current", 750, false));
        assertFalse(ProactiveSchedulePlan.isCurrent(value, "old", 750, false));
        assertFalse(ProactiveSchedulePlan.isCurrent(value, null, 750, false));
        assertFalse(ProactiveSchedulePlan.isCurrent(value, "", 750, false));
        value.enabled = false;
        assertFalse(ProactiveSchedulePlan.isCurrent(value, "current", 750, false));
    }

    @Test public void deletedOrDisabledTriggerIsRejectedEvenWithSameGeneration() {
        ProactiveSettings value = settings();
        value.attentionTriggerEnabled = false;
        TodoReviewSlot added = value.addTodoReviewSlot(650);
        assertTrue(ProactiveSchedulePlan.isCurrent(value, "current", 650, false));
        value.updateTodoReviewSlot(added.withEnabled(false));
        assertFalse(ProactiveSchedulePlan.isCurrent(value, "current", 650, false));
        value.removeTodoReviewSlot(added.id);
        assertFalse(ProactiveSchedulePlan.isCurrent(value, "current", 650, false));
    }

    @Test public void manualReviewDoesNotRequireNoonOrEnabledDailySlots() {
        ProactiveSettings value = settings();
        value.middayEnabled = false;
        value.setTodoReviewSlots(Collections.singletonList(new TodoReviewSlot("a", 660, false)));
        assertTrue(ProactiveSchedulePlan.isCurrent(value, "current", -1, true));
        value.todoReviewEnabled = false;
        assertTrue(ProactiveSchedulePlan.isCurrent(value, "current", -1, true));
        value.attentionTriggerEnabled = false;
        assertFalse(ProactiveSchedulePlan.isCurrent(value, "current", -1, true));
    }
}
