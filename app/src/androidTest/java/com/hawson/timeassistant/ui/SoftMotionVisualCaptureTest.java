package com.hawson.timeassistant.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.DeviceFixtureSafety;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.design.*;
import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.ui.schedule.ScheduleSwipeLayout;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real compositor screenshots, not software Canvas (which does not apply hardware outline clipping). */
@RunWith(AndroidJUnit4.class)
public class SoftMotionVisualCaptureTest {
    @Test public void captureRoundedSwipeAndBidirectionalIndicatorIntermediateFrames() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        DeviceFixtureSafety.requireIsolatedTarget(context);
        assertEquals("com.hawson.timeassistant.interaction.qa", context.getPackageName());
        WorkflowDriver.requireUnlockedDisplay(context);
        AppearancePreferences appearance = new AppearancePreferences(context);
        MotionSpec.Mode previous = appearance.motionMode(); appearance.setMotionMode(MotionSpec.Mode.FULL);
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().putInt("seen_version", 1)
                .putBoolean("intro_pending", false).putBoolean("context_enabled", false).commit();
        AppRepository repository = AppRepository.get(context);
        repository.clearAll();
        repository.upsertTask(new TaskItem("圆角与跟手过渡验收").setRequiresTimeArrangement(false)
                .setPlannedDateAt(System.currentTimeMillis()));
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> a.showPage(1)); WorkflowDriver.idle();
            screen.onActivity(a -> a.findViewById(R.id.todoTabButton).performClick()); WorkflowDriver.idle();
            ScheduleSwipeLayout[] row = {null};
            screen.onActivity(a -> row[0] = find(a.pageContainer));
            assertNotNull(row[0]);
            float width = UiKit.dp(context, 56);
            float threshold = 2 * width + Math.min(UiKit.dp(context, 96), row[0].getWidth() * .25f);
            float[] travels = {width, width * 2, width * 2 + (threshold - width * 2) * .5f / .55f,
                    width * 2 + (threshold - width * 2 + 8) / .55f, width * 2};
            String[] stages = {"first", "open", "damped", "armed", "retreat"};
            screen.onActivity(a -> send(row[0], MotionEvent.ACTION_DOWN, 0));
            for (int index = 0; index < travels.length; index++) {
                float travel = travels[index];
                screen.onActivity(a -> { send(row[0], MotionEvent.ACTION_MOVE, travel); send(row[0], MotionEvent.ACTION_MOVE, travel); });
                WorkflowDriver.idle();
                capture("swipe-" + stages[index], row[0]);
            }
            screen.onActivity(a -> {
                send(row[0], MotionEvent.ACTION_UP, 2 * width);
                MotionHandle handle = field(row[0], "settling", MotionHandle.class);
                handle.seekTo(70); handle.cancel();
            });
            capture("swipe-settle-middle", row[0]);
            screen.onActivity(a -> row[0].close()); WorkflowDriver.idle(); capture("swipe-closed", row[0]);
            screen.onActivity(a -> {
                SlidingSegmentedControl tabs = a.findViewById(R.id.scheduleSegments);
                tabs.setSelectedIndex(0, false); tabs.stopMotion(); tabs.setSelectedIndex(2, false);
                tabs.seekIndicatorTo(70); field(tabs, SlidingIndicatorLayout.class, "motion", MotionHandle.class).cancel();
            });
            WorkflowDriver.idle(); capture("indicator-forward-middle", null);
            screen.onActivity(a -> {
                SlidingSegmentedControl tabs = a.findViewById(R.id.scheduleSegments);
                tabs.setSelectedIndex(0, false); tabs.seekIndicatorTo(70);
                field(tabs, SlidingIndicatorLayout.class, "motion", MotionHandle.class).cancel();
            });
            WorkflowDriver.idle(); capture("indicator-reverse-middle", null);
            assertEquals(TaskItem.Status.PENDING, repository.getAllTasks().get(0).getStatus());
        } finally { repository.clearAll(); appearance.setMotionMode(previous); }
    }
    private static void capture(String name, ScheduleSwipeLayout row) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(60);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View visible = WorkflowDriver.find(v -> v instanceof SlidingSegmentedControl);
            assertNotNull(visible);
            assertTrue("Screenshot must have application window focus", visible.hasWindowFocus());
        });
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try {
            if (row != null && !name.equals("swipe-closed")) {
                int[] xy = new int[2]; int[] size = new int[2]; int[] colors = new int[2];
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    assertTrue("Screenshot must not be covered by a system window", row.hasWindowFocus());
                    row.getLocationOnScreen(xy); size[0] = row.getWidth(); size[1] = row.getHeight();
                    View actions = field(row, "actions", View.class);
                    colors[0] = ((GradientDrawable) actions.getBackground()).getColor().getDefaultColor();
                    colors[1] = ((android.graphics.drawable.ColorDrawable) field(row, "edit", View.class).getBackground()).getColor();
                });
                for (int x : new int[]{xy[0], xy[0] + size[0] - 1})
                    for (int y : new int[]{xy[1], xy[1] + size[1] - 1}) {
                        int pixel = screenshot.getPixel(x, y);
                        assertNotEquals("Action color leaked outside rounded corner", colors[0], pixel);
                        assertNotEquals("Edit color leaked outside rounded corner", colors[1], pixel);
                    }
            }
            File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                    .getExternalFilesDir(null), "soft-motion-frames");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally { screenshot.recycle(); }
    }
    private static ScheduleSwipeLayout find(View root) {
        if (root instanceof ScheduleSwipeLayout) return (ScheduleSwipeLayout) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            ScheduleSwipeLayout match = find(((ViewGroup) root).getChildAt(i)); if (match != null) return match;
        }
        return null;
    }
    private static void send(View view, int action, float travel) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, view.getWidth() - UiKit.dp(view.getContext(), 8) - travel,
                view.getHeight() / 2f, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private static <T> T field(Object target, String name, Class<T> type) { return field(target, target.getClass(), name, type); }
    private static <T> T field(Object target, Class<?> owner, String name, Class<T> type) {
        try {
            java.lang.reflect.Field field = owner.getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target));
        } catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }
}
