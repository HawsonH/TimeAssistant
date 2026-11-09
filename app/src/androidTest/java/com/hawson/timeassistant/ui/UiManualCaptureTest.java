package com.hawson.timeassistant.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.ChatMessage;
import com.hawson.timeassistant.domain.RecurringTaskGroup;
import com.hawson.timeassistant.domain.TaskItem;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Manual screenshots use fictional fixtures and are restricted to emulators. */
@RunWith(AndroidJUnit4.class)
public class UiManualCaptureTest {
    private final Context context = ApplicationProvider.getApplicationContext();
    private void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        android.os.SystemClock.sleep(420);
    }
    @Test public void captureFeatureWalkthroughs() throws Exception {
        com.hawson.timeassistant.DeviceFixtureSafety.requireIsolatedTarget(context);
        assertTrue(context.getPackageName().endsWith(".qa"));
        AppRepository repository = AppRepository.get(context);
        repository.clearAll();
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().clear()
                .putBoolean("intro_pending", true).commit();
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().putString(AppTheme.PREFERENCE_KEY, "neutral")
                .putInt("last_page", 0).commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            idle(); capture("manual-onboarding"); click("跳过教程");
        }
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().putInt("seen_version", 1)
                .putBoolean("intro_pending", false).putBoolean("context_enabled", false).commit();
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().putString(AppTheme.PREFERENCE_KEY, "neutral")
                .putInt("last_page", 0).putBoolean("focus_lockscreen_settings_confirmed_v2", true).commit();
        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 20); day.set(Calendar.MINUTE, 0); day.set(Calendar.SECOND, 0);
        long start = day.getTimeInMillis();
        TaskItem scheduled = repository.upsertTask(new TaskItem("复习线性代数并整理课堂笔记")
                .setRequiresTimeArrangement(true).setScheduleFields(start, start, start + 3_600_000, false));
        repository.upsertTask(new TaskItem("整理小组交流材料").setRequiresTimeArrangement(false));
        repository.upsertTask(new TaskItem("补充实验报告时间安排").setRequiresTimeArrangement(true));
        repository.upsertRecurringGroup(new RecurringTaskGroup("每周阅读与复盘", start + 3_600_000,
                1_800_000, RecurringTaskGroup.Unit.WEEKS, 1));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            idle(); capture("01-chat");
            scenario.onActivity(activity -> activity.findViewById(R.id.settingsButton).performClick());
            idle(); capture("02-chat-menu"); back();
            repository.addChatMessage(new ChatMessage(ChatMessage.Role.USER, "帮我把本周复习内容分成四件事。"));
            repository.addChatMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, "可以按以下顺序完成，确认后加入待办。",
                    "{\"message\":\"复习安排\",\"needsClarification\":false,\"actions\":["
                    + "{\"type\":\"create_task\",\"title\":\"回顾课堂笔记\"},"
                    + "{\"type\":\"create_task\",\"title\":\"整理易错题\"},"
                    + "{\"type\":\"create_task\",\"title\":\"完成章节练习\"},"
                    + "{\"type\":\"create_task\",\"title\":\"准备小组交流\"}]}", ChatMessage.ActionState.PENDING_CONFIRMATION));
            idle(); capture("03-proposal-preview"); click("查看全部 4 项"); capture("04-proposal-expanded");
            click("确认 4 项"); capture("05-proposal-confirm"); click("继续执行");
            idle(); capture("06-proposal-result");
            scenario.onActivity(activity -> { activity.scheduleMode = 0; activity.showPage(1); });
            idle(); capture("10-schedule");
            scenario.onActivity(activity -> activity.findViewById(R.id.selectedDateButton).performClick());
            idle(); capture("11-calendar"); back();
            scenario.onActivity(TimeAssistantActivity::showManualTaskDialog); idle(); capture("12-create-task");
            click("重复与备注"); capture("13-task-advanced");
            click("周期日程"); clickContains("每周"); capture("14-recurrence-rule"); back(); back();
            // No draft field is changed; cancel leaves the seeded tasks intact.
            dismissDraftIfPresent();
            scenario.onActivity(activity -> activity.showTaskDetails(scheduled.getId())); idle(); capture("15-edit-task"); back();
            scenario.onActivity(TimeAssistantActivity::showTodoTaskDialog); idle(); capture("16-create-todo");
            click("提醒与备注"); capture("17-todo-reminder"); click("倒计时");
            capture("18-todo-duration"); back(); back(); dismissDraftIfPresent();
            scenario.onActivity(activity -> { activity.scheduleMode = TimeAssistantActivity.SCHEDULE_TODO; activity.showPage(1); activity.renderCurrentPage(); });
            idle(); capture("19-todos");
            click("注意"); capture("20-attention"); click("日程");
            click("管理周期 ›"); capture("21-recurring-actions"); click("修改…");
            capture("22-recurring-scope"); back();
            scenario.onActivity(activity -> activity.showPage(2)); idle(); capture("30-focus-ready");
            scenario.onActivity(activity -> activity.findViewById(R.id.timerRing).performClick()); idle(); capture("31-focus-duration"); back();
            scenario.onActivity(activity -> activity.findViewById(R.id.focusTaskTitle).performClick()); idle(); capture("32-focus-task"); back();
            scenario.onActivity(activity -> activity.findViewById(R.id.timerButton).performClick()); idle(); capture("33-focus-running");
            scenario.onActivity(activity -> activity.findViewById(R.id.timerButton).performClick()); idle(); capture("34-focus-paused");
            scenario.onActivity(activity -> activity.findViewById(R.id.endFocusButton).performClick()); idle(); capture("35-focus-end");
            click("确认结束"); capture("36-focus-finished");
            scenario.onActivity(activity -> activity.findViewById(R.id.focusSettingsButton).performClick()); idle();
            click("专注设置"); capture("37-focus-settings");
            revealSection("衔接"); capture("38-focus-automation"); revealSection("声振"); capture("39-focus-signals");
            revealSection("记录"); capture("40-focus-history");
            scenario.onActivity(TimeAssistantActivity::closeSettings); idle();
            scenario.onActivity(TimeAssistantActivity::openSettings); idle(); capture("50-settings");
            String[] sections = {"外观与教程", "AI 连接与账号", "主动整理", "安排偏好与通知", "日历连接", "同步与数据"};
            String[] names = {"51-appearance", "52-connections", "53-proactive", "54-preferences", "55-calendar-settings", "56-data"};
            for (int i = 0; i < sections.length; i++) {
                click(sections[i]); capture(names[i]);
                if (i == 1) {
                    click("添加连接"); capture("57-add-connection"); click("官方 API Key"); capture("58-api-editor");
                    click("返回连接列表"); dismissDraftIfPresent();
                    click("添加连接"); click("自定义服务"); capture("59-custom-editor");
                    click("返回连接列表"); dismissDraftIfPresent();
                }
                if (i == 2 || i == 3 || i == 5) {
                    scenario.onActivity(activity -> ((ScrollView) activity.findViewById(R.id.settingsScroll)).fullScroll(View.FOCUS_DOWN));
                    idle(); capture(names[i] + "-bottom");
                }
                if (i == 5) {
                    click("加入设备"); capture("60-join-device"); back();
                }
                scenario.onActivity(activity -> activity.findViewById(R.id.backButton).performClick()); idle();
            }
            scenario.onActivity(TimeAssistantActivity::closeSettings); idle();
        }
    }
    @Test public void captureSettingsLayout() throws Exception {
        String variant = InstrumentationRegistry.getArguments().getString("settingsVariant", "");
        org.junit.Assume.assumeFalse(variant.isEmpty());
        com.hawson.timeassistant.DeviceFixtureSafety.requireIsolatedTarget(context);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { activity.closeSettings(); activity.showPage(2); }); idle();
            scenario.onActivity(activity -> activity.findViewById(R.id.focusSettingsButton).performClick()); idle();
            click("专注设置"); capture(variant + "-focus-settings");
            revealSection("衔接"); capture(variant + "-focus-automation");
            revealSection("声振"); capture(variant + "-focus-signals");
            scenario.onActivity(TimeAssistantActivity::closeSettings); idle();
            scenario.onActivity(TimeAssistantActivity::openSettings); idle();
            click("外观与教程"); capture(variant + "-appearance");
            scenario.onActivity(activity -> activity.findViewById(R.id.backButton).performClick()); idle();
            click("AI 连接与账号"); click("添加连接"); click("自定义服务");
            capture(variant + "-connection");
            click("返回连接列表"); dismissDraftIfPresent();
            scenario.onActivity(TimeAssistantActivity::closeSettings); idle();
        }
    }
    private void revealSection(String title) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View heading = find(title, false);
            ScrollView scroll = (ScrollView) WorkflowDriver.find(v -> v.getId() == R.id.settingsScroll);
            assertNotNull("Missing section: " + title, heading); assertNotNull(scroll);
            scroll.scrollTo(0, Math.max(0, heading.getTop()
                    - Math.round(16 * heading.getResources().getDisplayMetrics().density)));
        });
        idle();
    }
    private void dismissDraftIfPresent() {
        if (has("放弃修改")) click("放弃修改");
        if (has("放弃")) click("放弃");
    }
    private void back() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        idle();
    }
    private boolean has(String text) {
        boolean[] found = {false};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> found[0] = find(text, false) != null);
        return found[0];
    }
    private void click(String text) { click(text, false); }
    private void clickContains(String text) { click(text, true); }
    private void click(String text, boolean partial) {
        View[] target = {null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View view = find(text, partial);
            assertNotNull("Missing operation: " + text, view);
            view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true);
            target[0] = view;
        });
        idle();
        androidx.test.espresso.Espresso.onView(org.hamcrest.Matchers.is(target[0]))
                .perform(androidx.test.espresso.action.ViewActions.click());
        idle();
    }
    private View find(String text, boolean partial) {
        java.util.List<View> windows = WindowInspector.getGlobalWindowViews();
        for (int i = windows.size() - 1; i >= 0; i--) {
            View result = find(windows.get(i), text, partial);
            if (result != null) return result;
        }
        return null;
    }
    private View find(View view, String text, boolean partial) {
        if (!view.isShown()) return null;
        if (view instanceof TextView) {
            String value = ((TextView) view).getText().toString();
            if (partial ? value.contains(text) : value.equals(text)) return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = find(group.getChildAt(i), text, partial);
                if (result != null) return result;
            }
        }
        return null;
    }
    private void capture(String name) throws Exception {
        idle();
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = new File(context.getExternalFilesDir(null), "ui-manual");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }
}
