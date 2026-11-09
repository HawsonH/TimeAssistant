package com.hawson.timeassistant.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.databinding.PageFocusBinding;
import com.hawson.timeassistant.design.AppearancePreferences;
import com.hawson.timeassistant.design.MotionHandle;
import com.hawson.timeassistant.design.MotionSpec;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.testing.AiSettingsHarnessActivity;
import com.hawson.timeassistant.ui.widget.TimerRingView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Local injected events only: no repository, real timer sessions or network requests. */
@RunWith(AndroidJUnit4.class)
public class FocusDialInteractionTest {
    @Test public void phoneSafeDialPreviewCommitCancelAndStateValidation() {
        WorkflowDriver.requireUnlockedDisplay(ApplicationProvider.getApplicationContext());
        try (ActivityScenario<AiSettingsHarnessActivity> screen = ActivityScenario.launch(AiSettingsHarnessActivity.class)) {
            screen.onActivity(a -> {
                FocusPageController page = new FocusPageController();
                PageFocusBinding binding = page.inflate(a.getLayoutInflater()); a.setContentView(binding.getRoot());
                Fake host = new Fake(binding.timerRing); page.attachDial(host);
                TimerRingView ring = binding.timerRing;
                float step = 24 * a.getResources().getDisplayMetrics().density;
                touch(ring, MotionEvent.ACTION_DOWN, 100, step * 4);
                touch(ring, MotionEvent.ACTION_MOVE, 100, step * 2);
                assertEquals(27, host.preview);
                assertEquals(25, host.minutes);
                assertEquals(0, host.saves);
                assertTrue(page.isDurationPreviewing());
                touch(ring, MotionEvent.ACTION_UP, 100, step * 2);
                assertEquals(27, host.minutes); assertEquals(1, host.saves);
                touch(ring, MotionEvent.ACTION_UP, 100, step * 2);
                assertEquals(1, host.saves);
                touch(ring, MotionEvent.ACTION_DOWN, 100, step * 4);
                touch(ring, MotionEvent.ACTION_MOVE, 100, step);
                touch(ring, MotionEvent.ACTION_CANCEL, 100, step);
                assertEquals(27, host.minutes); assertEquals(1, host.saves);
                assertEquals(0, host.details);
                touch(ring, MotionEvent.ACTION_DOWN, 100, step * 4);
                touch(ring, MotionEvent.ACTION_MOVE, 100, step);
                host.editable = false;
                page.updatePresentation(FocusState.ready(null, FocusState.Mode.FOCUS, 1_500_000, 0)
                        .startOrResume(System.currentTimeMillis()), false);
                touch(ring, MotionEvent.ACTION_UP, 100, step);
                assertEquals(1, host.saves); assertFalse(page.isDurationPreviewing());
                assertTrue(ring.createAccessibilityNodeInfo().getActionList().stream()
                        .noneMatch(action -> action.getLabel() != null && action.getLabel().toString().contains("分钟")));
                host.editable = true;
                page.updatePresentation(FocusState.idle(), true);
                assertTrue(ring.createAccessibilityNodeInfo().getActionList().stream()
                        .anyMatch(action -> "增加一分钟".contentEquals(action.getLabel() == null ? "" : action.getLabel())));
                touch(ring, MotionEvent.ACTION_DOWN, 100, 100);
                touch(ring, MotionEvent.ACTION_UP, 100, 100);
                assertEquals(1, host.details);
                page.clearBinding();
            });
        }
    }

