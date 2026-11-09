package com.hawson.timeassistant;

import android.content.Context;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.hawson.timeassistant.testing.TutorialHarnessActivity;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TutorialFlowTest {
    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        assertTrue(context.getPackageName().endsWith(".qa"));
        context.getSharedPreferences(TutorialHarnessActivity.TEST_PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @After public void tearDown() {
        context.deleteSharedPreferences(TutorialHarnessActivity.TEST_PREFS);
    }

    @Test public void skipDisablesContextGuidesAndReplayStartsAtFirstStep() {
        try (ActivityScenario<TutorialHarnessActivity> scenario =
                     ActivityScenario.launch(TutorialHarnessActivity.class)) {
            onView(withText("说出安排，确认计划，专注当下。")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("跳过教程")).perform(click());
            assertFalse(context.getSharedPreferences(TutorialHarnessActivity.TEST_PREFS, 0)
                    .getBoolean("context_enabled", true));
            scenario.onActivity(TutorialHarnessActivity::replay);
            onView(withText("说出安排，确认计划，专注当下。")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("跳过教程")).perform(click());
        }
    }

    @Test public void recreationResumesSavedStepAndFinishIsVersioned() {
        try (ActivityScenario<TutorialHarnessActivity> scenario =
                     ActivityScenario.launch(TutorialHarnessActivity.class)) {
            scenario.recreate();
            onView(withText("说出安排，确认计划，专注当下。")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("开始使用")).perform(click());
            assertEquals(1, context.getSharedPreferences(TutorialHarnessActivity.TEST_PREFS, 0)
                    .getInt("seen_version", 0));
            assertTrue(context.getSharedPreferences(TutorialHarnessActivity.TEST_PREFS, 0)
                    .getBoolean("context_enabled", false));
        }
    }

    @Test public void existingUsersAreNotForcedIntoTutorial() {
        Intent launch = new Intent(context, TutorialHarnessActivity.class)
                .putExtra("existing_user", true);
        try (ActivityScenario<TutorialHarnessActivity> scenario = ActivityScenario.launch(launch)) {
            onView(withText("教程测试锚点")).check(matches(isDisplayed()));
            assertFalse(context.getSharedPreferences(TutorialHarnessActivity.TEST_PREFS, 0)
                    .getBoolean("intro_pending", false));
            scenario.onActivity(TutorialHarnessActivity::replay);
            onView(withText("说出安排，确认计划，专注当下。")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("跳过教程")).perform(click());
        }
    }
}
