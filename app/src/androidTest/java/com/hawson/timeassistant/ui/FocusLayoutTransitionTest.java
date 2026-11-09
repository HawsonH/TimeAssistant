package com.hawson.timeassistant.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.databinding.PageFocusBinding;
import com.hawson.timeassistant.design.AppearancePreferences;
import com.hawson.timeassistant.design.MotionSpec;
import com.hawson.timeassistant.domain.FocusState;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Destructive workflow fixtures are emulator-only; never run against account-bearing phones. */
@RunWith(AndroidJUnit4.class)
public class FocusLayoutTransitionTest {
    private Context context;
    private AppRepository repository;
    private MotionSpec.Mode previous;

    @Before public void prepare() {
        assertTrue("Emulator only", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"));
        context = ApplicationProvider.getApplicationContext();
        assertEquals("com.hawson.timeassistant.interaction.qa", context.getPackageName());
        assertTrue(((TimeAssistantApplication) context).getAiServices().profiles.list().isEmpty());
        assertTrue(((TimeAssistantApplication) context).getAiServices().accounts.accounts().isEmpty());
        repository = AppRepository.get(context);
        repository.clearAll();
        repository.saveSettings(repository.getSettings().setFocusAutomation(false, false, false, false)
                .setFocusEndSignals(false, false));
        previous = new AppearancePreferences(context).motionMode();
        new AppearancePreferences(context).setMotionMode(MotionSpec.Mode.FULL);
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().putInt("seen_version", 1)
                .putBoolean("intro_pending", false).putBoolean("context_enabled", false).commit();
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().putInt("last_page", 2)
                .putBoolean("focus_lockscreen_settings_confirmed_v2", true).commit();
    }

    @After public void restore() {
        if (repository != null) repository.clearAll();
        if (previous != null) new AppearancePreferences(context).setMotionMode(previous);
    }

    @Test public void startAndCancelKeepRootAndMoveThroughIntermediatePositions() throws Exception {
        try (ActivityScenario<MainActivity> screen = open()) {
            assertTrue("FULL-motion evidence requires system animator scale > 0", MotionSpec.full(context));
            List<Integer> forward = sample(screen, true, "start");
            assertIntermediate(forward, false);
            String session = repository.getCurrentFocus().getSessionId();
            screen.onActivity(a -> {
                Object timer = a.countDownTimer;
                View root = a.pageContainer.getChildAt(0);
                a.renderCurrentPage();
                root.post(() -> { assertSame(root, a.pageContainer.getChildAt(0)); assertSame(timer, a.countDownTimer); });
            });
            idle();
            List<Integer> reverse = sample(screen, false, "cancel");
            assertIntermediate(reverse, true);
            assertFalse(session.equals(repository.getCurrentFocus().getSessionId()));
            assertTrue(repository.getFocusHistory().isEmpty());
        }
    }

    @Test public void modeSwitchAndVerticalAdjustmentReuseClockAndPersistOnlyOnRelease() {
        try (ActivityScenario<MainActivity> screen = open()) {
            screen.onActivity(a -> a.findViewById(R.id.focusModeFocus).performClick()); idle();
            View[] original = {null}; int[] coordinates = {0, 0};
            screen.onActivity(a -> {
                original[0] = a.findViewById(R.id.timerRing);
                coordinates[0] = original[0].getTop(); coordinates[1] = original[0].getHeight();
                a.findViewById(R.id.focusModeShort).performClick();
                a.findViewById(R.id.focusModeLong).performClick();
                a.findViewById(R.id.focusModeFocus).performClick();
            }); idle();
            int before = repository.getSettings().getFocusDurationMinutes();
            screen.onActivity(a -> {
                View ring = a.findViewById(R.id.timerRing);
                assertSame(original[0], ring); assertEquals(coordinates[0], ring.getTop());
                assertEquals(coordinates[1], ring.getHeight());
                assertEquals(1f, ring.getAlpha(), 0); assertEquals(0f, ring.getTranslationY(), 0);
                float delta = 48 * a.getResources().getDisplayMetrics().density;
                dialTouch(ring, android.view.MotionEvent.ACTION_DOWN, delta * 2);
                dialTouch(ring, android.view.MotionEvent.ACTION_MOVE, delta);
                assertEquals(before, repository.getSettings().getFocusDurationMinutes());
                a.renderCurrentPage();
            }); idle();
            screen.onActivity(a -> dialTouch(original[0], android.view.MotionEvent.ACTION_UP,
                    48 * a.getResources().getDisplayMetrics().density)); idle();
            assertEquals(Math.min(180, before + 2), repository.getSettings().getFocusDurationMinutes());
            assertTrue(repository.getFocusHistory().isEmpty());
            assertEquals(FocusState.State.IDLE, repository.getCurrentFocus().getState());
            screen.recreate(); idle();
            screen.onActivity(a -> assertTrue(a.findViewById(R.id.timerRing).getContentDescription().toString()
                    .contains(Math.min(180, before + 2) + " 分")));
        }
    }

    private static void dialTouch(View ring, int action, float y) {
        long now = SystemClock.uptimeMillis();
        android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now, action, 100, y, 0);
        ring.dispatchTouchEvent(event); event.recycle();
    }

