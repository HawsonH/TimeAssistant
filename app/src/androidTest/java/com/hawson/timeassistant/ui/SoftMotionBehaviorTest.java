package com.hawson.timeassistant.ui;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.DeviceFixtureSafety;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.design.*;
import com.hawson.timeassistant.ui.schedule.ScheduleSwipeLayout;
import com.hawson.timeassistant.ui.widget.TimerRingView;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Presentation-only fixtures; never requests AI or changes task persistence. */
@RunWith(AndroidJUnit4.class)
public class SoftMotionBehaviorTest {
    private Context context;
    private MotionSpec.Mode previous;
    @Before public void prepare() {
        context = ApplicationProvider.getApplicationContext();
        DeviceFixtureSafety.requireIsolatedTarget(context);
        previous = new AppearancePreferences(context).motionMode();
        new AppearancePreferences(context).setMotionMode(MotionSpec.Mode.FULL);
        context.getSharedPreferences("time_assistant_tutorial", 0).edit()
                .putInt("seen_version", 1).putBoolean("intro_pending", false)
                .putBoolean("context_enabled", false).commit();
    }
    @After public void restore() { new AppearancePreferences(context).setMotionMode(previous); }

    @Test public void ownedPressAndEntryRestoreBaselineWithoutOverwritingSwipeOrDisabledAlpha() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                TextView view = new TextView(activity); view.setClickable(true);
                FrameLayout host = new FrameLayout(activity); host.addView(view); activity.setContentView(host);
                view.setAlpha(.45f); view.setScaleX(.9f); view.setScaleY(.8f); view.setTranslationX(-73f);
                MotionSpec.press(view);
                touch(view, MotionEvent.ACTION_DOWN, 20);
                MotionSpec.enter(view);
                MotionSpec.cancel(view);
                assertEquals(.45f, view.getAlpha(), .001f);
                assertEquals(-73f, view.getTranslationX(), .001f);
                assertEquals(.9f, view.getScaleX(), .001f);
                assertEquals(.8f, view.getScaleY(), .001f);
                view.setEnabled(false); MotionSpec.feedback(view);
                assertEquals(.45f, view.getAlpha(), .001f);
                MotionSpec.enterHorizontal(view, 1);
                MotionSpec.enterHorizontal(view, -1);
                MotionSpec.cancelTree(host);
                assertEquals(-73f, view.getTranslationX(), .001f);
            });
        }
    }

    @Test public void handlesPreserveFrameOnCancelAndFinishWhenPolicyChanges() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View owner = activity.getWindow().getDecorView();
                float[] frame = {0}; int[] finishes = {0};
                MotionHandle handle = MotionSpec.animate(owner, 240, f -> frame[0] = f, () -> finishes[0]++);
                handle.seekTo(60); float middle = frame[0];
                assertTrue(middle > 0 && middle < 1);
                handle.cancel(); assertEquals(middle, frame[0], 0); assertEquals(0, finishes[0]);
                handle.finish(); assertEquals(0, finishes[0]);
                MotionHandle next = MotionSpec.animate(owner, 240, f -> frame[0] = f, () -> finishes[0]++);
                new AppearancePreferences(context).setMotionMode(MotionSpec.Mode.OFF);
                next.seekTo(30);
                assertFalse(next.isRunning()); assertEquals(1f, frame[0], 0); assertEquals(1, finishes[0]);
            });
        }
    }

    @Test public void reducedAndOffIndicatorsHaveNoSpatialInterpolation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                SlidingSegmentedControl tabs = new SlidingSegmentedControl(activity);
                tabs.setSegments(new String[]{"日程", "待办", "注意"});
                tabs.measure(exact(600), exact(110)); tabs.layout(0, 0, 600, 110);
                tabs.setSelectedIndex(0, false);
                for (MotionSpec.Mode mode : new MotionSpec.Mode[]{MotionSpec.Mode.REDUCED, MotionSpec.Mode.OFF}) {
                    new AppearancePreferences(context).setMotionMode(mode);
                    assertTrue(MotionSpec.duration(context, 220) <= 100);
                    tabs.setSelectedIndex(2, false);
                    assertEquals(tabs.getChildAt(2).getLeft(), tabs.indicatorBounds().left, .01f);
                    tabs.setSelectedIndex(0, false);
                    assertEquals(tabs.getChildAt(0).getLeft(), tabs.indicatorBounds().left, .01f);
                    tabs.stopMotion();
                }
            });
        }
    }

    @Test public void disclosureInterruptionKeepsFieldsAndRestoresWrapContent() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                DisclosureSection section = new DisclosureSection(activity, "备注", false);
                android.widget.EditText draft = new android.widget.EditText(activity);
                draft.setText("unsaved draft"); section.content.addView(draft);
                section.measure(exact(600), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                section.layout(0, 0, 600, section.getMeasuredHeight());
                section.getChildAt(0).performClick();
                field(section, "motion", MotionHandle.class).seekTo(80);
                section.layout(0, 0, 600, section.getMeasuredHeight() + 12);
                assertTrue("A component-owned height change must not end the disclosure transition",
                        field(section, "motion", MotionHandle.class).isRunning());
                section.getChildAt(0).performClick();
                MotionSpec.cancelTree(section);
                assertEquals(View.GONE, section.content.getVisibility());
                assertEquals(-2, section.content.getLayoutParams().height);
                assertEquals("unsaved draft", draft.getText().toString());
                section.getChildAt(0).performClick(); MotionSpec.cancelTree(section);
                assertEquals(View.VISIBLE, section.content.getVisibility());
                assertEquals(1f, section.content.getAlpha(), 0);
            });
        }
    }

    @Test public void timerMetadataIsImmediateWhileOnlyArcInterpolatesAndStopsAtTarget() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                TimerRingView ring = new TimerRingView(activity); activity.setContentView(ring);
                ring.setTimer(100000, 100000, "ready");
                ring.animateTimerTo(40000, 100000, "running", 240);
                assertTrue(ring.getContentDescription().toString().contains("40"));
                Object textLayer = field(ring, "timeText", Object.class);
                assertNull("Ordinary ticks must not crossfade or slide text",
                        field(textLayer, "outgoing", android.graphics.Bitmap.class));
                MotionHandle handle = field(ring, "timerAnimator", MotionHandle.class);
                assertNotNull(handle); handle.seekTo(80);
                float progress = field(ring, "progress", Float.class);
                assertTrue(progress > .4f && progress < 1);
                ring.stopMotion(); assertEquals(.4f, field(ring, "progress", Float.class), .001f);
                assertEquals(40000L, (long) field(ring, "remainingMillis", Long.class));
            });
        }
    }

    @Test public void interruptedPageReplacementLeavesOnlyLatestPageAndNoForeignTransforms() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                FrameLayout container = new FrameLayout(activity); activity.setContentView(container);
                TextView first = new TextView(activity), second = new TextView(activity), third = new TextView(activity);
                container.addView(first);
                second.setTranslationY(11); second.setAlpha(.7f);
                ContentTransition.replace(container, second, 1, true);
                ContentTransition.replace(container, second, 1, true);
                assertEquals(1, container.getChildCount());
                assertEquals(.7f, second.getAlpha(), .001f);
                assertEquals(0f, second.getTranslationX(), .001f);
                ContentTransition.replace(container, third, -1, true);
                MotionSpec.cancelTree(container);
                assertEquals(1, container.getChildCount()); assertSame(third, container.getChildAt(0));
                assertEquals(1f, third.getAlpha(), .001f); assertEquals(0f, third.getTranslationX(), .001f);
                assertEquals(11f, second.getTranslationY(), .001f);
                assertFalse(third.isClickable());
            });
        }
    }

    @Test public void swipeTakeoverUsesCurrentDisplayedDistanceAndRetainsRoundedColorBackground() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ScheduleSwipeLayout row = new ScheduleSwipeLayout(activity);
                TextView content = new TextView(activity); content.setText("local fixture"); content.setMinHeight(160);
                row.setContent(content);
                row.bind("revision", activity.getDrawable(R.drawable.ic_task_check), "完成",
                        activity.getDrawable(android.R.drawable.ic_menu_edit), "编辑", (action, revision) -> fail("No commit expected"));
                FrameLayout host = new FrameLayout(activity); host.addView(row, new FrameLayout.LayoutParams(-1, 160));
                activity.setContentView(host);
                row.measure(exact(900), exact(160)); row.layout(0, 0, 900, 160);
                row.setActionColors(android.graphics.Color.GREEN, android.graphics.Color.GRAY);
                Outline outline = new Outline(); row.getOutlineProvider().getOutline(row, outline);
                assertTrue(row.getClipToOutline());
                assertEquals(UiKit.dp(activity, UiKit.RADIUS_CONTAINER), outline.getRadius(), .01f);
                assertTrue(field(row, "actions", View.class).getBackground() instanceof android.graphics.drawable.GradientDrawable);
                row.revealActions();
                MotionHandle settle = field(row, "settling", MotionHandle.class);
                settle.seekTo(60); float visible = -content.getTranslationX();
                assertTrue(visible > 0 && visible < UiKit.dp(activity, 112));
                touch(row, MotionEvent.ACTION_DOWN, 880);
                assertEquals(-visible, content.getTranslationX(), .01f);
                touch(row, MotionEvent.ACTION_MOVE, 850);
                assertEquals(-visible - 30, content.getTranslationX(), 1);
                touch(row, MotionEvent.ACTION_CANCEL, 850);
                assertEquals(0f, content.getTranslationX(), .01f);
                assertFalse(row.isOpen()); assertFalse(settle.isRunning());
            });
        }
    }

    private static int exact(int size) { return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY); }
    private static void touch(View view, int action, float x) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, 80, 0);
        view.dispatchTouchEvent(event); event.recycle();
    }
    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name); field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
}
