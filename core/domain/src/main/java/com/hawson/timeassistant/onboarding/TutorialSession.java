package com.hawson.timeassistant.onboarding;

/** Introduction state only; tutorials never mutate business data or grant permissions. */
public final class TutorialSession {
    public static final int VERSION = 1;
    public static final int STEP_COUNT = 3;
    private int step;

    public TutorialSession(int restoredStep) {
        step = Math.max(0, Math.min(STEP_COUNT - 1, restoredStep));
    }

    public int step() { return step; }
    public boolean isLastStep() { return step == STEP_COUNT - 1; }

    public boolean next() {
        if (isLastStep()) return false;
        step++;
        return true;
    }

    public static boolean shouldAutoStart(int seenVersion, boolean existingUser) {
        return seenVersion < VERSION && !existingUser;
    }
}
