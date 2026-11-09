package com.hawson.timeassistant.ui;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.DeviceFixtureSafety;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.design.AppearancePreferences;
import com.hawson.timeassistant.design.MotionSpec;
import com.hawson.timeassistant.domain.ChatMessage;
import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.proactive.ProactiveSettings;
import com.hawson.timeassistant.ui.settings.ProactiveSettingsView;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Fictional local screenshots only. Never schedules work, configures a service or sends AI requests. */
@RunWith(AndroidJUnit4.class)
public class InteractionVisualCaptureTest {
    @Test public void captureFiveRefinements() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        DeviceFixtureSafety.requireIsolatedTarget(context);
        assertEquals("com.hawson.timeassistant.interaction.qa", context.getPackageName());
        WorkflowDriver.requireUnlockedDisplay(context);
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().putInt("seen_version", 1)
                .putBoolean("intro_pending", false).putBoolean("context_enabled", false).commit();
        String variant = InstrumentationRegistry.getArguments().getString("uiVariant", "default");
        String theme = InstrumentationRegistry.getArguments().getString("captureTheme", "neutral");
        int night = Integer.parseInt(InstrumentationRegistry.getArguments().getString("captureNight", "1"));
        MotionSpec.Mode motion = MotionSpec.Mode.valueOf(
                InstrumentationRegistry.getArguments().getString("captureMotion", "FULL"));
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().putString("theme_id", theme)
                .putInt("last_page", 0).putBoolean("focus_lockscreen_settings_confirmed_v2", true).commit();
        AppearancePreferences appearance = new AppearancePreferences(context);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> appearance.setNightMode(night));
        appearance.setMotionMode(motion);
        AppRepository repository = AppRepository.get(context);
        repository.clearAll();
        repository.upsertTask(new TaskItem("完成章节练习并整理易错题，准备小组交流")
                .setRequiresTimeArrangement(false).setPlannedDateAt(System.currentTimeMillis()));
        ChatMessage message = repository.addChatMessage(new ChatMessage(ChatMessage.Role.ASSISTANT,
                "可以先阅读资料，再整理重点。确认后将加入待办。",
                "{\"message\":\"本地建议\",\"needsClarification\":false,\"actions\":["
                        + "{\"type\":\"create_task\",\"title\":\"阅读项目资料并整理重点\"}]}",
                ChatMessage.ActionState.PENDING_CONFIRMATION));
        String prefix = "refine-" + variant + "-" + theme + "-" + night + "-" + motion;
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            if (variant.equals("landscape")) {
                screen.onActivity(a -> a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                WorkflowDriver.awaitCondition("landscape", () -> context.getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
            }
            WorkflowDriver.idle();
            screen.onActivity(a -> {
                a.showPage(0);
                a.renderCurrentPage();
            });
            WorkflowDriver.idle();
            WorkflowDriver.capture(prefix + "-proposal-pending");
            screen.onActivity(a -> {
                View confirm = findText(a.pageContainer, "确认 1 项");
                assertNotNull(confirm);
                confirm.performClick();
            });
            WorkflowDriver.idle();
            assertEquals(ChatMessage.ActionItemState.EXECUTED,
                    repository.getChatMessage(message.getId()).getActionItemState(0));
            WorkflowDriver.capture(prefix + "-proposal-result");
            if (variant.equals("landscape")) {
                assertBubbleActionReachable(screen, "撤销");
                WorkflowDriver.capture(prefix + "-proposal-undo-visible");
                assertBubbleActionReachable(screen, "查看安排");
                WorkflowDriver.capture(prefix + "-proposal-view-visible");
            }
            screen.onActivity(a -> a.showPage(1));
            WorkflowDriver.idle();
            screen.onActivity(a -> a.findViewById(R.id.todoTabButton).performClick());
            WorkflowDriver.idle();
            WorkflowDriver.capture(prefix + "-todos");
            screen.onActivity(TimeAssistantActivity::showManualTaskDialog);
            WorkflowDriver.idle();
            WorkflowDriver.capture(prefix + "-task-form");
            if ("true".equals(InstrumentationRegistry.getArguments().getString("captureKeyboard"))) {
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withHint("任务标题"))
                        .perform(androidx.test.espresso.action.ViewActions.click());
                WorkflowDriver.awaitCondition("visible software keyboard", () -> {
                    View editor = WorkflowDriver.find(v -> v instanceof android.widget.EditText
                            && "任务标题".contentEquals(((android.widget.EditText) v).getHint()));
                    androidx.core.view.WindowInsetsCompat insets = editor == null ? null
                            : androidx.core.view.ViewCompat.getRootWindowInsets(editor);
                    return insets != null && insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
                });
                WorkflowDriver.capture(prefix + "-task-keyboard");
                androidx.test.espresso.Espresso.closeSoftKeyboard();
                WorkflowDriver.idle();
            }
            androidx.test.espresso.Espresso.pressBack();
            WorkflowDriver.idle();
            screen.onActivity(a -> {
                ProactiveSettings settings = new ProactiveSettings();
                settings.enabled = true;
                settings.addTodoReviewSlot(18 * 60);
                ProactiveSettingsView view = new ProactiveSettingsView(a, settings, true, true,
                        new ProactiveSettingsView.Callbacks() {
                    public void save(ProactiveSettings ignored) { }
                    public void requestMasterEnabled(boolean value, java.util.function.Consumer<Boolean> result) { result.accept(value); }
                    public void requestNotifications(boolean value, java.util.function.Consumer<Boolean> result) { result.accept(value); }
                    public void runNow() { throw new AssertionError("No background request allowed"); }
                    public void showMessage(String text) { }
                });
                ScrollView scroll = new ScrollView(a);
                int inset = com.hawson.timeassistant.design.UiKit.dp(a, 16);
                scroll.setPadding(inset, 0, inset, inset);
                scroll.setClipToPadding(false);
                scroll.addView(view);
                a.pageContainer.removeAllViews();
                a.pageContainer.addView(scroll);
            });
            WorkflowDriver.idle();
            WorkflowDriver.capture(prefix + "-review-times");
        }
    }

    private static void assertBubbleActionReachable(ActivityScenario<MainActivity> screen, String label) {
        screen.onActivity(a -> {
            View action = findText(a.pageContainer, label);
            assertNotNull("Missing inline action: " + label, action);
            android.view.ViewParent parent = action.getParent();
            while (parent instanceof View && !(parent instanceof ProposalView)) parent = parent.getParent();
            assertTrue("Action must remain inside proposal: " + label, parent instanceof ProposalView);
            action.requestRectangleOnScreen(new android.graphics.Rect(0, 0,
                    action.getWidth(), action.getHeight()), true);
        });
        WorkflowDriver.idle();
        screen.onActivity(a -> {
            View action = findText(a.pageContainer, label);
            android.graphics.Rect visible = new android.graphics.Rect();
            assertTrue("Inline action must be reachable: " + label, action.getLocalVisibleRect(visible));
            assertEquals("Inline action height clipped: " + label, action.getHeight(), visible.height());
            assertEquals("Inline action width clipped: " + label, action.getWidth(), visible.width());
            assertTrue("Inline action must be enabled: " + label, action.isEnabled());
        });
    }

    private static View findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
