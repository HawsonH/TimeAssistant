package com.hawson.timeassistant.ui.schedule;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.DeviceFixtureSafety;
import com.hawson.timeassistant.ui.MainActivity;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real ViewGroup dispatch, including streams with no clickable child touch target. */
@RunWith(AndroidJUnit4.class)
public class ScheduleSwipeLayoutGestureTest {
    @Before public void requireScratchTarget() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DeviceFixtureSafety.requireIsolatedTarget(context);
        assertEquals("com.hawson.timeassistant.interaction.qa", context.getPackageName());
    }

    @Test public void reverseOpenRowBeforeActionPanelLayoutCloses() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Fixture fixture = new Fixture(activity);
                fixture.row.revealActions();
                assertTrue(fixture.row.isOpen());
                // The panel has requested, but not yet received, its expanded layout.
                float x = fixture.row.getWidth() - 8;
                assertTrue(fixture.send(MotionEvent.ACTION_DOWN, x));
                fixture.send(MotionEvent.ACTION_MOVE, x + fixture.actionWidth * 2);
                fixture.send(MotionEvent.ACTION_UP, x + fixture.actionWidth * 2);
                fixture.assertClosed();
                assertEquals(0, fixture.actions.get());
            });
        }
    }

    @Test public void nonClickableContentCanCommitOnceThroughDispatch() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Fixture fixture = new Fixture(activity);
                float x = fixture.row.getWidth() / 2f;
                assertTrue(fixture.send(MotionEvent.ACTION_DOWN, x));
                fixture.send(MotionEvent.ACTION_MOVE, x - fixture.armTravel());
                assertEquals(0, fixture.actions.get());
                fixture.send(MotionEvent.ACTION_UP, x - fixture.armTravel());
                fixture.send(MotionEvent.ACTION_UP, x - fixture.armTravel());
                assertEquals(1, fixture.actions.get());
                fixture.assertClosed();
            });
        }
    }

    @Test public void cancelAndMultitouchAllowNextChildClickAfterIdle() {
        AtomicInteger clicks = new AtomicInteger();
        Fixture[] fixtures = new Fixture[1];
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Fixture fixture = fixtures[0] = new Fixture(activity);
                CheckBox checkbox = new CheckBox(activity);
                checkbox.setOnClickListener(view -> clicks.incrementAndGet());
                fixture.content.addView(checkbox, new FrameLayout.LayoutParams(
                        fixture.actionWidth, fixture.actionWidth));
                fixture.layout();
                float x = fixture.row.getWidth() / 2f;
                fixture.send(MotionEvent.ACTION_DOWN, x);
                fixture.send(MotionEvent.ACTION_MOVE, x - fixture.armTravel());
                fixture.send(MotionEvent.ACTION_CANCEL, x - fixture.armTravel());
                fixture.send(MotionEvent.ACTION_UP, x - fixture.armTravel());
                fixture.assertClosed();
                fixture.send(MotionEvent.ACTION_DOWN, x);
                fixture.send(MotionEvent.ACTION_MOVE, x - fixture.armTravel());
                fixture.secondPointer(x - fixture.armTravel());
                fixture.send(MotionEvent.ACTION_MOVE, x - fixture.armTravel());
                fixture.send(MotionEvent.ACTION_UP, x - fixture.armTravel());
                fixture.assertClosed();
                assertEquals(0, fixture.actions.get());
                float hitX = checkbox.getWidth() / 2f;
                float hitY = checkbox.getHeight() / 2f;
                assertTrue(fixture.send(MotionEvent.ACTION_DOWN, hitX, hitY));
                assertTrue(fixture.send(MotionEvent.ACTION_UP, hitX, hitY));
            });
            // View posts performClick after UP; repository/callback assertions must wait.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals(1, clicks.get());
                assertEquals(0, fixtures[0].actions.get());
                fixtures[0].assertClosed();
            });
        }
    }

    private static final class Fixture {
        final ScheduleSwipeLayout row;
        final FrameLayout content;
        final AtomicInteger actions = new AtomicInteger();
        final int actionWidth;
        private long downTime;
        private long eventTime;

        Fixture(MainActivity activity) {
            row = new ScheduleSwipeLayout(activity);
            content = new FrameLayout(activity);
            actionWidth = Math.round(56 * activity.getResources().getDisplayMetrics().density);
            row.setContent(content);
            row.bind(this, new ColorDrawable(Color.RED), "Complete",
                    new ColorDrawable(Color.GREEN), "Edit", (action, state) -> {
                        assertSame(this, state);
                        assertEquals(ScheduleSwipeLayout.Action.MAIN, action);
                        actions.incrementAndGet();
                    });
            activity.addContentView(row, new FrameLayout.LayoutParams(actionWidth * 8, actionWidth * 2));
            layout();
        }

        void layout() {
            row.measure(View.MeasureSpec.makeMeasureSpec(actionWidth * 8, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(actionWidth * 2, View.MeasureSpec.EXACTLY));
            row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
        }

        float armTravel() {
            return actionWidth * 2 + (Math.min(actionWidth * 96f / 56, row.getWidth() * .25f)
                    + actionWidth) / .55f;
        }

        boolean send(int action, float x) { return send(action, x, actionWidth); }

        boolean send(int action, float x, float y) {
            if (action == MotionEvent.ACTION_DOWN) downTime = eventTime = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(downTime, eventTime += 16, action, x, y, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { return row.dispatchTouchEvent(event); } finally { event.recycle(); }
        }

        void secondPointer(float x) {
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
            MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
            for (int i = 0; i < 2; i++) {
                properties[i] = new MotionEvent.PointerProperties();
                properties[i].id = i;
                properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coordinates[i] = new MotionEvent.PointerCoords();
                coordinates[i].x = x + i * 8;
                coordinates[i].y = actionWidth;
                coordinates[i].pressure = coordinates[i].size = 1;
            }
            MotionEvent event = MotionEvent.obtain(downTime, eventTime += 16,
                    MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    2, properties, coordinates, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try { row.dispatchTouchEvent(event); } finally { event.recycle(); }
        }

        void assertClosed() {
            assertFalse(row.isOpen());
            com.hawson.timeassistant.design.MotionSpec.cancel(row);
            assertEquals(0f, content.getTranslationX(), .01f);
        }
    }
}
