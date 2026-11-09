package com.hawson.timeassistant.ui;

import android.content.Context;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.hawson.timeassistant.DeviceFixtureSafety;
import com.hawson.timeassistant.design.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Run explicitly with the disposable emulator's animator_duration_scale set to zero. */
@RunWith(AndroidJUnit4.class)
public class SystemMotionDisabledTest {
    @Test public void systemOffOverridesFullModeAndCompletesPresentationSynchronously() {
        Context context = ApplicationProvider.getApplicationContext();
        DeviceFixtureSafety.requireIsolatedTarget(context);
        assertEquals("0", android.provider.Settings.Global.getString(context.getContentResolver(), "animator_duration_scale"));
        AppearancePreferences appearance = new AppearancePreferences(context);
        MotionSpec.Mode previous = appearance.motionMode(); appearance.setMotionMode(MotionSpec.Mode.FULL);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(0, MotionSpec.duration(activity, 240)); assertFalse(MotionSpec.full(activity));
                FrameLayout host = new FrameLayout(activity); activity.setContentView(host);
                float[] frame = {0}; int[] completions = {0};
                MotionHandle handle = MotionSpec.animate(host, 240, f -> frame[0] = f, () -> completions[0]++);
                assertFalse(handle.isRunning()); assertEquals(1f, frame[0], 0); assertEquals(1, completions[0]);
                host.addView(new TextView(activity));
                TextView incoming = new TextView(activity); incoming.setAlpha(.6f); incoming.setTranslationX(23);
                ContentTransition.replace(host, incoming, 1, true);
                assertEquals(1, host.getChildCount()); assertSame(incoming, host.getChildAt(0));
                MotionSpec.enter(incoming); MotionSpec.cancelTree(host);
                assertEquals(.6f, incoming.getAlpha(), .001f); assertEquals(23f, incoming.getTranslationX(), .001f);
                androidx.appcompat.app.AlertDialog dialog = new SoftAlertDialogBuilder(activity).setMessage("local fixture").create();
                assertEquals(0, dialog.getWindow().getAttributes().windowAnimations); dialog.dismiss();
            });
        } finally { appearance.setMotionMode(previous); }
    }
}