    @Test public void permissionSectionNavigatesAndBackgroundReturnKeepsPreferenceDraft() {
        try (ActivityScenario<MainActivity> screen = open()) {
            screen.onActivity(TimeAssistantActivity::openSettings); idle();
            WorkflowDriver.click("系统权限与提醒");
            View[] root = {null};
            screen.onActivity(a -> {
                root[0] = a.pageContainer.getChildAt(0);
                assertEquals("系统权限与提醒", ((android.widget.TextView) a.findViewById(R.id.settingsTitle)).getText().toString());
                assertNotNull(WorkflowDriver.text("后台与电池限制"));
                assertNotNull(WorkflowDriver.text("麦克风"));
            });
            screen.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            screen.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); idle();
            screen.onActivity(a -> assertSame(root[0], a.pageContainer.getChildAt(0)));
            WorkflowDriver.clickId(R.id.backButton);
            WorkflowDriver.click("安排偏好与通知");
            android.widget.EditText[] draft = {null};
            screen.onActivity(a -> {
                draft[0] = a.findViewById(R.id.availableStartInput);
                draft[0].setText("08:45");
            });
            screen.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            screen.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); idle();
            screen.onActivity(a -> {
                assertSame(draft[0], a.findViewById(R.id.availableStartInput));
                assertEquals("08:45", draft[0].getText().toString());
            });
        }
    }

    @Test public void pauseResumeAndRepositoryRefreshKeepSessionRootAndRingPosition() {
        try (ActivityScenario<MainActivity> screen = open()) {
            screen.onActivity(a -> a.findViewById(R.id.timerButton).performClick()); idle();
            String session = repository.getCurrentFocus().getSessionId();
            View[] root = {null}; int[] top = {0};
            screen.onActivity(a -> { root[0] = a.pageContainer.getChildAt(0); top[0] = a.findViewById(R.id.timerRing).getTop();
                a.findViewById(R.id.timerButton).performClick(); });
            idle();
            assertEquals(FocusState.State.PAUSED, repository.getCurrentFocus().getState());
            screen.onActivity(a -> {
                assertSame(root[0], a.pageContainer.getChildAt(0));
                assertEquals(top[0], a.findViewById(R.id.timerRing).getTop());
                assertEquals(View.GONE, a.findViewById(R.id.focusModes).getVisibility());
                a.findViewById(R.id.timerButton).performClick();
            }); idle();
            assertEquals(session, repository.getCurrentFocus().getSessionId());
            assertEquals(FocusState.State.RUNNING, repository.getCurrentFocus().getState());
            screen.onActivity(a -> { assertSame(root[0], a.pageContainer.getChildAt(0));
                assertEquals(top[0], a.findViewById(R.id.timerRing).getTop()); });
            assertTrue(repository.getFocusHistory().isEmpty());
        }
    }

    @Test public void reducedAndOffHaveNoSpatialInterpolation() throws Exception {
        for (MotionSpec.Mode mode : new MotionSpec.Mode[]{MotionSpec.Mode.REDUCED, MotionSpec.Mode.OFF}) {
            repository.clearCurrentFocus();
            new AppearancePreferences(context).setMotionMode(mode);
            try (ActivityScenario<MainActivity> screen = open()) {
                List<Integer> positions = sample(screen, true, mode.name());
                int first = positions.get(0), last = positions.get(positions.size() - 1);
                for (int position : positions) assertTrue(position == first || position == last);
                screen.onActivity(a -> assertEquals(View.GONE, a.findViewById(R.id.focusModes).getVisibility()));
            }
        }
    }

    @Test public void fourPaletteLightAndDarkTransitionsKeepEndpoints() throws Exception {
        android.content.SharedPreferences ui = context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0);
        String previousTheme = AppTheme.selected(ui);
        AppearancePreferences appearance = new AppearancePreferences(context);
        int previousNight = appearance.nightMode();
        try {
            for (String theme : new String[]{AppTheme.VIOLET, AppTheme.OCEAN, AppTheme.FOREST, AppTheme.CLAY}) {
                ui.edit().putString(AppTheme.PREFERENCE_KEY, theme).commit();
                for (int night : new int[]{androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES}) {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> appearance.setNightMode(night));
                    repository.clearCurrentFocus();
                    try (ActivityScenario<MainActivity> screen = open()) {
                        List<Integer> positions = sample(screen, true, theme + "-" + night);
                        assertIntermediate(positions, false);
                        screen.onActivity(a -> assertEquals(View.GONE, a.findViewById(R.id.focusModes).getVisibility()));
                    }
                }
            }
        } finally {
            ui.edit().putString(AppTheme.PREFERENCE_KEY, previousTheme).commit();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> appearance.setNightMode(previousNight));
        }
    }

    @Test public void systemDisabledFocusLayoutChangesSynchronously() {
        org.junit.Assume.assumeTrue("Run separately with system animations disabled",
                "0".equals(android.provider.Settings.Global.getString(context.getContentResolver(), "animator_duration_scale")));
        try (ActivityScenario<MainActivity> screen = open()) {
            screen.onActivity(a -> {
                assertFalse(MotionSpec.full(a));
                PageFocusBinding b = PageFocusBinding.bind(a.pageContainer.getChildAt(0));
                FocusLayoutTransition transition = new FocusLayoutTransition(b);
                transition.setStarted(false, false); transition.setStarted(true, true);
                assertEquals(View.GONE, b.focusModes.getVisibility());
                assertEquals(View.VISIBLE, b.endFocusButton.getVisibility());
                transition.setStarted(false, true);
                assertEquals(View.VISIBLE, b.focusModes.getVisibility());
                assertEquals(View.INVISIBLE, b.endFocusButton.getVisibility());
                assertEquals(0f, b.timerRing.getTranslationY(), 0);
            });
        }
    }

    @Test public void interruptionAndStopUseLatestEndpointWithoutResidualTransforms() {
        try (ActivityScenario<MainActivity> screen = open()) {
            PageFocusBinding[] binding = {null}; FocusLayoutTransition[] transition = {null};
            screen.onActivity(a -> {
                binding[0] = PageFocusBinding.bind(a.pageContainer.getChildAt(0));
                transition[0] = new FocusLayoutTransition(binding[0]);
                transition[0].setStarted(false, false); transition[0].setStarted(true, true);
            });
            SystemClock.sleep(70);
            screen.onActivity(a -> {
                transition[0].setStarted(false, true); transition[0].setStarted(true, true);
                transition[0].stopMotion();
                assertEquals(View.GONE, binding[0].focusModes.getVisibility());
                assertEquals(View.VISIBLE, binding[0].endFocusButton.getVisibility());
                assertEquals(0f, binding[0].timerRing.getTranslationY(), 0);
                assertEquals(1f, binding[0].timerRing.getAlpha(), 0);
                transition[0].setStarted(false, true); transition[0].stopMotion();
                assertEquals(View.VISIBLE, binding[0].focusModes.getVisibility());
                assertEquals(View.INVISIBLE, binding[0].endFocusButton.getVisibility());
            });
        }
    }

    @Test public void reentryRecreationAndBackgroundUseCurrentStateWithoutNewSession() {
        try (ActivityScenario<MainActivity> screen = open()) {
            screen.onActivity(a -> a.findViewById(R.id.timerButton).performClick()); idle();
            String session = repository.getCurrentFocus().getSessionId();
            screen.onActivity(a -> a.showPage(1)); idle();
            screen.onActivity(a -> a.showPage(2)); idle();
            screen.recreate(); idle();
            screen.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            screen.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); idle();
            screen.onActivity(a -> {
                assertEquals(View.GONE, a.findViewById(R.id.focusModes).getVisibility());
                assertEquals(0f, a.findViewById(R.id.timerRing).getTranslationY(), 0);
                assertNotNull(a.countDownTimer);
            });
            assertEquals(session, repository.getCurrentFocus().getSessionId());
            assertTrue(repository.getFocusHistory().isEmpty());
        }
    }

    @Test public void controlsDoNotOverlapAndReadyStateRemainsAccessible() {
        try (ActivityScenario<MainActivity> screen = open()) {
            screen.onActivity(a -> {
                PageFocusBinding b = PageFocusBinding.bind(a.pageContainer.getChildAt(0));
                if (context.getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    int[] ring = new int[2], task = new int[2];
                    b.timerRing.getLocationOnScreen(ring); b.focusTaskTitle.getLocationOnScreen(task);
                    assertTrue(ring[0] + b.timerRing.getWidth() <= task[0]);
                } else {
                    assertTrue(b.timerRing.getTop() >= b.focusModes.getBottom());
                    assertTrue(b.focusTaskTitle.getTop() >= b.timerRing.getBottom());
                }
                assertTrue(b.focusActions.getTop() >= b.focusTaskTitle.getBottom());
                assertTrue(b.timerButton.getHeight() >= 48 * context.getResources().getDisplayMetrics().density);
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, b.focusModes.getImportantForAccessibility());
            });
        }
    }

    @Test public void endReturnsPreparedLayoutAndDuplicateSettlementDoesNotAddHistory() throws Exception {
        try (ActivityScenario<MainActivity> screen = open()) {
            screen.onActivity(a -> a.findViewById(R.id.timerButton).performClick()); idle();
            FocusState running = repository.getCurrentFocus();
            View[] root = {null};
            screen.onActivity(a -> {
                root[0] = a.pageContainer.getChildAt(0);
                repository.endCurrentFocus(running.getSessionId(), System.currentTimeMillis(), false, "");
                a.renderCurrentPage();
            }); idle();
            repository.endCurrentFocus(running.getSessionId(), System.currentTimeMillis(), false, "");
            assertEquals(1, repository.getFocusHistory().size());
            screen.onActivity(a -> {
                assertSame(root[0], a.pageContainer.getChildAt(0));
                assertEquals(View.VISIBLE, a.findViewById(R.id.focusModes).getVisibility());
                assertEquals(View.INVISIBLE, a.findViewById(R.id.endFocusButton).getVisibility());
            });
        }
    }

    private ActivityScenario<MainActivity> open() {
        ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class);
        if ("landscape".equals(InstrumentationRegistry.getArguments().getString("uiVariant"))) {
            screen.onActivity(a -> a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            idle();
        }
        screen.onActivity(a -> a.showPage(2)); idle(); return screen;
    }

    private List<Integer> sample(ActivityScenario<MainActivity> screen, boolean start, String name) throws Exception {
        List<Integer> positions = new ArrayList<>(); CountDownLatch complete = new CountDownLatch(1);
        screen.onActivity(a -> {
            View root = a.pageContainer.getChildAt(0);
            View ring = a.findViewById(a.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    ? R.id.focusTaskTitle : R.id.timerRing);
            positions.add(ring.getTop());
            ViewTreeObserver.OnDrawListener sampler = () -> {
                positions.add(ring.getTop());
                View primary = a.findViewById(R.id.timerButton), slot = a.findViewById(R.id.endFocusSlot);
                assertTrue("Action bounds must never overlap", primary.getRight() <= slot.getLeft());
            };
            root.getViewTreeObserver().addOnDrawListener(sampler);
            if (start) a.findViewById(R.id.timerButton).performClick();
            else { repository.cancelCurrentFocus(repository.getCurrentFocus().getSessionId(), System.currentTimeMillis());
                a.renderCurrentPage(); }
            root.postDelayed(() -> {
                root.getViewTreeObserver().removeOnDrawListener(sampler);
                assertSame("No page rebuild during timer state changes", root, a.pageContainer.getChildAt(0));
                positions.add(ring.getTop()); complete.countDown();
            }, 650);
        });
        assertTrue(complete.await(5, TimeUnit.SECONDS));
        File directory = new File(context.getExternalFilesDir(null), "focus-transition-frames");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + "-positions.txt"))) {
            output.write(positions.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        return positions;
    }

    @Test public void captureCompositorFramesSeparatelyFromTimingAssertions() throws Exception {
        try (ActivityScenario<MainActivity> screen = open()) {
            for (String name : new String[]{"start", "return"}) {
                View[] focusRoot = {null};
                screen.onActivity(a -> focusRoot[0] = a.pageContainer);
                WorkflowDriver.awaitCondition("focused timer window before screenshot capture",
                        () -> focusRoot[0].hasWindowFocus());
                screen.onActivity(a -> {
                    assertTrue(a.pageContainer.hasWindowFocus());
                    if (name.equals("start")) a.findViewById(R.id.timerButton).performClick();
                    else { repository.cancelCurrentFocus(repository.getCurrentFocus().getSessionId(), System.currentTimeMillis());
                        a.renderCurrentPage(); }
                });
                List<Bitmap> compositor = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    SystemClock.sleep(20);
                    Bitmap frame = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                    assertNotNull(frame); compositor.add(frame);
                }
                saveFrames(name, compositor);
                idle();
            }
        }
    }

    private void saveFrames(String name, List<Bitmap> compositor) throws Exception {
        File directory = new File(context.getExternalFilesDir(null), "focus-transition-frames");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        for (int i = 0; i < compositor.size(); i++) {
            Bitmap frame = compositor.get(i);
            try (FileOutputStream output = new FileOutputStream(new File(directory, name + "-screen-" + i + ".png"))) {
                assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally { frame.recycle(); }
        }
    }

    private static void assertIntermediate(List<Integer> positions, boolean increasing) {
        int start = positions.get(0), end = positions.get(positions.size() - 1);
        assertTrue("Expected ring to move: " + positions, increasing ? end > start : end < start);
        assertTrue("Expected real intermediate layout frames: " + positions,
                positions.stream().anyMatch(y -> y > Math.min(start, end) && y < Math.max(start, end)));
        int previous = start;
        for (int position : positions) {
            assertTrue("Layout must not jump backwards: " + positions,
                    increasing ? position >= previous : position <= previous);
            previous = position;
        }
    }

    private static void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(400);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
