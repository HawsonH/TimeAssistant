package com.hawson.timeassistant.ui;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.design.AppearancePreferences;
import com.hawson.timeassistant.design.MotionSpec;
import com.hawson.timeassistant.domain.TaskItem;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Destructive fictional fixtures: emulator-only, never run on an account-bearing phone. */
@RunWith(AndroidJUnit4.class)
public class UiConsistencyWorkflowTest {
    private Context context;
    private AppRepository repository;
    private String oldTheme;
    private int oldNight;
    private MotionSpec.Mode oldMotion;

    @Before public void prepare() {
        assertTrue("Emulator only", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"));
        context = ApplicationProvider.getApplicationContext();
        assertEquals("com.hawson.timeassistant.interaction.qa", context.getPackageName());
        com.hawson.timeassistant.DeviceFixtureSafety.requireIsolatedTarget(context);
        WorkflowDriver.requireUnlockedDisplay(context);
        repository = AppRepository.get(context);
        repository.clearAll();
        repository.saveSettings(repository.getSettings().setFocusAutomation(false, false, false, false)
                .setFocusEndSignals(false, false));
        AppearancePreferences appearance = new AppearancePreferences(context);
        oldMotion = appearance.motionMode(); oldNight = appearance.nightMode();
        oldTheme = AppTheme.selected(context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0));
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().putInt("seen_version", 1)
                .putBoolean("intro_pending", false).putBoolean("context_enabled", false).commit();
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().putInt("last_page", 0)
                .putBoolean("focus_lockscreen_settings_confirmed_v2", true).commit();
    }

