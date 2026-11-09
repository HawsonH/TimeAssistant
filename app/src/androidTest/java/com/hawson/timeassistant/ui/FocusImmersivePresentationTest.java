package com.hawson.timeassistant.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.databinding.PageFocusBinding;
import com.hawson.timeassistant.design.AppearancePreferences;
import com.hawson.timeassistant.design.MotionSpec;
import com.hawson.timeassistant.design.RingAmbientMotion;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.testing.AiSettingsHarnessActivity;
import com.hawson.timeassistant.ui.widget.TimerRingView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Presentation fixtures only. No repository, service, real session or network access. */
@RunWith(AndroidJUnit4.class)
public class FocusImmersivePresentationTest {
    @Test public void phoneSafeRunningPresentationAndBreathingCycle() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals("com.hawson.timeassistant.interaction.qa", context.getPackageName());
        WorkflowDriver.requireUnlockedDisplay(context);
        FocusPageController controller = new FocusPageController();
        PageFocusBinding[] binding = {null};
        FocusState running = FocusState.ready(null, FocusState.Mode.FOCUS, 1_500_000, 0)
                .startOrResume(System.currentTimeMillis());
        List<Integer> positions = new ArrayList<>();
        try (ActivityScenario<AiSettingsHarnessActivity> screen = ActivityScenario.launch(AiSettingsHarnessActivity.class)) {
            screen.onActivity(a -> {
                binding[0] = controller.inflate(a.getLayoutInflater());
                populate(binding[0], false);
                controller.setStarted(false, false);
                a.setContentView(binding[0].getRoot());
            });
            idle();
            screen.onActivity(a -> {
                positions.add(binding[0].timerRing.getTop());
                binding[0].getRoot().getViewTreeObserver().addOnDrawListener(() ->
                        positions.add(binding[0].timerRing.getTop()));
                controller.setStarted(true, true);
                controller.updatePresentation(running, true);
                populate(binding[0], true);
                assertFalse(binding[0].timerRing.getContentDescription().toString().contains("调整时间"));
                assertFalse(binding[0].focusTaskTitle.isClickable());
            });
            idle();
            screen.onActivity(a -> {
                assertEquals(View.GONE, binding[0].focusModes.getVisibility());
                assertTrue(binding[0].timerButton.getRight() <= binding[0].endFocusSlot.getLeft());
                int end = binding[0].timerRing.getTop(), start = positions.get(0);
                if (MotionSpec.full(a)) {
                    assertTrue("Transition must capture intermediate positions: " + positions,
                            positions.stream().anyMatch(y -> y > Math.min(start, end) && y < Math.max(start, end)));
                    assertTrue(binding[0].timerRing.isAmbientRunning());
                }
            });
            List<Float> opacity = new ArrayList<>();
            for (int frame = 0; frame <= 4; frame++) {
                final int index = frame;
                screen.onActivity(a -> {
                    opacity.add(binding[0].timerRing.getAmbientOpacity());
                    capture(binding[0].getRoot(), "breathing-" + index);
                    assertEquals(1f, binding[0].timerRing.getScaleX(), 0);
                    assertEquals(1f, binding[0].timerRing.getAlpha(), 0);
                });
                if (frame < 4) SystemClock.sleep(RingAmbientMotion.PERIOD_MILLIS / 4);
            }
            if (MotionSpec.full(context)) assertTrue("A complete cycle must vary opacity: " + opacity,
                    java.util.Collections.max(opacity) - java.util.Collections.min(opacity) > .04f);
            screen.onActivity(a -> {
                float before = binding[0].timerRing.getAmbientOpacity();
                for (int i = 0; i < 20; i++) binding[0].timerRing.setTimer(1_490_000 - i, 1_500_000, "正在专注");
                assertEquals("Timer refresh must not restart ambient motion", before,
                        binding[0].timerRing.getAmbientOpacity(), .00001f);
                controller.updatePresentation(running.pause(System.currentTimeMillis()), true);
                assertFalse(binding[0].timerRing.isAmbientRunning());
            });
            SystemClock.sleep(220);
            screen.onActivity(a -> {
                assertEquals(RingAmbientMotion.REST_ALPHA, binding[0].timerRing.getAmbientOpacity(), .001f);
                controller.updatePresentation(running, true);
                if (MotionSpec.full(a)) assertTrue(binding[0].timerRing.isAmbientRunning());
                controller.stopMotion();
                binding[0].timerRing.setTimer(1_480_000, 1_500_000, "正在专注");
                assertFalse("Background refresh cannot restart motion", binding[0].timerRing.isAmbientRunning());
                controller.setStarted(false, true);
                controller.updatePresentation(FocusState.idle(), true);
            });
            idle();
            screen.onActivity(a -> {
                assertEquals(View.VISIBLE, binding[0].focusModes.getVisibility());
                assertFalse(binding[0].timerRing.isAmbientRunning());
                controller.clearBinding();
            });
        } finally { controller.clearBinding(); }
    }

    @Test public void reducedOffAndSystemDisabledDoNotRunAmbientMotion() {
        assertEmulator();
        Context context = ApplicationProvider.getApplicationContext();
        AppearancePreferences appearance = new AppearancePreferences(context);
        MotionSpec.Mode previous = appearance.motionMode();
        try {
            for (MotionSpec.Mode mode : new MotionSpec.Mode[]{MotionSpec.Mode.REDUCED, MotionSpec.Mode.OFF}) {
                appearance.setMotionMode(mode);
                try (ActivityScenario<AiSettingsHarnessActivity> screen = ActivityScenario.launch(AiSettingsHarnessActivity.class)) {
                    screen.onActivity(a -> {
                        PageFocusBinding b = PageFocusBinding.inflate(a.getLayoutInflater());
                        populate(b, true); a.setContentView(b.getRoot());
                        b.timerRing.setPresentationState(TimerRingView.PresentationState.RUNNING);
                        b.timerRing.setTimer(1_200_000, 1_500_000, "正在专注");
                        assertFalse(b.timerRing.isAmbientRunning());
                        assertEquals(RingAmbientMotion.REST_ALPHA, b.timerRing.getAmbientOpacity(), 0);
                    });
                }
            }
        } finally { appearance.setMotionMode(previous); }
    }

    @Test public void systemAnimationOffNeverStartsAmbientMotion() {
        Context context = ApplicationProvider.getApplicationContext();
        org.junit.Assume.assumeTrue("Run separately with system animations disabled", "0".equals(
                android.provider.Settings.Global.getString(context.getContentResolver(), "animator_duration_scale")));
        try (ActivityScenario<AiSettingsHarnessActivity> screen = ActivityScenario.launch(AiSettingsHarnessActivity.class)) {
            screen.onActivity(a -> {
                assertFalse(android.animation.ValueAnimator.areAnimatorsEnabled());
                PageFocusBinding b = PageFocusBinding.inflate(a.getLayoutInflater());
                populate(b, true); a.setContentView(b.getRoot());
                b.timerRing.setPresentationState(TimerRingView.PresentationState.RUNNING);
                assertFalse(b.timerRing.isAmbientRunning());
            });
        }
    }

    @Test public void layoutsAcrossWidthsFontsOrientationsAndPalettes() {
        try (ActivityScenario<AiSettingsHarnessActivity> screen = ActivityScenario.launch(AiSettingsHarnessActivity.class)) {
            screen.onActivity(a -> {
                for (String palette : new String[]{AppTheme.OCEAN, AppTheme.FOREST, AppTheme.CLAY, AppTheme.VIOLET})
                    for (boolean dark : new boolean[]{false, true})
                        for (int width : new int[]{320, 360, 412, 740})
                            for (float font : new float[]{1, 1.3f, 2}) {
                                Configuration config = new Configuration(a.getResources().getConfiguration());
                                config.fontScale = font;
                                config.orientation = width == 740 ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
                                config.screenWidthDp = width;
                                config.screenHeightDp = width == 740 ? 320 : 620;
                                config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                                        | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
                                Context themed = new ContextThemeWrapper(a.createConfigurationContext(config), AppTheme.styleFor(palette));
                                PageFocusBinding b = PageFocusBinding.inflate(LayoutInflater.from(themed));
                                populate(b, true);
                                b.focusTaskTitle.setText("完成项目复盘与接下来一周的任务安排，保留完整任务名称");
                                b.timerRing.setMotionActive(false);
                                b.timerRing.setPresentationState(TimerRingView.PresentationState.RUNNING);
                                b.timerRing.setTimer(10_800_000, 10_800_000, "正在专注");
                                new FocusLayoutTransition(b).setStarted(true, false);
                                int w = dp(themed, width), h = dp(themed, config.screenHeightDp);
                                b.getRoot().measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                                b.getRoot().layout(0, 0, w, h);
                                assertBounds(b.getRoot());
                                assertTrue(b.timerButton.getHeight() >= dp(themed, 48));
                                assertTrue(b.timerButton.getRight() <= b.endFocusSlot.getLeft());
                                assertTrue(b.timerRing.getWidth() <= dp(themed, 320));
                                assertNotNull(androidx.core.widget.ImageViewCompat.getImageTintList(b.focusSettingsButton));
                                assertEquals(themed.getColor(com.hawson.timeassistant.R.color.text_primary),
                                        androidx.core.widget.ImageViewCompat.getImageTintList(b.focusSettingsButton).getDefaultColor());
                                if (font == 2) assertDialInkInsideRing(b.timerRing);
                                capture(b.getRoot(), palette + "-" + dark + "-" + width + "-" + font);
                            }
            });
        }
    }

    private static void populate(PageFocusBinding b, boolean running) {
        b.focusEyebrow.setVisibility(View.GONE);
        b.focusModeLabel.setText("专注"); b.focusTaskTitle.setText(running ? "自由专注" : "不关联任务 · 可选择 ›");
        b.timerButton.setText(running ? "暂停" : "开始");
        b.timerRing.setTimer(1_500_000, 1_500_000, running ? "正在专注" : "准备开始");
    }

    private static void assertBounds(View parent) {
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) parent;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            assertTrue("left " + child, child.getLeft() >= 0);
            assertTrue("right " + child, child.getRight() <= group.getWidth() + 1);
            if (child instanceof TextView) {
                TextView text = (TextView) child;
                assertNotNull(text.getLayout());
                assertTrue("text height " + text.getText(), text.getHeight() >= text.getLayout().getHeight()
                        + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom());
            }
            assertBounds(child);
        }
    }

    private static void capture(View root, String name) {
        File directory = new File(root.getContext().getExternalFilesDir(null), "focus-immersive");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap frame = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        android.content.res.TypedArray background = root.getContext().obtainStyledAttributes(
                new int[]{android.R.attr.colorBackground});
        try { canvas.drawColor(background.getColor(0, android.graphics.Color.WHITE)); }
        finally { background.recycle(); }
        root.draw(canvas);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, out));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        finally { frame.recycle(); }
    }

    private static void assertDialInkInsideRing(TimerRingView ring) {
        android.content.res.TypedArray colors = ring.getContext().obtainStyledAttributes(new int[]{
                android.R.attr.textColorPrimary, android.R.attr.textColorSecondary});
        int primary, secondary;
        try { primary = colors.getColor(0, 0); secondary = colors.getColor(1, 0); }
        finally { colors.recycle(); }
        Bitmap image = Bitmap.createBitmap(ring.getWidth(), ring.getHeight(), Bitmap.Config.ARGB_8888);
        ring.draw(new Canvas(image));
        int[] pixels = new int[image.getWidth() * image.getHeight()];
        image.getPixels(pixels, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
        float radius = ring.getWidth() / 2f - dp(ring.getContext(), 16);
        try {
            for (int y = 0; y < ring.getHeight(); y += 2) for (int x = 0; x < ring.getWidth(); x += 2) {
                int color = pixels[y * ring.getWidth() + x];
                if (color != primary && color != secondary || android.graphics.Color.alpha(color) != 255) continue;
                float dx = x - ring.getWidth() / 2f, dy = y - ring.getHeight() / 2f;
                assertTrue("Timer text must stay inside the dial, not cross the progress stroke",
                        dx * dx + dy * dy <= radius * radius);
            }
        } finally { image.recycle(); }
    }

    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(450); }
    private static void assertEmulator() {
        assertTrue("Preference-changing tests are emulator-only", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"));
    }
}
