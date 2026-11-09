package com.hawson.timeassistant.ui;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.design.AppearancePreferences;
import com.hawson.timeassistant.design.MotionSpec;
import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.ChatMessage;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;
import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Explicit opt-in capture against the real QA activity with fictional local data. */
@RunWith(AndroidJUnit4.class)
public class UiVisualCaptureTest {
    private final Context context = ApplicationProvider.getApplicationContext();
    private void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(300);
    }
    @Test public void captureSinglePageOnboarding() throws Exception {
        String variant = InstrumentationRegistry.getArguments().getString("captureVariant", "");
        Assume.assumeFalse(variant.isEmpty());
        context.getSharedPreferences(com.hawson.timeassistant.testing.TutorialHarnessActivity.TEST_PREFS, 0)
                .edit().clear().commit();
        try (ActivityScenario<com.hawson.timeassistant.testing.TutorialHarnessActivity> scenario =
                     ActivityScenario.launch(com.hawson.timeassistant.testing.TutorialHarnessActivity.class)) {
            idle(); capture(variant + "-onboarding");
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("跳过教程"))
                    .perform(androidx.test.espresso.action.ViewActions.click());
        }
    }
    @Test public void captureRealScreens() throws Exception {
        String variant = InstrumentationRegistry.getArguments().getString("captureVariant", "");
        Assume.assumeFalse(variant.isEmpty());
        assertTrue(context.getPackageName().endsWith(".qa"));
        com.hawson.timeassistant.DeviceFixtureSafety.requireIsolatedTarget(context);
        WorkflowDriver.requireUnlockedDisplay(context);
        AppRepository repository = AppRepository.get(context);
        repository.clearAll();
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().putInt("seen_version", 1)
                .putBoolean("intro_pending", false).putBoolean("context_enabled", false).commit();
        Calendar date = Calendar.getInstance();
        date.add(Calendar.HOUR_OF_DAY, 2);
        date.set(Calendar.SECOND, 0); date.set(Calendar.MILLISECOND, 0);
        long start = date.getTimeInMillis();
        repository.upsertTask(new TaskItem("阅读与整理课堂笔记").setRequiresTimeArrangement(true)
                .setScheduleFields(start, start, start + 3_600_000, false));
        repository.upsertTask(new TaskItem("完成线性代数第三章练习并整理易错题，准备周末的小组交流")
                .setRequiresTimeArrangement(false));
        String[] themes = variant.equals("matrix")
                ? new String[]{"neutral", "violet", "ocean", "forest", "clay"} : new String[]{"neutral"};
        String captureTheme = InstrumentationRegistry.getArguments().getString("captureTheme", "");
        if (!captureTheme.isEmpty()) themes = new String[]{captureTheme};
        for (String theme : themes) {
            for (int night : variant.equals("matrix") ? new int[]{1, 2} : new int[]{1}) {
                context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit()
                        .putString(AppTheme.PREFERENCE_KEY, theme).putInt("last_page", 0)
                        .putBoolean("focus_lockscreen_settings_confirmed_v2", true).commit();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                        new AppearancePreferences(context).setNightMode(night));
                for (MotionSpec.Mode motion : variant.equals("matrix") ? MotionSpec.Mode.values()
                        : new MotionSpec.Mode[]{MotionSpec.Mode.FULL}) {
                    new AppearancePreferences(context).setMotionMode(motion);
                    String prefix = variant + "-" + theme + "-" + night + "-" + motion.name();
                    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                        idle();
                        if (variant.equals("refinement")) {
                            captureRefinements(scenario, repository, prefix);
                            continue;
                        }
                        if (variant.equals("interaction")) {
                            captureInteractions(scenario, repository, prefix);
                            continue;
                        }
                        if (motion == MotionSpec.Mode.FULL) capture(prefix + "-chat");
                        scenario.onActivity(activity -> { activity.selectedDate.setTimeInMillis(start); activity.showPage(1); }); idle();
                        if (motion == MotionSpec.Mode.FULL) capture(prefix + "-schedule");
                        scenario.onActivity(activity -> activity.showPage(2)); idle();
                        capture(prefix + "-focus");
                        if (motion == MotionSpec.Mode.FULL) {
                            scenario.onActivity(activity -> activity.findViewById(R.id.timerRing).performClick()); idle();
                            capture(prefix + "-duration");
                            androidx.test.espresso.Espresso.pressBack(); idle();
                            scenario.onActivity(TimeAssistantActivity::openSettings); idle();
                            capture(prefix + "-settings");
                            scenario.onActivity(activity -> {
                                View appearance = find(activity.pageContainer, "外观与教程");
                                assertNotNull(appearance);
                                View clickable = appearance;
                                while (!clickable.isClickable()) clickable = (View) clickable.getParent();
                                clickable.performClick();
                            }); idle();
                            capture(prefix + "-appearance");
                        }
                    }
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                new AppearancePreferences(context).setNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        new AppearancePreferences(context).setMotionMode(MotionSpec.Mode.FULL);
    }
    private void captureRefinements(ActivityScenario<MainActivity> scenario, AppRepository repository,
                                    String prefix) throws Exception {
        String payload = "{\"message\":\"复习安排\",\"needsClarification\":false,\"actions\":[{\"type\":\"create_task\",\"title\":\"阅读课堂笔记\"},"
                + "{\"type\":\"create_task\",\"title\":\"整理易错题\"},"
                + "{\"type\":\"create_task\",\"title\":\"完成章节练习\"},"
                + "{\"type\":\"create_task\",\"title\":\"准备小组交流\"}]}";
        repository.addChatMessage(new ChatMessage(ChatMessage.Role.USER, "帮我把复习内容整理成四件事。"));
        repository.addChatMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, "可以按下面的顺序完成，确认后加入待办。",
                payload, ChatMessage.ActionState.PENDING_CONFIRMATION));
        repository.addChatMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, "今天可以留出一段复习时间。",
                "{\"message\":\"今日建议\",\"needsClarification\":false,\"actions\":[{\"type\":\"create_task\",\"title\":\"回顾今日笔记\"}]}",
                ChatMessage.ActionState.PENDING_CONFIRMATION, ChatMessage.ProposalKind.PROACTIVE_PLAN,
                java.util.Collections.emptyList()));
        scenario.onActivity(activity -> activity.showPage(0)); idle();
        capture(prefix + "-proposal-collapsed");
        scenario.onActivity(activity -> {
            assertNull(find(activity.pageContainer, "确认 4 项"));
            clickText(activity.pageContainer, "查看全部 4 项");
        }); idle(); capture(prefix + "-proposal-expanded");
        scenario.onActivity(activity -> clickText(activity.pageContainer, "确认 4 项")); idle();
        capture(prefix + "-danger-confirmation");
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("暂不执行"))
                .perform(androidx.test.espresso.action.ViewActions.click()); idle();
        scenario.onActivity(activity -> {
            View confirm = find(activity.pageContainer, "确认 4 项");
            assertNotNull(confirm); assertTrue(confirm.isEnabled());
            clickText(activity.pageContainer, "今日建议 · 1");
        }); idle(); capture(prefix + "-daily-suggestion");
        scenario.onActivity(TimeAssistantActivity::openSettings); idle();
        scenario.onActivity(activity -> clickText(activity.pageContainer, "AI 连接与账号")); idle();
        capture(prefix + "-connection-list");
        scenario.onActivity(activity -> clickText(activity.pageContainer, "添加连接")); idle();
        capture(prefix + "-connection-types");
        pressSystemBack(); idle();
    }

    private void captureInteractions(ActivityScenario<MainActivity> scenario, AppRepository repository,
                                     String prefix) throws Exception {
        // This path is deliberately short: adb screenrecord can pause the app's activity
        // on some emulator images, so it avoids Espresso's resumed-activity requirement.
        scenario.onActivity(TimeAssistantActivity::closeSettings); idle();
        scenario.onActivity(activity -> { activity.scheduleMode = 1; activity.showPage(1); }); idle();
        capture(prefix + "-todos-long-text");
        scenario.onActivity(TimeAssistantActivity::showManualTaskDialog); idle();
        capture(prefix + "-create-task");
        pressSystemBack();
        idle();
        repository.addChatMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, "可以先完成阅读，再用一段专注整理笔记。",
                "{\"message\":\"安排建议\",\"needsClarification\":false,\"actions\":[{\"type\":\"create_task\",\"title\":\"复习本周知识点\"},{\"type\":\"create_task\",\"title\":\"整理易错题\"}]}",
                ChatMessage.ActionState.PENDING_CONFIRMATION));
        scenario.onActivity(activity -> activity.showPage(0)); idle(); capture(prefix + "-proposal");
        scenario.onActivity(activity -> activity.showPage(2)); idle();
        scenario.onActivity(activity -> activity.findViewById(R.id.focusTaskTitle).performClick()); idle();
        capture(prefix + "-task-search");
        pressSystemBack(); idle();
        scenario.onActivity(activity -> activity.findViewById(R.id.timerButton).performClick()); idle();
        String session = repository.getCurrentFocus().getSessionId();
        capture(prefix + "-running");
        scenario.onActivity(activity -> activity.findViewById(R.id.timerButton).performClick()); idle();
        assertEquals(FocusState.State.PAUSED, repository.getCurrentFocus().getState());
        capture(prefix + "-paused");
        scenario.onActivity(activity -> activity.findViewById(R.id.timerButton).performClick()); idle();
        assertEquals(FocusState.State.RUNNING, repository.getCurrentFocus().getState());
        assertEquals(session, repository.getCurrentFocus().getSessionId());
        capture(prefix + "-resumed");
        // Let the genuine focus notification's heads-up banner clear before the menu evidence.
        SystemClock.sleep(4_000);
        scenario.onActivity(activity -> activity.findViewById(R.id.focusSettingsButton).performClick()); idle();
        capture(prefix + "-focus-more");
        pressSystemBack();
    }
    private void pressSystemBack() {
        assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_BACK));
    }
    private void clickText(View root, String value) {
        View view = find(root, value);
        assertNotNull(view);
        while (!view.isClickable()) view = (View) view.getParent();
        view.performClick();
    }
    private void capture(String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        assertTrue(bitmap.getWidth() > 0 && bitmap.getHeight() > 0);
        File directory = new File(context.getExternalFilesDir(null), "ui-redesign");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        android.util.Log.i("UiVisualCapture", name + ": " + bitmap.getWidth() + "x" + bitmap.getHeight());
        bitmap.recycle();
    }
    private View find(View root, String value) {
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = find(group.getChildAt(index), value);
                if (found != null) return found;
            }
        }
        return null;
    }
}