    @After public void restore() {
        if (repository == null) return;
        repository.clearAll();
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().putString(AppTheme.PREFERENCE_KEY, oldTheme).commit();
        new AppearancePreferences(context).setMotionMode(oldMotion);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> new AppearancePreferences(context).setNightMode(oldNight));
    }

    @Test public void groupedFocusSettingsKeepFooterAndUnsavedDraft() throws Exception {
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> a.showPage(2)); WorkflowDriver.idle();
            WorkflowDriver.clickId(R.id.focusSettingsButton); WorkflowDriver.click("专注设置");
            screen.onActivity(a -> {
                for (String title : new String[]{"计时", "衔接", "声振", "记录", "分钟"})
                    assertNotNull(title, findText(a.pageContainer, title));
                View save = findText(a.pageContainer, "保存专注设置"); assertNotNull(save);
                for (View parent = (View) save.getParent(); parent != a.pageContainer; parent = (View) parent.getParent())
                    assertFalse("Save must not scroll with settings", parent instanceof ScrollView);
                assertEquals(1, countButtons(a.pageContainer, "保存专注设置"));
                assertFalse(findText(a.pageContainer, "配置锁屏番茄钟显示") instanceof Button);
                EditText input = (EditText) findDescription(a.pageContainer, "番茄时长，分钟");
                assertNotNull(input); input.setText("42");
                ((ScrollView) a.findViewById(R.id.settingsScroll)).fullScroll(View.FOCUS_DOWN);
            }); WorkflowDriver.idle();
            capture(screen, "settings-footer", "保存专注设置");
            screen.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            screen.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); WorkflowDriver.idle();
            screen.onActivity(a -> assertEquals("42", ((EditText) findDescription(a.pageContainer, "番茄时长，分钟")).getText().toString()));
            screen.onActivity(TimeAssistantActivity::closeSettings); WorkflowDriver.idle();
            assertNotNull("Leaving edited form must offer draft handling", WorkflowDriver.text("继续编辑"));
        }
    }

    @Test public void captureVerifiedPagesAcrossFiveThemesAndNightModes() throws Exception {
        String requested = InstrumentationRegistry.getArguments().getString("consistencyTheme", "neutral");
        String variant = InstrumentationRegistry.getArguments().getString("consistencyVariant", "default");
        boolean correctionsOnly = "corrections".equals(InstrumentationRegistry.getArguments()
                .getString("consistencyScope", "all"));
        for (int night : new int[]{1, 2}) {
            context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().putString(AppTheme.PREFERENCE_KEY, requested).commit();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> new AppearancePreferences(context).setNightMode(night));
            String prefix = (correctionsOnly ? "corrected-" : "") + variant + "-" + requested + "-" + night;
            try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
                screen.onActivity(a -> a.showPage(0));
                if (variant.equals("landscape")) {
                    screen.onActivity(a -> a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                    WorkflowDriver.awaitCondition("landscape", () -> context.getResources().getConfiguration().orientation == 2);
                }
                screen.onActivity(a -> {
                    android.content.res.Configuration config = a.getResources().getConfiguration();
                    if (variant.equals("compact320")) assertEquals(320, config.screenWidthDp);
                    if (variant.equals("width360")) assertEquals(360, config.screenWidthDp);
                    if (variant.equals("width412")) assertEquals(412, config.screenWidthDp);
                    if (variant.equals("font13")) assertEquals(1.3f, config.fontScale, .01f);
                    if (variant.equals("font2") || variant.equals("landscape"))
                        assertEquals(2f, config.fontScale, .01f);
                });
                WorkflowDriver.idle();
                if (!correctionsOnly) capture(screen, prefix + "-chat", "时屿");
                screen.onActivity(a -> {
                    assertEquals(.4f, a.findViewById(R.id.sendButton).getAlpha(), .001f);
                    a.showPage(1);
                }); WorkflowDriver.idle();
                if (!correctionsOnly) capture(screen, prefix + "-schedule-empty", "暂无日程");
                java.util.Calendar date = java.util.Calendar.getInstance();
                date.add(java.util.Calendar.MINUTE, 15);
                repository.upsertTask(new TaskItem("阅读并整理课堂笔记与本周复习提纲")
                        .setRequiresTimeArrangement(true).schedule(date.getTimeInMillis(), date.getTimeInMillis() + 3_600_000, false));
                screen.onActivity(TimeAssistantActivity::renderCurrentPage); WorkflowDriver.idle();
                capture(screen, prefix + "-schedule", "阅读并整理课堂笔记与本周复习提纲");
                if (!correctionsOnly) {
                    WorkflowDriver.clickId(R.id.nextDateButton);
                    capture(screen, prefix + "-date-next", "回到今天");
                    WorkflowDriver.clickId(R.id.todayButton);
                    WorkflowDriver.clickId(R.id.todoTabButton); capture(screen, prefix + "-todo", "暂无待办");
                    WorkflowDriver.clickId(R.id.reflowTabButton); capture(screen, prefix + "-attention", "注意");
                    WorkflowDriver.clickId(R.id.scheduleTabButton); WorkflowDriver.clickId(R.id.selectedDateButton);
                    capture(screen, prefix + "-calendar", "回到今天");
                    WorkflowDriver.clickId(R.id.scheduleTabButton);
                    screen.onActivity(TimeAssistantActivity::showManualTaskDialog); WorkflowDriver.idle();
                    capture(screen, prefix + "-create-task", "添加任务");
                    androidx.test.espresso.Espresso.pressBack(); WorkflowDriver.idle();
                }
                screen.onActivity(a -> a.showPage(2)); WorkflowDriver.idle();
                capture(screen, prefix + "-focus-ready", "开始");
                screen.onActivity(a -> {
                    assertNoOverlap(a);
                    a.findViewById(R.id.timerButton).performClick();
                }); WorkflowDriver.idle();
                capture(screen, prefix + "-focus-running", "暂停");
                screen.onActivity(this::assertNoOverlap);
                repository.clearCurrentFocus();
                screen.onActivity(TimeAssistantActivity::renderCurrentPage); WorkflowDriver.idle();
                if (!correctionsOnly) {
                    WorkflowDriver.clickId(R.id.focusSettingsButton); WorkflowDriver.click("专注设置");
                    capture(screen, prefix + "-focus-settings", "保存专注设置");
                }
            }
            repository.clearAll();
        }
    }

    @Test public void largeFontTimelineKeepsEachTimeOnOneLine() {
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> {
                android.content.res.Configuration config = new android.content.res.Configuration(a.getResources().getConfiguration());
                config.fontScale = 2;
                Context themed = new android.view.ContextThemeWrapper(a.createConfigurationContext(config), AppTheme.styleFor(AppTheme.NEUTRAL));
                android.widget.LinearLayout row = com.hawson.timeassistant.ui.schedule.ScheduleTaskLayout.timeline(themed, "18:38", "23:59");
                com.hawson.timeassistant.ui.schedule.ScheduleTaskLayout.content(row, new TextView(themed));
                int width = Math.round(288 * themed.getResources().getDisplayMetrics().density);
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
                android.widget.LinearLayout times = (android.widget.LinearLayout) row.getChildAt(0);
                for (int i = 0; i < times.getChildCount(); i++) {
                    TextView time = (TextView) times.getChildAt(i);
                    assertEquals(1, time.getLayout().getLineCount());
                    assertTrue(time.getPaint().measureText(time.getText().toString())
                            <= time.getWidth() - time.getCompoundPaddingLeft() - time.getCompoundPaddingRight());
                }
            });
        }
    }

    private void assertNoOverlap(MainActivity activity) {
        View ring = activity.findViewById(R.id.timerRing), actions = activity.findViewById(R.id.focusActions);
        android.graphics.Rect clock = new android.graphics.Rect(), buttons = new android.graphics.Rect();
        ring.getDrawingRect(clock); actions.getDrawingRect(buttons);
        ViewGroup root = activity.pageContainer;
        root.offsetDescendantRectToMyCoords(ring, clock); root.offsetDescendantRectToMyCoords(actions, buttons);
        assertFalse("Dial and actions overlap", android.graphics.Rect.intersects(clock, buttons));
        assertTrue(ring.getWidth() <= Math.round(320 * activity.getResources().getDisplayMetrics().density));
    }

    private void capture(ActivityScenario<MainActivity> screen, String name, String expected) throws Exception {
        WorkflowDriver.idle();
        screen.onActivity(a -> {
            View anchor = WorkflowDriver.text(expected);
            assertNotNull("Expected page missing: " + name + " / " + expected, anchor);
            assertTrue(anchor.isShown());
            assertTrue("Wrong activity or obscured window: " + name, anchor.hasWindowFocus());
        });
        // Accessibility roots can be unavailable immediately after Activity recreation.
        // Keep an independent system-window check instead of trusting the screenshot alone.
        String foreground = "";
        try (android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand("dumpsys window");
             java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                     new java.io.FileInputStream(descriptor.getFileDescriptor())))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("mCurrentFocus=")) { foreground = line.trim(); break; }
            }
        }
        assertTrue("Unexpected foreground for " + name + ": " + foreground,
                foreground.contains(" " + context.getPackageName() + "/")
                        && !foreground.contains("Application Not Responding"));
        File directory = new File(context.getExternalFilesDir(null), "ui-consistency-2026-11-08");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { bitmap.recycle(); }
    }

    private static View findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = findText(((ViewGroup) root).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private static View findDescription(View root, String text) {
        if (text.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = findDescription(((ViewGroup) root).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private static int countButtons(View root, String title) {
        int count = root instanceof Button && title.contentEquals(((Button) root).getText()) ? 1 : 0;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
            count += countButtons(((ViewGroup) root).getChildAt(i), title);
        return count;
    }
}
