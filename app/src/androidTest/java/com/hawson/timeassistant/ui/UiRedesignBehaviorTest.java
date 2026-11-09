package com.hawson.timeassistant.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.design.FormDraftGuard;
import com.hawson.timeassistant.domain.ChatMessage;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.domain.TaskItem;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.assertion.ViewAssertions.matches;

@RunWith(AndroidJUnit4.class)
public class UiRedesignBehaviorTest {
    private AppRepository repository;
    @Before public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        assertTrue(context.getPackageName().endsWith(".qa"));
        repository = AppRepository.get(context);
        repository.clearAll();
        context.getSharedPreferences("time_assistant_tutorial", 0).edit()
                .putInt("seen_version", 1).putBoolean("intro_pending", false)
                .putBoolean("context_enabled", false).commit();
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().clear()
                .putBoolean("focus_lockscreen_settings_confirmed_v2", true).commit();
    }
    @After public void tearDown() { repository.clearAll(); }
    private void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }

    @Test public void scheduleSelectionSurvivesTabsAndRecreation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            long[] date = {0};
            scenario.onActivity(activity -> {
                activity.selectedDate.add(java.util.Calendar.DATE, 4);
                date[0] = activity.selectedDate.getTimeInMillis();
                activity.scheduleMode = 1;
                activity.showPage(1);
            }); idle();
            scenario.onActivity(activity -> activity.showPage(2)); idle();
            scenario.onActivity(activity -> activity.showPage(1)); idle();
            scenario.recreate(); idle();
            scenario.onActivity(activity -> {
                assertEquals(date[0], activity.selectedDate.getTimeInMillis());
                assertEquals(1, activity.scheduleMode);
                View previous = activity.pageContainer.getChildAt(0);
                activity.showPage(1);
                assertSame(previous, activity.pageContainer.getChildAt(0));
            });
        }
    }

    @Test public void chatDraftSurvivesTabsAndRecreation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            idle();
            scenario.onActivity(activity -> activity.chatInput.setText("明天下午复习线性代数"));
            scenario.onActivity(activity -> activity.showPage(1)); idle();
            scenario.onActivity(activity -> activity.showPage(0)); idle();
            scenario.recreate(); idle();
            scenario.onActivity(activity -> assertEquals("明天下午复习线性代数", activity.chatInput.getText().toString()));
        }
    }

    @Test public void emptyChatKeepsOnlyTheConversationSurface() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            idle();
            scenario.onActivity(activity -> {
                activity.showPage(0);
                assertNull(findText(activity.pageContainer, "今天想先处理什么？"));
                assertNull(findText(activity.pageContainer, "新建任务"));
                assertNull(findText(activity.pageContainer, "开始专注"));
            });
        }
    }

    @Test public void calendarReturnsToTheModeThatOpenedIt() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                activity.scheduleMode = TimeAssistantActivity.SCHEDULE_TODO;
                activity.showPage(1);
            });
            idle();
            scenario.onActivity(activity -> activity.findViewById(R.id.selectedDateButton).performClick());
            idle();
            scenario.onActivity(activity -> {
                assertEquals(TimeAssistantActivity.SCHEDULE_CALENDAR, activity.scheduleMode);
                View returnToday = findText(activity.pageContainer, "回到今天");
                assertNotNull(returnToday);
                returnToday.performClick();
            }); idle();
            scenario.onActivity(activity -> {
                assertEquals(TimeAssistantActivity.SCHEDULE_TODO, activity.scheduleMode);
                assertNotNull(findText(activity.pageContainer, "待办"));
            });
        }
    }

    @Test public void systemBackFromCalendarReturnsToPreviousScheduleMode() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                activity.scheduleMode = TimeAssistantActivity.SCHEDULE_TODO;
                activity.showPage(1);
            }); idle();
            scenario.onActivity(activity -> activity.findViewById(R.id.selectedDateButton).performClick()); idle();
            scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed()); idle();
            scenario.onActivity(activity -> {
                assertEquals(1, activity.currentPage);
                assertEquals(TimeAssistantActivity.SCHEDULE_TODO, activity.scheduleMode);
                assertNotNull(activity.findViewById(R.id.selectedDateButton));
            });
        }
    }

    @Test public void settingsSubpageHasOneTitleAndReturnsToMenu() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(TimeAssistantActivity::openSettings); idle();
            scenario.onActivity(activity -> {
                View row = findText(activity.pageContainer, "安排偏好与通知");
                while (!row.isClickable()) row = (View) row.getParent();
                row.performClick();
            }); idle();
            scenario.onActivity(activity -> {
                assertEquals("安排偏好与通知", ((TextView) activity.findViewById(R.id.settingsTitle)).getText().toString());
                assertEquals(1, countText(activity.pageContainer, "安排偏好与通知"));
                activity.findViewById(R.id.backButton).performClick();
            }); idle();
            scenario.onActivity(activity -> assertEquals("设置", ((TextView) activity.findViewById(R.id.settingsTitle)).getText().toString()));
        }
    }

    private int countText(View root, String value) {
        int count = root instanceof TextView && value.contentEquals(((TextView) root).getText()) && root.isShown() ? 1 : 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) count += countText(group.getChildAt(i), value);
        }
        return count;
    }

    @Test public void focusPauseResumeRetainsSessionAndCreatesNoRecord() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            repository.setCurrentFocus(FocusState.ready(null, FocusState.Mode.FOCUS, 600_000, 1)
                    .startOrResume(System.currentTimeMillis()));
            String id = repository.getCurrentFocus().getSessionId();
            scenario.onActivity(activity -> activity.showPage(2)); idle();
            scenario.onActivity(activity -> activity.findViewById(R.id.timerButton).performClick()); idle();
            assertEquals(FocusState.State.PAUSED, repository.getCurrentFocus().getState());
            scenario.onActivity(activity -> activity.findViewById(R.id.timerButton).performClick()); idle();
            assertEquals(FocusState.State.RUNNING, repository.getCurrentFocus().getState());
            assertEquals(id, repository.getCurrentFocus().getSessionId());
            assertTrue(repository.getFocusHistory().isEmpty());
        }
    }

    @Test public void activeFocusHidesModePickerAndOffersCancellationFromMoreMenu() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.showPage(2)); idle();
            scenario.onActivity(activity -> activity.findViewById(R.id.timerButton).performClick()); idle();
            scenario.onActivity(activity -> {
                assertEquals(View.GONE, activity.findViewById(R.id.focusModes).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.endFocusButton).getVisibility());
                activity.findViewById(R.id.focusSettingsButton).performClick();
            }); idle();
        }
    }

    @Test public void draftSessionRestoresUnsavedEditorFields() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                android.widget.LinearLayout form = new android.widget.LinearLayout(activity);
                android.widget.EditText title = new android.widget.EditText(activity);
                android.widget.Switch enabled = new android.widget.Switch(activity);
                title.setText("原始任务");
                form.addView(title);
                form.addView(enabled);
                FormDraftGuard.Session draft = new FormDraftGuard.Session(activity, form);
                title.setText("未保存的演示任务");
                enabled.setChecked(true);
                assertTrue(draft.dirty());
                android.os.Bundle state = draft.save();
                title.setText("临时覆盖");
                enabled.setChecked(false);
                draft.restore(state);
                assertEquals("未保存的演示任务", title.getText().toString());
                assertTrue(enabled.isChecked());
                draft.accept();
                assertFalse(draft.dirty());
            });
        }
    }

    @Test public void staleProposalButtonCannotCreateTaskTwice() {
        ChatMessage message = new ChatMessage(ChatMessage.Role.ASSISTANT, "安排建议",
                "{\"message\":\"安排建议\",\"needsClarification\":false,\"actions\":[{\"type\":\"create_task\",\"title\":\"提案演示任务\"}]}",
                ChatMessage.ActionState.PENDING_CONFIRMATION);
        repository.addChatMessage(message);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            idle();
            scenario.onActivity(activity -> {
                View button = findText(activity.pageContainer, "确认 1 项");
                assertNotNull(button);
                button.performClick();
                button.performClick();
            }); idle();
            assertEquals(1, repository.getAllTasks().size());
            assertEquals(ChatMessage.ActionItemState.EXECUTED,
                    repository.getChatMessage(message.getId()).getActionItemState(0));
        }
    }

    @Test public void inlineResultRetainsSingleUseUndo() {
        ChatMessage message = new ChatMessage(ChatMessage.Role.ASSISTANT, "安排建议",
                "{\"message\":\"安排建议\",\"needsClarification\":false,\"actions\":[{\"type\":\"create_task\",\"title\":\"可撤销的演示任务\"}]}",
                ChatMessage.ActionState.PENDING_CONFIRMATION);
        repository.addChatMessage(message);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            idle();
            scenario.onActivity(activity -> findText(activity.pageContainer, "确认 1 项").performClick()); idle();
            assertEquals(1, repository.getAllTasks().size());
            scenario.onActivity(activity -> {
                View undo = findText(activity.pageContainer, "撤销"); assertNotNull(undo);
                undo.performClick(); undo.performClick();
            }); idle();
            assertTrue(repository.getAllTasks().isEmpty());
            assertEquals(ChatMessage.ActionItemState.REVERTED,
                    repository.getChatMessage(message.getId()).getActionItemState(0));
        }
    }

    @Test public void backgroundSuggestionPreservesChatViewDraftAndReadingPosition() {
        for (int i = 0; i < 18; i++) repository.addChatMessage(new ChatMessage(ChatMessage.Role.ASSISTANT,
                "演示历史消息 " + i + "：复习时先整理知识，再逐项完成练习，保留可执行的计划。"));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            idle(); android.os.SystemClock.sleep(500);
            View[] original = new View[2]; int[] scroll = new int[1];
            scenario.onActivity(activity -> {
                original[0] = activity.pageContainer.getChildAt(0);
                original[1] = activity.chatMessages.getChildAt(1);
                activity.chatInput.setText("尚未发送的复习安排");
                activity.chatScroll.scrollTo(0, 200); scroll[0] = activity.chatScroll.getScrollY();
            }); idle();
            repository.addChatMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, "后台建议演示内容",
                    "{\"message\":\"建议\",\"needsClarification\":false,\"actions\":[{\"type\":\"create_task\",\"title\":\"复习\"}]}",
                    ChatMessage.ActionState.PENDING_CONFIRMATION, ChatMessage.ProposalKind.PROACTIVE_PLAN,
                    java.util.Collections.emptyList()));
            idle(); android.os.SystemClock.sleep(500);
            scenario.onActivity(activity -> {
                assertSame(original[0], activity.pageContainer.getChildAt(0));
                assertSame(original[1], activity.chatMessages.getChildAt(1));
                assertEquals(scroll[0], activity.chatScroll.getScrollY());
                assertEquals("尚未发送的复习安排", activity.chatInput.getText().toString());
                assertNotNull(findText(activity.pageContainer, "今日建议 · 1"));
                assertNull(findText(activity.pageContainer, "后台建议演示内容"));
            });
        }
    }

    @Test public void taskCheckboxCompletesOnlyItsTask() {
        TaskItem selected = repository.upsertTask(new TaskItem("选择完成的演示任务").setRequiresTimeArrangement(false));
        TaskItem untouched = repository.upsertTask(new TaskItem("保持待办的演示任务").setRequiresTimeArrangement(false));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> { activity.scheduleMode = 1; activity.showPage(1); }); idle();
            onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("完成：选择完成的演示任务"))
                    .perform(click()); idle();
            assertEquals(TaskItem.Status.COMPLETED, repository.getTask(selected.getId()).getStatus());
            assertFalse(repository.getTask(untouched.getId()).getStatus().isTerminal());
        }
    }

    @Test public void settingsDraftSurvivesRecreationWithoutWritingRepository() {
        int original = repository.getSettings().getAvailableStartMinute();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(TimeAssistantActivity::openSettings); idle();
            scenario.onActivity(activity -> {
                View view = findText(activity.pageContainer, "安排偏好与通知");
                assertNotNull(view);
                while (!view.isClickable()) view = (View) view.getParent();
                view.performClick();
            }); idle();
            scenario.onActivity(activity -> {
                ((android.widget.EditText) activity.findViewById(R.id.availableStartInput)).setText("09:20");
            }); idle();
            scenario.recreate(); idle();
            scenario.onActivity(activity -> {
                assertEquals("09:20", ((android.widget.EditText) activity.findViewById(R.id.availableStartInput)).getText().toString());
                assertEquals(original, repository.getSettings().getAvailableStartMinute());
            }); idle();
        }
    }

    private static View findText(View root, String value) {
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findText(group.getChildAt(index), value);
                if (found != null) return found;
            }
        }
        return null;
    }
}
