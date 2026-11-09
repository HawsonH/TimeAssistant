package com.hawson.timeassistant.ui;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.databinding.PageFocusBinding;
import com.hawson.timeassistant.design.MotionSpec;
import com.hawson.timeassistant.testing.AiSettingsHarnessActivity;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Presentation-only phone check: no repository, credentials, permissions or timer writes. */
@RunWith(AndroidJUnit4.class)
public class FocusLayoutPhoneSmokeTest {
    @Test public void isolatedPresentationMovesAndRestoresWithoutRunningTimer() {
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals("com.hawson.timeassistant.interaction.qa", context.getPackageName());
        WorkflowDriver.requireUnlockedDisplay(context);
        PageFocusBinding[] binding = {null}; FocusLayoutTransition[] motion = {null};
        List<Integer> positions = new ArrayList<>();
        try (ActivityScenario<AiSettingsHarnessActivity> screen = ActivityScenario.launch(AiSettingsHarnessActivity.class)) {
            screen.onActivity(a -> {
                binding[0] = PageFocusBinding.inflate(a.getLayoutInflater());
                binding[0].focusModeLabel.setText("专注");
                binding[0].focusTaskTitle.setText("本地动效验收");
                binding[0].timerButton.setText("开始");
                binding[0].timerRing.setTimer(1500000, 1500000, "准备开始");
                a.setContentView(binding[0].getRoot());
                motion[0] = new FocusLayoutTransition(binding[0]); motion[0].setStarted(false, false);
            });
            SystemClock.sleep(500);
            screen.onActivity(a -> {
                assertTrue(binding[0].getRoot().hasWindowFocus());
                positions.add(binding[0].timerRing.getTop());
                binding[0].getRoot().getViewTreeObserver().addOnDrawListener(() -> {
                    positions.add(binding[0].timerRing.getTop());
                    assertTrue(binding[0].timerButton.getRight() <= binding[0].endFocusSlot.getLeft());
                });
                motion[0].setStarted(true, true);
            });
            SystemClock.sleep(700);
            screen.onActivity(a -> {
                int end = binding[0].timerRing.getTop(), start = positions.get(0);
                assertTrue(end < start);
                if (MotionSpec.full(context)) assertTrue("Phone must render intermediate bounds: " + positions,
                        positions.stream().anyMatch(y -> y > end && y < start));
                assertEquals(View.GONE, binding[0].focusModes.getVisibility());
                motion[0].setStarted(false, true);
            });
            SystemClock.sleep(500);
            screen.onActivity(a -> {
                motion[0].stopMotion();
                assertEquals(View.VISIBLE, binding[0].focusModes.getVisibility());
                assertEquals(View.INVISIBLE, binding[0].endFocusButton.getVisibility());
                assertEquals(0f, binding[0].timerRing.getTranslationY(), 0);
            });
        }
    }
}