    @Test public void phoneSafeTextRetargetKeepsDialGeometryAndIntermediateFrames() {
        WorkflowDriver.requireUnlockedDisplay(ApplicationProvider.getApplicationContext());
        try (ActivityScenario<AiSettingsHarnessActivity> screen = ActivityScenario.launch(AiSettingsHarnessActivity.class)) {
            TimerRingView[] holder = {null};
            screen.onActivity(a -> {
                PageFocusBinding b = PageFocusBinding.inflate(a.getLayoutInflater());
                a.setContentView(b.getRoot()); holder[0] = b.timerRing;
                b.timerRing.setTimer(1_500_000, 1_500_000, "准备专注");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(a -> {
                TimerRingView ring = holder[0];
                int top = ring.getTop(), width = ring.getWidth(), height = ring.getHeight();
                Bitmap before = bitmap(ring);
                ring.transitionTimerTo(300_000, 300_000, "准备短休", true);
                if (MotionSpec.duration(a, 220) > 0) {
                    MotionHandle animator = (MotionHandle) field(field(ring, "timeText"), "animator");
                    assertNotNull(animator);
                    capture(ring, "mode-0"); animator.seekTo(70); capture(ring, "mode-70");
                    Bitmap middle = bitmap(ring);
                    assertFalse("Text must change across intermediate frames", before.sameAs(middle));
                    assertOuterPixelsEqual(before, middle, a.getResources().getDisplayMetrics().density);
                    ring.setTimer(300_000, 300_000, "准备短休");
                    assertSame("Same target must not restart animation", animator, field(field(ring, "timeText"), "animator"));
                    ring.transitionTimerTo(600_000, 600_000, "准备长休", true);
                    Bitmap redirected = bitmap(ring);
                    assertTrue("Retarget must start at current visible frame", nearlyEqual(middle, redirected));
                    middle.recycle(); redirected.recycle();
                }
                ring.stopMotion(); capture(ring, "mode-end");
                ring.transitionTimerTo(660_000, 660_000, "准备长休", false);
                if (MotionSpec.duration(a, 180) > 0) {
                    MotionHandle animator = (MotionHandle) field(field(ring, "timeText"), "animator");
                    animator.seekTo(60); capture(ring, "minute-up-60");
                    animator.seekTo(120); capture(ring, "minute-up-120");
                }
                ring.stopMotion();
                ring.transitionTimerTo(600_000, 600_000, "准备长休", false);
                if (MotionSpec.duration(a, 180) > 0) {
                    MotionHandle animator = (MotionHandle) field(field(ring, "timeText"), "animator");
                    animator.seekTo(60); capture(ring, "minute-down-60");
                }
                assertEquals(top, ring.getTop()); assertEquals(width, ring.getWidth()); assertEquals(height, ring.getHeight());
                assertEquals(1f, ring.getAlpha(), 0); assertEquals(0f, ring.getTranslationY(), 0);
                assertTrue(ring.getContentDescription().toString().contains("10 分"));
                ring.stopMotion(); before.recycle();
            });
        }
    }

    @Test public void offAndReducedAnimationAndBackgroundCleanup() {
        assertTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"));
        Context context = ApplicationProvider.getApplicationContext();
        AppearancePreferences prefs = new AppearancePreferences(context);
        MotionSpec.Mode old = prefs.motionMode();
        try {
            for (MotionSpec.Mode mode : MotionSpec.Mode.values()) {
                prefs.setMotionMode(mode);
                try (ActivityScenario<AiSettingsHarnessActivity> screen = ActivityScenario.launch(AiSettingsHarnessActivity.class)) {
                    TimerRingView[] ring = {null};
                    screen.onActivity(a -> {
                        ring[0] = new TimerRingView(a); a.setContentView(ring[0]);
                        ring[0].setTimer(1_500_000, 1_500_000, "准备专注");
                    });
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    screen.onActivity(a -> {
                        ring[0].transitionTimerTo(1_560_000, 1_560_000, "准备专注", false);
                        Object text = field(ring[0], "timeText");
                        MotionHandle animator = (MotionHandle) field(text, "animator");
                        if (mode == MotionSpec.Mode.OFF) assertEquals(1f, (Float) field(text, "fraction"), 0);
                        else if (animator != null) {
                            animator.seekTo(mode == MotionSpec.Mode.REDUCED ? 100 : 180);
                            assertEquals(1f, (Float) field(text, "fraction"), .001f);
                            if (mode == MotionSpec.Mode.REDUCED) assertEquals(0f, (Float) field(text, "offset"), 0);
                        }
                        ring[0].stopMotion(); assertNull(field(text, "outgoing"));
                        if (animator != null) assertFalse(animator.isRunning());
                    });
                }
            }
        } finally { prefs.setMotionMode(old); }
    }

    @Test public void systemDisabledTextUpdatesImmediately() {
        Context context = ApplicationProvider.getApplicationContext();
        org.junit.Assume.assumeTrue("Run only in the system-animation-off batch", "0".equals(
                android.provider.Settings.Global.getString(context.getContentResolver(), "animator_duration_scale")));
        try (ActivityScenario<AiSettingsHarnessActivity> screen = ActivityScenario.launch(AiSettingsHarnessActivity.class)) {
            TimerRingView[] ring = {null};
            screen.onActivity(a -> { ring[0] = new TimerRingView(a); a.setContentView(ring[0]); });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(a -> {
                assertFalse(android.animation.ValueAnimator.areAnimatorsEnabled());
                ring[0].transitionTimerTo(1_800_000, 1_800_000, "准备专注", false);
                Object text = field(ring[0], "timeText");
                assertNull(field(text, "outgoing")); assertEquals(1f, (Float) field(text, "fraction"), 0);
                assertTrue(ring[0].getContentDescription().toString().contains("30 分"));
            });
        }
    }

    @Test public void multitouchModeChangeAndLeavingNeverSavePreview() {
        try (ActivityScenario<AiSettingsHarnessActivity> screen = ActivityScenario.launch(AiSettingsHarnessActivity.class)) {
            screen.onActivity(a -> {
                PageFocusBinding b = PageFocusBinding.inflate(a.getLayoutInflater()); a.setContentView(b.getRoot());
                Fake host = new Fake(b.timerRing); FocusDialController controller = new FocusDialController(b.timerRing, host);
                touch(b.timerRing, MotionEvent.ACTION_DOWN, 100, 500);
                touch(b.timerRing, MotionEvent.ACTION_MOVE, 100, 100);
                MotionEvent.PointerProperties p = new MotionEvent.PointerProperties(); p.id = 0;
                MotionEvent.PointerProperties q = new MotionEvent.PointerProperties(); q.id = 1;
                MotionEvent.PointerCoords c = new MotionEvent.PointerCoords(); c.x = 100; c.y = 100;
                MotionEvent event = MotionEvent.obtain(0, 1, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        2, new MotionEvent.PointerProperties[]{p, q}, new MotionEvent.PointerCoords[]{c, c}, 0, 0, 1, 1, 0, 0, 0, 0);
                b.timerRing.dispatchTouchEvent(event); event.recycle();
                touch(b.timerRing, MotionEvent.ACTION_UP, 100, 100);
                assertEquals(0, host.saves); assertEquals(0, host.details);
                touch(b.timerRing, MotionEvent.ACTION_DOWN, 100, 500);
                touch(b.timerRing, MotionEvent.ACTION_MOVE, 100, 100);
                host.mode = FocusState.Mode.SHORT_BREAK;
                touch(b.timerRing, MotionEvent.ACTION_UP, 100, 100);
                assertEquals(0, host.saves);
                touch(b.timerRing, MotionEvent.ACTION_DOWN, 100, 500);
                touch(b.timerRing, MotionEvent.ACTION_MOVE, 100, 100);
                controller.cancel();
                assertFalse(controller.isPreviewing()); assertEquals(0, host.saves);
            });
        }
    }

    private static final class Fake implements FocusDialController.Host {
        final TimerRingView ring;
        boolean editable = true;
        int minutes = 25, preview, saves, details;
        FocusState.Mode mode = FocusState.Mode.FOCUS;
        Fake(TimerRingView ring) { this.ring = ring; }
        public boolean editable() { return editable; }
        public FocusState.Mode mode() { return mode; }
        public int minutes() { return minutes; }
        public void preview(int minutes) { preview = minutes; ring.transitionTimerTo(minutes * 60_000L, minutes * 60_000L, "准备专注", false); }
        public void restore() { ring.setTimer(minutes * 60_000L, minutes * 60_000L, "准备专注"); }
        public void save(FocusState.Mode mode, int minutes) { saves++; this.minutes = minutes; }
        public void switchMode(int direction) { mode = FocusState.Mode.SHORT_BREAK; }
        public void details() { details++; }
    }

    private static void touch(View ring, int action, float x, float y) {
        long now = SystemClock.uptimeMillis(); MotionEvent e = MotionEvent.obtain(now, now, action, x, y, 0);
        ring.dispatchTouchEvent(e); e.recycle();
    }
    private static Object field(Object value, String name) {
        try { Field f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Bitmap bitmap(View v) {
        Bitmap bitmap = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(bitmap)); return bitmap;
    }
    private static boolean nearlyEqual(Bitmap a, Bitmap b) {
        int different = 0;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++)
            if (a.getPixel(x, y) != b.getPixel(x, y)) different++;
        return different < a.getWidth() * a.getHeight() * .015;
    }
    private static void assertOuterPixelsEqual(Bitmap a, Bitmap b, float density) {
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) {
            double radius = Math.hypot(x - a.getWidth() / 2f, y - a.getHeight() / 2f);
            if (radius > a.getWidth() / 2f - 20 * density) assertEquals(a.getPixel(x, y), b.getPixel(x, y));
        }
    }
    private static void capture(View v, String name) {
        File dir = new File(v.getContext().getExternalFilesDir(null), "focus-dial-evidence");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        Bitmap bitmap = bitmap(v);
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } catch (Exception e) { throw new AssertionError(e); }
        finally { bitmap.recycle(); }
    }
}
