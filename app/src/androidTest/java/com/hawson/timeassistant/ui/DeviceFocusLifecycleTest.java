package com.hawson.timeassistant.ui;

import android.app.NotificationManager;
import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.DeviceFixtureSafety;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.data.local.NotificationPrivacyStore;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.focus.FocusTimerService;
import org.junit.Test;
import static org.junit.Assert.*;

/** Short real-device lifecycle probes, using only the disposable device QA package. */
public class DeviceFocusLifecycleTest {
    @Test public void checkpointSurvivesExplicitProcessRestart() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        DeviceFixtureSafety.requireIsolatedTarget(context);
        AppRepository repository = AppRepository.get(context);
        SharedPreferences fixture = context.getSharedPreferences("device_focus_fixture", 0);
        String phase = InstrumentationRegistry.getArguments().getString("focusPhase", "");
        org.junit.Assume.assumeFalse(phase.isEmpty());
        if (phase.startsWith("seed-")) {
            repository.clearAll();
            FocusState state = FocusState.ready(null, FocusState.Mode.FOCUS, 300_000, 1)
                    .startOrResume(System.currentTimeMillis());
            if (phase.equals("seed-paused")) state = state.pause(System.currentTimeMillis());
            repository.setCurrentFocus(state);
            assertTrue(fixture.edit().putString("session", state.getSessionId())
                    .putLong("remaining", state.remainingAt(System.currentTimeMillis()))
                    .putLong("saved", System.currentTimeMillis()).commit());
            return;
        }
        FocusState restored = repository.getCurrentFocus();
        assertEquals(fixture.getString("session", "missing"), restored.getSessionId());
        if (phase.equals("verify-paused")) {
            assertEquals(FocusState.State.PAUSED, restored.getState());
            assertEquals(fixture.getLong("remaining", -1), restored.remainingAt(System.currentTimeMillis()));
        } else {
            assertEquals(FocusState.State.RUNNING, restored.getState());
            long expected = fixture.getLong("remaining", -1)
                    - (System.currentTimeMillis() - fixture.getLong("saved", 0));
            assertTrue("Running recovery drift", Math.abs(expected - restored.remainingAt(System.currentTimeMillis())) < 1500);
        }
        repository.clearAll(); fixture.edit().clear().commit();
    }

    @Test public void screenOffTimerSettlesOnceWithoutCompletingTask() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("screenOff")));
        verifyBackgroundCompletion(true);
    }

    @Test public void awakeBackgroundTimerSettlesOnceWithoutCompletingTask() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("awakeBackground")));
        verifyBackgroundCompletion(false);
    }

    private void verifyBackgroundCompletion(boolean screenOff) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        DeviceFixtureSafety.requireIsolatedTarget(context);
        WorkflowDriver.requireUnlockedDisplay(context);
        AppRepository repository = AppRepository.get(context);
        repository.clearAll();
        repository.saveSettings(repository.getSettings().setFocusEndSignals(false, false)
                .setFocusAutomation(false, false, false, false));
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().putInt("seen_version", 1)
                .putBoolean("intro_pending", false).putBoolean("context_enabled", false).commit();
        new NotificationPrivacyStore(context).setHideTaskContent(true);
        TaskItem task = repository.upsertTask(new TaskItem("device-private-fixture"));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                FocusState state = FocusState.ready(task.getId(), FocusState.Mode.FOCUS, 12_000, 1)
                        .startOrResume(System.currentTimeMillis());
                repository.setCurrentFocus(state);
                FocusTimerService.sync(activity, state);
                activity.moveTaskToBack(true);
            });
            SystemClock.sleep(1500);
            android.service.notification.StatusBarNotification[] notifications = context.getSystemService(NotificationManager.class).getActiveNotifications();
            assertTrue("Active focus notification missing", notifications.length > 0);
            for (android.service.notification.StatusBarNotification notification : notifications) {
                for (String key : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TEXT,
                        Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT,
                        Notification.EXTRA_TITLE_BIG, Notification.EXTRA_SUMMARY_TEXT}) {
                    CharSequence value = notification.getNotification().extras.getCharSequence(key);
                    assertFalse("Notification task title leaked through " + key,
                            value != null && value.toString().contains("device-private-fixture"));
                }
            }
            if (screenOff) {
                shell("input keyevent 223");
                SystemClock.sleep(1500);
                assertFalse("Display did not turn off", context.getSystemService(PowerManager.class).isInteractive());
            }
            long deadline = SystemClock.elapsedRealtime() + 25_000;
            while (repository.getFocusHistory().isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(500);
            assertEquals("Background completion missing", 1, repository.getFocusHistory().size());
            assertEquals(12_000, repository.getFocusHistory().get(0).getDurationMillis());
            assertFalse(repository.getTask(task.getId()).getStatus().isTerminal());
            repository.completeCurrentFocusNaturally(System.currentTimeMillis());
            assertEquals(1, repository.getFocusHistory().size());
            if (!screenOff) assertTrue("Awake-only probe must not turn off the display",
                    context.getSystemService(PowerManager.class).isInteractive());
        } finally {
            if (screenOff) shell("input keyevent 224");
            repository.clearAll(); FocusTimerService.sync(context, repository.getCurrentFocus());
        }
    }
    private void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor ignored = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command)) { SystemClock.sleep(350); }
    }
}
