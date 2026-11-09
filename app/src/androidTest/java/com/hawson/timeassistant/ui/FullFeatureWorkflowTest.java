package com.hawson.timeassistant.ui;

import android.Manifest;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.widget.EditText;
import android.widget.CompoundButton;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Calendar;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.hawson.timeassistant.ui.WorkflowDriver.*;

/** Destructive workflows require an emulator or explicit opt-in on the disposable device QA package. */
@RunWith(AndroidJUnit4.class)
public class FullFeatureWorkflowTest {
    @Rule public final org.junit.rules.TestName testName = new org.junit.rules.TestName();
    private Context context;
    private AppRepository repository;
    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        assertTrue(context.getPackageName().endsWith(".qa"));
        com.hawson.timeassistant.DeviceFixtureSafety.requireIsolatedTarget(context);
        repository = AppRepository.get(context); repository.clearAll();
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().clear().putInt("seen_version", 1)
                .putBoolean("intro_pending", false).putBoolean("context_enabled", false).commit();
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().clear().putString("theme_id", "neutral")
                .putInt("last_page", 0).putBoolean("focus_lockscreen_settings_confirmed_v2", true).commit();
        boolean needsNotifications = "todoCountdownEditCompleteAndUndo".equals(testName.getMethodName())
                || "focusDurationValidationCancelAndInterruptedSettlement".equals(testName.getMethodName());
        if (needsNotifications && Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
    }
    @After public void cleanup() { if (repository != null) repository.clearAll(); }

