package com.hawson.timeassistant.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.AiServices;
import com.hawson.timeassistant.DeviceFixtureSafety;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.connections.AiCredentialsPort;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.design.AppearancePreferences;
import com.hawson.timeassistant.design.MotionSpec;
import com.hawson.timeassistant.design.SlidingSegmentedControl;
import com.hawson.timeassistant.domain.ChatMessage;
import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.ui.schedule.ScheduleSwipeGesture;
import com.hawson.timeassistant.ui.schedule.ScheduleSwipeLayout;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Local-only destructive fixtures, restricted to the explicitly opted-in scratch APK. */
@RunWith(AndroidJUnit4.class)
public class InteractionRefinementTest {
    private AppRepository repository;
    private Context context;
    private MotionSpec.Mode previousMotion;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        DeviceFixtureSafety.requireIsolatedTarget(context);
        assertEquals("Use only the disposable interaction QA installation",
                "com.hawson.timeassistant.interaction.qa", context.getPackageName());
        assertEquals("Explicit fixture opt-in is required even on an emulator", "true",
                InstrumentationRegistry.getArguments().getString("physicalFixtures"));
        AiServices ai = ((TimeAssistantApplication) context).getAiServices();
        assertTrue("Scratch APK must have no saved connections", ai.profiles.list().isEmpty());
        assertTrue("Scratch APK must have no accounts", ai.accounts.accounts().isEmpty());
        assertFalse("Scratch APK must have no pending sign-in", ai.accounts.signInStatus().active);
        assertTrue("Scratch APK must have no credentials",
                field(ai, "credentials", AiCredentialsPort.class).references().isEmpty());
        repository = AppRepository.get(context);
        repository.clearAll();
        assertTrue(repository.getAllTasks().isEmpty());
        assertTrue(repository.getChatMessages().isEmpty());
        assertEquals("", repository.getSettings().getApiKey());
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().clear()
                .putInt("seen_version", 1).putBoolean("intro_pending", false)
                .putBoolean("context_enabled", false).commit();
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().clear()
                .putInt("last_page", 0)
                .putBoolean("focus_lockscreen_settings_confirmed_v2", true).commit();
        AppearancePreferences appearance = new AppearancePreferences(context);
        previousMotion = appearance.motionMode();
        appearance.setMotionMode(MotionSpec.Mode.FULL);
    }

    @After public void tearDown() {
        if (repository != null) repository.clearAll();
        if (previousMotion != null) new AppearancePreferences(context).setMotionMode(previousMotion);
    }

    @Test public void rapidScheduleClicksKeepRootSegmentsAndImmediateSelection() {
        try (ActivityScenario<MainActivity> scenario = schedule()) {
            scenario.onActivity(activity -> {
                View root = activity.pageContainer.getChildAt(0);
                SlidingSegmentedControl segments = activity.findViewById(R.id.scheduleSegments);
                long selectedDate = activity.selectedDate.getTimeInMillis();
                int[] ids = {R.id.scheduleTabButton, R.id.todoTabButton, R.id.reflowTabButton};
                int[] modes = {1, 2, 0, 2, 1, 0, 1, 2, 1};
                for (int mode : modes) {
                    assertTrue(activity.findViewById(ids[mode]).performClick());
                    assertSame(root, activity.pageContainer.getChildAt(0));
                    assertEquals(1, activity.pageContainer.getChildCount());
                    assertSame(segments, activity.findViewById(R.id.scheduleSegments));
                    assertEquals(mode, activity.scheduleMode);
                    assertEquals(mode, segments.getSelectedIndex());
                    for (int i = 0; i < ids.length; i++) {
                        assertEquals(i == mode, activity.findViewById(ids[i]).isSelected());
                    }
                    assertEquals(selectedDate, activity.selectedDate.getTimeInMillis());
                }
                activity.showPage(1);
                assertSame(root, activity.pageContainer.getChildAt(0));
            });
            idle();
            scenario.onActivity(activity -> {
                assertEquals(TimeAssistantActivity.SCHEDULE_TODO, activity.scheduleMode);
                assertEquals(1, ((SlidingSegmentedControl) activity.findViewById(
                        R.id.scheduleSegments)).getSelectedIndex());
            });
        }
    }

    @Test public void indicatorIntermediateFrameStaysBoundedAndRetargetsWithoutJump() {
        assumeTrue("Intermediate motion requires enabled system animations", MotionSpec.full(context));
        try (ActivityScenario<MainActivity> scenario = schedule()) {
            scenario.onActivity(activity -> {
                SlidingSegmentedControl segments = activity.findViewById(R.id.scheduleSegments);
                float start = segments.getChildAt(0).getLeft();
                float end = segments.getChildAt(2).getLeft();
                activity.findViewById(R.id.reflowTabButton).performClick();
                segments.seekIndicatorTo(MotionSpec.INDICATOR / 2);
                float middle = segments.indicatorBounds().left;
                assertTrue("Must sample an intermediate position", middle > start && middle < end);
                assertIndicatorBounds(segments, middle);
                captureView(segments, "indicator-forward-middle");
                activity.findViewById(R.id.scheduleTabButton).performClick();
                assertEquals(middle, segments.indicatorBounds().left, .01f);
                segments.seekIndicatorTo(MotionSpec.INDICATOR / 2);
                float returning = segments.indicatorBounds().left;
                assertTrue(returning > start && returning < middle);
                assertIndicatorBounds(segments, returning);
                captureView(segments, "indicator-retarget-middle");
                segments.stopMotion();
                assertEquals(start, segments.indicatorBounds().left, .01f);
                assertEquals(0, segments.getSelectedIndex());
            });
        }
    }

    @Test public void swipeProgressivelyRevealsArmsAndReversesWithoutCommit() {
        seedTodo();
        try (ActivityScenario<MainActivity> scenario = todo()) {
            AtomicInteger[] observed = new AtomicInteger[1];
            scenario.onActivity(activity -> {
                ScheduleSwipeLayout row = attachedRow(activity);
                AtomicInteger callbacks = countCallbacks(row);
                observed[0] = callbacks;
                Touch touch = new Touch(row);
                float width = dp(row, 56);
                touch.down();
                touch.move(width / 2);
                View content = field(row, "content", View.class);
                View actions = field(row, "actions", View.class);
                assertEquals(-width / 2, content.getTranslationX(), 1f);
                assertEquals(View.VISIBLE, actions.getVisibility());
                assertEquals(Math.round(width / 2) + Math.round(dp(row,
                        com.hawson.timeassistant.design.UiKit.RADIUS_CONTAINER)), actions.getClipBounds().width());
                touch.move(width);
                assertEquals(-width, content.getTranslationX(), 1f);
                captureView(row, "swipe-first-action");
                assertEquals(0, callbacks.get());
                touch.move(2 * width);
                captureView(row, "swipe-fully-revealed");
                touch.move(2 * width + dp(row, 32) / .55f);
                assertEquals(-(2 * width + dp(row, 32)), content.getTranslationX(), 1f);
                assertFalse(field(row, "gesture", ScheduleSwipeGesture.class).isArmed());
                captureView(row, "swipe-damped");
                touch.move(armTravel(row));
                assertTrue(field(row, "gesture", ScheduleSwipeGesture.class).isArmed());
                captureView(row, "swipe-armed");
                assertEquals(View.INVISIBLE, field(row, "edit", View.class).getVisibility());
                assertEquals(Math.round(-content.getTranslationX()), field(row, "main", View.class).getWidth());
                touch.move(width);
                assertFalse(field(row, "gesture", ScheduleSwipeGesture.class).isArmed());
                captureView(row, "swipe-retreat");
                assertEquals(View.INVISIBLE, field(row, "edit", View.class).getVisibility());
                touch.move(2 * width);
                assertEquals(View.VISIBLE, field(row, "edit", View.class).getVisibility());
                touch.up(width);
                assertTrue(row.isOpen());
                assertEquals(0, callbacks.get());
            });
            awaitLayout(scenario);
            scenario.onActivity(activity -> {
                ScheduleSwipeLayout row = attachedRow(activity);
                Touch touch = new Touch(row);
                float width = dp(row, 56);
                // Start inside the foreground so the reverse path stays within the row bounds.
                touch.downAt(dp(row, 8));
                touch.move(-2 * width);
                touch.up(-2 * width);
                com.hawson.timeassistant.design.MotionHandle settling = field(row, "settling",
                        com.hawson.timeassistant.design.MotionHandle.class);
                if (settling != null) settling.finish();
                assertClosed(row);
                assertEquals(0, observed[0].get());
            });
            assertFalse(repository.getAllTasks().get(0).getStatus().isTerminal());
        }
    }

    @Test public void fullSwipeCommitsExactlyOnceIncludingDuplicateRelease() {
        TaskItem task = seedTodo();
        try (ActivityScenario<MainActivity> scenario = todo()) {
            scenario.onActivity(activity -> {
                ScheduleSwipeLayout row = attachedRow(activity);
                AtomicInteger callbacks = countCallbacks(row);
                Touch touch = new Touch(row);
                touch.down();
                touch.move(armTravel(row));
                assertEquals(0, callbacks.get());
                captureView(row, "swipe-before-release");
                touch.up(armTravel(row));
                touch.up(armTravel(row));
                assertEquals(1, callbacks.get());
                assertClosed(row);
                captureView(row, "swipe-after-release");
            });
            assertEquals(TaskItem.Status.COMPLETED, repository.getTask(task.getId()).getStatus());
        }
    }

    @Test public void changedTaskBeforeReleaseInvalidatesCapturedAction() {
        TaskItem task = seedTodo();
        try (ActivityScenario<MainActivity> scenario = todo()) {
            scenario.onActivity(activity -> {
                ScheduleSwipeLayout row = attachedRow(activity);
                Touch touch = new Touch(row);
                touch.down();
                touch.move(armTravel(row));
                repository.upsertTask(repository.getTask(task.getId()).setTitle("Changed during gesture")
                        .markUpdatedAfter(task.getUpdatedAt()));
                touch.up(armTravel(row));
                assertEquals("Changed during gesture", repository.getTask(task.getId()).getTitle());
                assertEquals(TaskItem.Status.PENDING, repository.getTask(task.getId()).getStatus());
                assertClosed(row);
            });
        }
    }

    @Test public void cancelAndMultitouchDisarmWithoutCallbackAndAllowNextChildTap() {
        TaskItem task = seedTodo();
        try (ActivityScenario<MainActivity> scenario = todo()) {
            AtomicInteger[] observed = new AtomicInteger[1];
            scenario.onActivity(activity -> {
                ScheduleSwipeLayout row = attachedRow(activity);
                AtomicInteger callbacks = countCallbacks(row);
                observed[0] = callbacks;
                Touch touch = new Touch(row);
                touch.down();
                touch.move(armTravel(row));
                touch.send(MotionEvent.ACTION_CANCEL, armTravel(row));
                touch.up(armTravel(row));
                assertClosed(row);
                assertEquals(0, callbacks.get());
                touch.down();
                touch.move(armTravel(row));
                touch.secondPointer();
                assertClosed(row);
                touch.secondPointerUp();
                touch.move(armTravel(row));
                touch.up(armTravel(row));
                assertClosed(row);
                assertEquals(0, callbacks.get());
                CheckBox checkbox = (CheckBox) required(row, v -> v instanceof CheckBox);
                assertTrue("Checkbox must have a hit target", checkbox.getWidth() > 0 && checkbox.getHeight() > 0);
                Rect hit = new Rect(0, 0, checkbox.getWidth(), checkbox.getHeight());
                row.offsetDescendantRectToMyCoords(checkbox, hit);
                assertTrue("Checkbox center must be inside row: " + hit,
                        hit.exactCenterX() >= 0 && hit.exactCenterX() < row.getWidth()
                                && hit.exactCenterY() >= 0 && hit.exactCenterY() < row.getHeight());
                touch.tap(hit.exactCenterX(), hit.exactCenterY());
                assertEquals("A checkbox tap must not dispatch a swipe action", 0, callbacks.get());
            });
            // View.onTouchEvent may post PerformClick; let that callback run before checking persistence.
            idle();
            assertEquals("A checkbox tap must not dispatch a swipe action", 0, observed[0].get());
            assertEquals("Dispatched checkbox tap must complete its task after queued clicks drain",
                    TaskItem.Status.COMPLETED, repository.getTask(task.getId()).getStatus());
        }
    }

    @Test public void executedProposalKeepsInlineResultAndSingleUseUndoAcrossRecreation() {
        ChatMessage message = seedProposal();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitLayout(scenario);
            scenario.onActivity(activity -> {
                Button confirm = inlineButton(activity, "\u786e\u8ba4 1 \u9879");
                confirm.performClick();
                confirm.performClick();
            });
            idle();
            assertEquals(1, repository.getAllTasks().size());
            String createdId = repository.getAllTasks().get(0).getId();
            scenario.recreate();
            awaitLayout(scenario);
            scenario.onActivity(activity -> {
                ProposalView proposal = proposal(activity);
                ProposalPresentation state = field(proposal, "presentation", ProposalPresentation.class);
                assertEquals(message.getId(), state.messageId);
                assertEquals(ProposalPresentation.Outcome.SUCCEEDED, state.outcome);
                assertFalse(state.outcomeText.isEmpty());
                assertTrue(state.undoAvailable);
                assertNotNull(find(proposal, v -> v instanceof TextView
                        && state.outcomeText.contentEquals(((TextView) v).getText())));
                assertNull(find(proposal, v -> v instanceof Button
                        && "\u786e\u8ba4 1 \u9879".contentEquals(((Button) v).getText())));
                Button undo = inlineButton(activity, "\u64a4\u9500");
                undo.performClick();
                undo.performClick();
            });
            idle();
            assertNull(repository.getTask(createdId));
            assertTrue(repository.getAllTasks().isEmpty());
            assertEquals(ChatMessage.ActionItemState.REVERTED,
                    repository.getChatMessage(message.getId()).getActionItemState(0));
            scenario.recreate();
            awaitLayout(scenario);
            scenario.onActivity(activity -> {
                ProposalPresentation state = field(proposal(activity), "presentation", ProposalPresentation.class);
                assertEquals(ProposalPresentation.Outcome.REVERTED, state.outcome);
                assertFalse(state.undoAvailable);
                assertNull(find(proposal(activity), v -> v instanceof Button
                        && "\u64a4\u9500".contentEquals(((Button) v).getText())));
            });
        }
    }

    @Test public void staleInlineUndoCannotOverwriteInterveningRepositoryWrite() {
        ChatMessage message = seedProposal();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitLayout(scenario);
            scenario.onActivity(activity -> inlineButton(activity, "\u786e\u8ba4 1 \u9879").performClick());
            idle();
            String createdId = repository.getAllTasks().get(0).getId();
            String[] otherId = new String[1];
            scenario.onActivity(activity -> {
                Button staleUndo = inlineButton(activity, "\u64a4\u9500");
                otherId[0] = repository.upsertTask(new TaskItem("intervening local task")
                        .setRequiresTimeArrangement(false)).getId();
                // Keep the pre-write button: availability must be revalidated at execution time.
                staleUndo.performClick();
                staleUndo.performClick();
            });
            idle();
            assertNotNull(repository.getTask(createdId));
            assertNotNull(repository.getTask(otherId[0]));
            assertEquals(2, repository.getAllTasks().size());
            assertEquals(ChatMessage.ActionItemState.EXECUTED,
                    repository.getChatMessage(message.getId()).getActionItemState(0));
            scenario.recreate();
            awaitLayout(scenario);
            scenario.onActivity(activity -> {
                ProposalPresentation state = field(proposal(activity), "presentation", ProposalPresentation.class);
                assertEquals(ProposalPresentation.Outcome.SUCCEEDED, state.outcome);
                assertFalse(state.undoAvailable);
                assertFalse(state.undoUnavailableReason.isEmpty());
                assertNull(find(proposal(activity), v -> v instanceof Button
                        && "\u64a4\u9500".contentEquals(((Button) v).getText())));
            });
            assertEquals(2, repository.getAllTasks().size());
        }
    }

    private ActivityScenario<MainActivity> schedule() {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            activity.scheduleMode = TimeAssistantActivity.SCHEDULE_DAY;
            activity.showPage(1);
        });
        awaitLayout(scenario);
        scenario.onActivity(activity -> MotionSpec.cancel(activity.pageContainer));
        return scenario;
    }

    private ActivityScenario<MainActivity> todo() {
        ActivityScenario<MainActivity> scenario = schedule();
        scenario.onActivity(activity -> activity.findViewById(R.id.todoTabButton).performClick());
        awaitLayout(scenario);
        return scenario;
    }

    private TaskItem seedTodo() {
        return repository.upsertTask(new TaskItem("interaction fixture task")
                .setRequiresTimeArrangement(false).setPlannedDateAt(System.currentTimeMillis()));
    }

    private ChatMessage seedProposal() {
        return repository.addChatMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, "Local proposal",
                "{\"message\":\"Local proposal\",\"needsClarification\":false,\"actions\":["
                        + "{\"type\":\"create_task\",\"title\":\"local proposal task\"}]}",
                ChatMessage.ActionState.PENDING_CONFIRMATION));
    }

    private static ScheduleSwipeLayout attachedRow(MainActivity activity) {
        ScheduleSwipeLayout row = (ScheduleSwipeLayout) required(activity.pageContainer,
                v -> v instanceof ScheduleSwipeLayout);
        assertTrue(row.isAttachedToWindow());
        assertTrue(row.isShown());
        assertTrue(row.getWidth() > 0 && row.getHeight() > 0);
        assertEquals(View.LAYOUT_DIRECTION_LTR, row.getLayoutDirection());
        return row;
    }

    private static AtomicInteger countCallbacks(ScheduleSwipeLayout row) {
        ScheduleSwipeLayout.Callback original = field(row, "callback", ScheduleSwipeLayout.Callback.class);
        Object bound = field(row, "boundState", Object.class);
        AtomicInteger count = new AtomicInteger();
        setField(row, "callback", (ScheduleSwipeLayout.Callback) (action, captured) -> {
            assertSame(bound, captured);
            assertEquals(ScheduleSwipeLayout.Action.MAIN, action);
            count.incrementAndGet();
            original.onAction(action, captured);
        });
        return count;
    }

    private static float armTravel(View row) {
        return dp(row, 112) + (Math.min(dp(row, 96), row.getWidth() * .25f) + dp(row, 8)) / .55f;
    }

    private static void assertClosed(ScheduleSwipeLayout row) {
        assertFalse("Row remained open; translation=" + field(row, "content", View.class).getTranslationX()
                + ", dragging=" + field(row, "dragging", Boolean.class)
                + ", blocked=" + field(row, "blocked", Boolean.class), row.isOpen());
        MotionSpec.cancel(row);
        assertEquals(0f, field(row, "content", View.class).getTranslationX(), .01f);
        assertEquals(View.INVISIBLE, field(row, "actions", View.class).getVisibility());
        ScheduleSwipeGesture gesture = field(row, "gesture", ScheduleSwipeGesture.class);
        if (gesture != null) assertFalse(gesture.isArmed());
    }

    private static void captureView(View view, String name) {
        // Synthetic moves share a main-thread turn; perform the pending layout before drawing.
        if (view.isLayoutRequested()) {
            view.measure(View.MeasureSpec.makeMeasureSpec(view.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(view.getHeight(), View.MeasureSpec.EXACTLY));
            view.layout(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
        }
        java.io.File directory = new java.io.File(view.getContext().getExternalFilesDir(null), "interaction-frames");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
            view.draw(new Canvas(bitmap));
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException error) {
            throw new AssertionError("Cannot save interaction evidence", error);
        } finally {
            bitmap.recycle();
        }
    }

    private static void assertIndicatorBounds(SlidingSegmentedControl segments, float left) {
        Bitmap bitmap = Bitmap.createBitmap(segments.getWidth(), segments.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            segments.draw(new Canvas(bitmap));
            RectF bounds = segments.indicatorBounds();
            assertEquals(left, bounds.left, .01f);
            assertTrue(bounds.left >= segments.getPaddingLeft());
            assertTrue(bounds.right <= segments.getWidth() - segments.getPaddingRight());
            assertTrue(bounds.top >= 0 && bounds.bottom <= segments.getHeight());
            assertTrue(bounds.width() > 0 && bounds.height() > 0);
        } finally {
            bitmap.recycle();
        }
    }

    private static ProposalView proposal(MainActivity activity) {
        return (ProposalView) required(activity.pageContainer, v -> v instanceof ProposalView);
    }

    private static Button inlineButton(MainActivity activity, String label) {
        return (Button) required(proposal(activity), v -> v instanceof Button
                && label.contentEquals(((Button) v).getText()));
    }

    private static View required(View root, Predicate<View> predicate) {
        View found = find(root, predicate);
        assertNotNull("Required semantic view is missing", found);
        return found;
    }

    private static View find(View root, Predicate<View> predicate) {
        if (predicate.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), predicate);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Existing UI field changed: " + name, error);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Existing UI field changed: " + name, error);
        }
    }

    private static float dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void awaitLayout(ActivityScenario<MainActivity> scenario) {
        idle();
        CountDownLatch drawn = new CountDownLatch(1);
        View[] root = new View[1];
        ViewTreeObserver.OnPreDrawListener[] listener = new ViewTreeObserver.OnPreDrawListener[1];
        scenario.onActivity(activity -> {
            root[0] = activity.getWindow().getDecorView();
            listener[0] = () -> {
                if (!root[0].isLaidOut() || root[0].isLayoutRequested()
                        || root[0].getWidth() == 0 || root[0].getHeight() == 0) return true;
                root[0].getViewTreeObserver().removeOnPreDrawListener(listener[0]);
                drawn.countDown();
                return true;
            };
            root[0].getViewTreeObserver().addOnPreDrawListener(listener[0]);
            root[0].requestLayout();
            root[0].invalidate();
        });
        try {
            assertTrue("Activity did not reach a laid-out frame", drawn.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting layout", interrupted);
        } finally {
            scenario.onActivity(activity -> {
                if (root[0].getViewTreeObserver().isAlive()) {
                    root[0].getViewTreeObserver().removeOnPreDrawListener(listener[0]);
                }
            });
        }
        idle();
    }

    /** Events go through the attached row's real child interception/dispatch, never onTouchEvent. */
    private static final class Touch {
        private final ScheduleSwipeLayout row;
        private float x;
        private final float y;
        private long downTime, eventTime;

        Touch(ScheduleSwipeLayout row) {
            this.row = row;
            x = row.getWidth() - dp(row, 8);
            y = row.getHeight() / 2f;
        }

        void down() {
            downTime = eventTime = SystemClock.uptimeMillis();
            send(MotionEvent.ACTION_DOWN, 0);
        }

        void downAt(float startX) {
            x = startX;
            down();
        }

        void move(float travel) {
            send(MotionEvent.ACTION_MOVE, travel);
            send(MotionEvent.ACTION_MOVE, travel);
        }

        void up(float travel) { send(MotionEvent.ACTION_UP, travel); }

        void send(int action, float travel) {
            MotionEvent event = MotionEvent.obtain(downTime, eventTime += 16, action, x - travel, y, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { row.dispatchTouchEvent(event); } finally { event.recycle(); }
        }

        void secondPointer() { pointers(MotionEvent.ACTION_POINTER_DOWN); }

        void secondPointerUp() { pointers(MotionEvent.ACTION_POINTER_UP); }

        private void pointers(int action) {
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
            MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
            for (int i = 0; i < 2; i++) {
                properties[i] = new MotionEvent.PointerProperties();
                properties[i].id = i;
                properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coordinates[i] = new MotionEvent.PointerCoords();
                coordinates[i].x = x - armTravel(row) + i * dp(row, 8);
                coordinates[i].y = y;
                coordinates[i].pressure = coordinates[i].size = 1;
            }
            MotionEvent event = MotionEvent.obtain(downTime, eventTime += 16,
                    action | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    2, properties, coordinates, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try { row.dispatchTouchEvent(event); } finally { event.recycle(); }
        }

        void tap(float targetX, float targetY) {
            long time = SystemClock.uptimeMillis();
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                MotionEvent event = MotionEvent.obtain(time, time + (action == MotionEvent.ACTION_UP ? 32 : 0),
                        action, targetX, targetY, 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try {
                    assertTrue("Checkbox event not handled: " + MotionEvent.actionToString(action),
                            row.dispatchTouchEvent(event));
                } finally { event.recycle(); }
            }
        }
    }
}
