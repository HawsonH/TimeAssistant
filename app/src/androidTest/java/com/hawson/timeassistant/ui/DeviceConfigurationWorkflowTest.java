package com.hawson.timeassistant.ui;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.TaskItem;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.hawson.timeassistant.ui.WorkflowDriver.*;

@RunWith(AndroidJUnit4.class)
public class DeviceConfigurationWorkflowTest {
    @Test public void configurationWalkthroughAssertsAppliedEnvironment() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        assertTrue(context.getPackageName().endsWith(".qa"));
        com.hawson.timeassistant.DeviceFixtureSafety.requireIsolatedTarget(context);
        requireUnlockedDisplay(context);
        String variant = InstrumentationRegistry.getArguments().getString("uiVariant", "landscape");
        AppRepository repository = AppRepository.get(context); repository.clearAll();
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().putInt("seen_version", 1)
                .putBoolean("intro_pending", false).putBoolean("context_enabled", false).commit();
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().putInt("last_page", 0)
                .putBoolean("focus_lockscreen_settings_confirmed_v2", true).commit();
        repository.upsertTask(new TaskItem("长标题验证：完成线性代数复习并整理课堂笔记和小组讨论材料").setRequiresTimeArrangement(false));
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            if (variant.equals("landscape")) {
                screen.onActivity(a -> a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                long end = android.os.SystemClock.uptimeMillis() + 8_000; boolean[] ready = {false};
                do {
                    idle(); screen.onActivity(a -> ready[0] = a.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
                } while (!ready[0] && android.os.SystemClock.uptimeMillis() < end);
                assertTrue("The requested landscape configuration was not applied", ready[0]);
            }
            screen.onActivity(a -> {
                Configuration config = a.getResources().getConfiguration();
                if (variant.equals("font2")) assertTrue("font_scale 2.0 not applied", config.fontScale >= 1.99f);
                if (variant.equals("compact320")) assertTrue("Expected <=320dp viewport", config.screenWidthDp <= 320);
                assertTrue("Page container must be visible on the unlocked display", a.pageContainer.isShown());
            }); capture("config-" + variant + "-chat");
            screen.onActivity(a -> a.showPage(1)); idle();
            long[] nextDay = {0};
            screen.onActivity(activity -> {
                java.util.Calendar next = (java.util.Calendar) activity.selectedDate.clone();
                next.add(java.util.Calendar.DATE, 1); nextDay[0] = next.getTimeInMillis();
            });
            capture("config-" + variant + "-date-before");
            clickId(R.id.nextDateButton);
            capture("config-" + variant + "-date-after");
            screen.onActivity(activity -> assertTrue("Next-date action did not update domain selection: expected="
                            + nextDay[0] + ", actual=" + activity.selectedDate.getTimeInMillis()
                            + ", mode=" + activity.scheduleMode,
                    activity.sameDay(nextDay[0], activity.selectedDate.getTimeInMillis())));
            clickId(R.id.todayButton);
            screen.onActivity(activity -> assertTrue("Return-to-today action did not update selection",
                    activity.sameDay(System.currentTimeMillis(), activity.selectedDate.getTimeInMillis())));
            awaitCondition("today toolbar replacement", () -> find(view -> view.getId() == R.id.selectedDateButton) != null
                    && find(view -> view.getId() == R.id.todayButton) == null);
            screen.onActivity(a -> assertEquals(android.view.View.INVISIBLE, a.findViewById(R.id.todayButton).getVisibility()));
            clickId(R.id.todoTabButton); capture("config-" + variant + "-todo");
            screen.onActivity(TimeAssistantActivity::showTodoTaskDialog); idle(); fill("待办标题", "配置验证新增待办"); click("创建");
            assertEquals(2, repository.getAllTasks().size());
            screen.onActivity(a -> a.showPage(2)); idle();
            if (variant.equals("landscape")) screen.onActivity(activity -> {
                for (int id : new int[]{R.id.timerRing, R.id.timerButton}) {
                    android.view.View control = activity.findViewById(id);
                    android.graphics.Rect visible = new android.graphics.Rect();
                    assertTrue("Landscape focus control is not visible", control.getGlobalVisibleRect(visible));
                    assertEquals("Landscape focus control is clipped", control.getHeight(), visible.height());
                    assertEquals("Landscape focus control is clipped horizontally", control.getWidth(), visible.width());
                }
            });
            capture("config-" + variant + "-focus");
            clickId(R.id.timerButton); clickId(R.id.timerButton); clickId(R.id.endFocusButton); click("确认结束");
            assertEquals(1, repository.getFocusHistory().size());
            screen.onActivity(TimeAssistantActivity::openSettings); idle(); click("外观与教程"); capture("config-" + variant + "-appearance");
        } finally { repository.clearAll(); }
    }
}