    @Test public void createEditAndRemoveScheduleThroughForm() throws Exception {
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> { a.selectedDate.add(Calendar.DATE, 3); a.showPage(1); a.showManualTaskDialog(); }); idle();
            click("保存");
            screen.onActivity(a -> assertNotNull(((EditText) find(v -> v instanceof EditText && "任务标题".contentEquals(((EditText) v).getHint()))).getError()));
            assertTrue(repository.getAllTasks().isEmpty());
            fill("任务标题", "多设备验证：完成实验报告"); click("重复与备注"); fill("备注", "需要保留的实验步骤");
            capture("task-create"); click("保存"); assertEquals(1, repository.getAllTasks().size());
            TaskItem task = repository.getAllTasks().get(0);
            screen.onActivity(a -> { assertTrue(a.sameDay(a.selectedDate.getTimeInMillis(), task.getPlannedDateAt())); a.showTaskDetails(task.getId()); }); idle();
            fill("任务标题", "修改后的实验报告"); click("保存修改");
            assertEquals("修改后的实验报告", repository.getTask(task.getId()).getTitle());
            screen.onActivity(a -> a.showTaskDetails(task.getId())); idle(); click("更多操作"); click("仅移除安排"); click("确认移除");
            TaskItem retained = repository.getTask(task.getId()); assertNotNull(retained); assertFalse(retained.requiresTimeArrangement());
            assertEquals("需要保留的实验步骤", retained.getDescription());
            screen.onActivity(a -> { a.scheduleMode=TimeAssistantActivity.SCHEDULE_TODO; a.showPage(1); a.renderCurrentPage(); }); idle(); capture("task-moved-to-todo");
        }
    }

    @Test public void todoCountdownEditCompleteAndUndo() throws Exception {
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> { a.scheduleMode=TimeAssistantActivity.SCHEDULE_TODO; a.showPage(1); a.showTodoTaskDialog(); }); idle();
            fill("待办标题", "多设备验证：复习单词"); click("提醒与备注"); click("倒计时"); click("确定");
            click("循环提醒"); click("创建");
            TaskItem task=repository.getAllTasks().get(0); assertTrue(task.hasCountdown()); assertTrue(task.isRepeatingCountdown());
            screen.onActivity(a -> a.showTaskDetails(task.getId())); idle();
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers
                    .withContentDescription("清除倒计时"))
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo(),
                            androidx.test.espresso.action.ViewActions.click()); idle(); click("保存");
            assertFalse(repository.getTask(task.getId()).hasCountdown());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("完成：多设备验证：复习单词"))
                    .perform(androidx.test.espresso.action.ViewActions.click()); idle();
            assertEquals(TaskItem.Status.COMPLETED,repository.getTask(task.getId()).getStatus());
            screen.onActivity(a -> a.showTaskDetails(task.getId())); idle(); click("更多操作"); click("撤销"); click("确认撤销");
            assertFalse(repository.getTask(task.getId()).getStatus().isTerminal()); capture("todo-restored");
        }
    }

    @Test public void taskDeleteRequiresConfirmationAndSupportsUndo() throws Exception {
        TaskItem task=repository.upsertTask(new TaskItem("验证删除与撤销").setRequiresTimeArrangement(true));
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> a.showTaskDetails(task.getId())); idle(); click("更多操作"); click("删除任务"); click("取消");
            assertNotNull(repository.getTask(task.getId()));
            screen.onActivity(a -> a.showTaskDetails(task.getId())); idle(); click("更多操作"); click("删除任务"); click("确认删除");
            assertNull(repository.getTask(task.getId())); click("撤销删除"); assertNotNull(repository.getTask(task.getId())); capture("task-delete-undone");
        }
    }

    @Test public void recurringSingleEditSkipAndRestoreKeepSeries() throws Exception {
        Calendar day=Calendar.getInstance(); day.add(Calendar.DATE,1); day.set(Calendar.HOUR_OF_DAY,18); day.set(Calendar.MINUTE,0); day.set(Calendar.SECOND,0); day.set(Calendar.MILLISECOND,0);
        long start=day.getTimeInMillis();
        RecurringTaskGroup series=repository.upsertRecurringGroup(new RecurringTaskGroup("周期验证课程",start,1_800_000,RecurringTaskGroup.Unit.WEEKS,1));
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> { a.selectedDate.setTimeInMillis(start); a.showPage(1); }); idle();
            click("管理周期 ›"); click("修改…"); click("仅本次"); fill("标题","本次改为专题讨论"); click("保存");
            assertEquals("周期验证课程",repository.getRecurringGroup(series.getId()).getTitle());
            assertEquals("本次改为专题讨论",repository.getRecurringOverride(series.getId(),start).effectiveTitle(series));
            click("管理周期 ›"); click("跳过本次"); assertTrue(repository.getRecurringOverride(series.getId(),start).isSkipped());
            capture("recurrence-skipped-recovery"); click("恢复本次"); assertNull(repository.getRecurringOverride(series.getId(),start)); capture("recurrence-restored");
        }
    }

    @Test public void focusDurationValidationCancelAndInterruptedSettlement() throws Exception {
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> a.showPage(2)); idle(); clickId(R.id.timerRing); fill("分钟","0"); click("保存");
            assertNotEquals(0,repository.getSettings().getFocusDurationMinutes()); fill("分钟","17"); click("保存");
            capture("focus-duration-edited"); assertEquals(17,repository.getSettings().getFocusDurationMinutes());
            clickId(R.id.timerButton); assertEquals(FocusState.State.RUNNING,repository.getCurrentFocus().getState());
            clickId(R.id.focusSettingsButton); click("取消本轮"); click("确认取消"); assertTrue(repository.getFocusHistory().isEmpty());
            clickId(R.id.timerButton); android.os.SystemClock.sleep(1100); clickId(R.id.timerButton);
            assertEquals(FocusState.State.PAUSED,repository.getCurrentFocus().getState());
            clickId(R.id.endFocusButton); clickContaining("任务中断"); fill("中断原因（可选）","临时上课"); click("确认结束");
            assertEquals(1,repository.getFocusHistory().size()); assertEquals("临时上课",repository.getFocusHistory().get(0).getNote()); capture("focus-interruption-result");
            screen.recreate(); idle(); assertEquals(1,repository.getFocusHistory().size());
        }
    }

    @Test public void preferenceInvalidValueDoesNotSaveAndValidValueSurvivesRecreation() throws Exception {
        int original=repository.getSettings().getAvailableStartMinute();
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(TimeAssistantActivity::openSettings); idle(); click("安排偏好与通知");
            fillId(R.id.availableStartInput,"25:00"); clickId(R.id.savePreferencesButton); assertEquals(original,repository.getSettings().getAvailableStartMinute());
            fillId(R.id.availableStartInput,"09:20"); fillId(R.id.availableEndInput,"21:30"); clickId(R.id.savePreferencesButton);
            assertEquals(560,repository.getSettings().getAvailableStartMinute()); screen.recreate(); idle();
            screen.onActivity(a -> assertEquals("09:20",((EditText)a.findViewById(R.id.availableStartInput)).getText().toString())); capture("preferences-saved");
        }
    }

    @Test public void focusAutomationDependenciesSaveOnlySelectedOptions() throws Exception {
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> a.showPage(2)); idle(); clickId(R.id.focusSettingsButton); click("专注设置");
            screen.onActivity(a -> {
                CompoundButton advance=(CompoundButton)text("结束后自动进入下一段"); if (!advance.isChecked()) advance.performClick();
                CompoundButton breaks=(CompoundButton)text("禁用休息"); if (!breaks.isChecked()) breaks.performClick();
                assertFalse(text("自动开始休息").isEnabled());
                assertFalse(((CompoundButton)text("自动开始休息")).isChecked());
            }); click("保存专注设置"); assertTrue(repository.getSettings().isBreaksDisabled());
            assertFalse(repository.getSettings().isAutoStartBreak()); capture("focus-automation-saved");
        }
    }

    @Test public void dataFileCallbacksExportImportAndMergeWithoutDuplicates() throws Exception {
        TaskItem task=repository.upsertTask(new TaskItem("导出导入验证任务").setDescription("需要完整保留的备注"));
        File backup=new File(context.getCacheDir(),"workflow-backup.json");
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> callback(a,"writeExport",Uri.fromFile(backup))); idle();
            String content=new String(Files.readAllBytes(backup.toPath()),StandardCharsets.UTF_8); assertTrue(content.contains(task.getTitle()));
            repository.clearAll(); screen.onActivity(a -> callback(a,"readImport",Uri.fromFile(backup))); idle(); click("替换");
            assertEquals("需要完整保留的备注",repository.getTask(task.getId()).getDescription());
            screen.onActivity(a -> callback(a,"readImport",Uri.fromFile(backup))); idle(); click("合并"); assertEquals(1,repository.getAllTasks().size());
            screen.onActivity(TimeAssistantActivity::openSettings); idle(); click("同步与数据"); capture("data-roundtrip");
        } finally { assertTrue(!backup.exists() || backup.delete()); }
    }

    @Test public void clearingChatAndAllDataHonorCancellationAndTwoConfirmations() throws Exception {
        repository.upsertTask(new TaskItem("清理验证保留项")); repository.addChatMessage(new ChatMessage(ChatMessage.Role.USER,"虚构聊天"));
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(TimeAssistantActivity::openSettings); idle(); click("同步与数据"); click("清除聊天记录"); click("取消"); assertEquals(1,repository.getChatMessages().size());
            click("清除聊天记录"); click("清除"); assertTrue(repository.getChatMessages().isEmpty()); assertEquals(1,repository.getAllTasks().size());
            click("清除全部本地数据"); click("继续"); click("返回"); assertEquals(1,repository.getAllTasks().size());
            click("清除全部本地数据"); click("继续"); capture("data-final-confirmation"); click("永久清除");
            long deadline=android.os.SystemClock.uptimeMillis()+8_000;
            while (!repository.getAllTasks().isEmpty() && android.os.SystemClock.uptimeMillis()<deadline) { idle(); }
            assertTrue(repository.getAllTasks().isEmpty());
        }
    }

    @Test public void calendarReadUsesGrantedPermissionAndCanBeDisabled() throws Exception {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.READ_CALENDAR);
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(TimeAssistantActivity::openSettings); idle(); click("日历连接");
            screen.onActivity(a -> {
                CompoundButton toggle=(CompoundButton)text("允许 AI 查询系统日历忙碌时间"); if (!toggle.isChecked()) toggle.performClick();
            }); idle(); click("测试读取");
            long deadline=android.os.SystemClock.uptimeMillis()+8_000; boolean[] success={false};
            do { idle(); screen.onActivity(a -> success[0]=text("日历读取成功")!=null); } while (!success[0] && android.os.SystemClock.uptimeMillis()<deadline);
            assertTrue("Calendar provider read did not complete",success[0]); capture("calendar-provider-read"); click("好"); click("允许 AI 查询系统日历忙碌时间");
            screen.onActivity(a -> assertFalse(text("测试读取").isEnabled()));
        }
    }

    @Test public void pairingFormValidationDoesNotChangeExistingData() throws Exception {
        repository.upsertTask(new TaskItem("配对表单验证保留项"));
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(TimeAssistantActivity::openSettings); idle(); click("同步与数据"); click("加入设备"); click("配对并同步");
            fill("主机地址，例如 192.168.1.20","127.0.0.1"); fill("6 位配对码","12"); click("配对并同步");
            screen.onActivity(a -> assertNotNull(((EditText)find(v -> v instanceof EditText && "6 位配对码".contentEquals(((EditText)v).getHint()))).getError()));
            capture("pairing-validation"); click("取消"); assertEquals(1,repository.getAllTasks().size()); assertFalse(repository.getSettings().isSyncEnabled());
        }
    }

    @Test public void editedTaskDraftCanContinueOrDiscardWithoutSaving() throws Exception {
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(TimeAssistantActivity::showManualTaskDialog); idle();
            fill("任务标题","不应自动保存的草稿"); click("取消"); click("继续编辑");
            assertTrue(repository.getAllTasks().isEmpty()); capture("task-draft-continued");
            click("取消"); click("放弃修改"); assertTrue(repository.getAllTasks().isEmpty());
        }
    }

    @Test public void customConnectionSaveEditAndDeletePreserveTaskData() throws Exception {
        com.hawson.timeassistant.AiServices ai=((com.hawson.timeassistant.TimeAssistantApplication)context).getAiServices();
        repository.upsertTask(new TaskItem("连接操作保留的任务"));
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(TimeAssistantActivity::openSettings); idle(); click("AI 连接与账号"); click("添加连接"); click("自定义服务");
            fillId(com.hawson.timeassistant.settingsfeature.R.id.name,"多设备测试连接");
            fillId(com.hawson.timeassistant.settingsfeature.R.id.url,"https://example.invalid/v1");
            fillId(com.hawson.timeassistant.settingsfeature.R.id.key,"fixture-not-a-real-secret");
            fillId(com.hawson.timeassistant.settingsfeature.R.id.model,"fixture-model"); click("保存连接");
            long deadline=android.os.SystemClock.uptimeMillis()+8_000;
            while (ai.profiles.list().stream().noneMatch(p -> p.name.equals("多设备测试连接")) && android.os.SystemClock.uptimeMillis()<deadline) idle();
            com.hawson.timeassistant.connections.AiProfile saved=ai.profiles.list().stream().filter(p -> p.name.equals("多设备测试连接")).findFirst().orElseThrow(AssertionError::new);
            assertEquals("fixture-model",saved.model); click("多设备测试连接 · 未选用"); click("编辑");
            fillId(com.hawson.timeassistant.settingsfeature.R.id.name,"修改后的测试连接"); click("保存连接"); idle();
            capture("connection-saved"); click("修改后的测试连接 · 未选用"); click("删除"); click("取消"); assertTrue(ai.profiles.list().stream().anyMatch(p -> p.id.equals(saved.id)));
            click("修改后的测试连接 · 未选用"); click("删除"); click("删除"); idle();
            deadline=android.os.SystemClock.uptimeMillis()+8_000;
            while (ai.profiles.list().stream().anyMatch(p -> p.id.equals(saved.id)) && android.os.SystemClock.uptimeMillis()<deadline) idle();
            assertFalse(ai.profiles.list().stream().anyMatch(p -> p.id.equals(saved.id))); assertEquals(1,repository.getAllTasks().size());
        } finally {
            for (com.hawson.timeassistant.connections.AiProfile p:ai.profiles.list())
                if (p.name.equals("多设备测试连接") || p.name.equals("修改后的测试连接")) ai.profiles.delete(p.id);
        }
    }
}
