package com.hawson.timeassistant.ui;

import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.hawson.timeassistant.testing.PermissionGuideHarnessActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** System launches intercepted by the harness: never requests or changes permissions. */
@RunWith(AndroidJUnit4.class)
public class SystemPermissionGuideTest {
    @Test public void phoneSafeGuideAcrossWidthsFontsAndThemes() {
        try (ActivityScenario<PermissionGuideHarnessActivity> screen = ActivityScenario.launch(PermissionGuideHarnessActivity.class)) {
            screen.onActivity(a -> {
                for (String palette : new String[]{AppTheme.OCEAN, AppTheme.FOREST, AppTheme.CLAY, AppTheme.VIOLET})
                    for (boolean dark : new boolean[]{false, true})
                        for (int width : new int[]{320, 360, 412, 740})
                            for (float font : new float[]{1.3f, 2f}) {
                                android.content.res.Configuration config = new android.content.res.Configuration(a.getResources().getConfiguration());
                                config.fontScale = font;
                                config.orientation = width == 740 ? 2 : 1;
                                config.screenWidthDp = width; config.screenHeightDp = width == 740 ? 320 : 740;
                                config.uiMode = (config.uiMode & ~48) | (dark ? 32 : 16);
                                android.content.Context theme = new android.view.ContextThemeWrapper(a.createConfigurationContext(config), AppTheme.styleFor(palette));
                                com.hawson.timeassistant.settings.PermissionGuideView guide = new com.hawson.timeassistant.settings.PermissionGuideView(theme, id -> fail("Rendering cannot launch settings"));
                                guide.render(java.util.Arrays.asList(
                                        new com.hawson.timeassistant.settings.PermissionGuideView.Item("reminders", "通知与提醒", "任务与日程提醒", "已开启；声音：已配置；震动：允许；横幅：未允许", "ColorOS 请分别检查应用总开关和本渠道。最终效果受勿扰与静音限制。"),
                                        new com.hawson.timeassistant.settings.PermissionGuideView.Item("background", "后台与锁屏", "后台与电池限制", "系统未报告后台限制；受标准电池优化管理", "厂商自启动、后台运行及锁屏需系统内确认，不自动申请白名单。")));
                                android.widget.ScrollView scroll = new android.widget.ScrollView(theme); scroll.addView(guide);
                                int w = Math.round(width * theme.getResources().getDisplayMetrics().density);
                                int h = Math.round(config.screenHeightDp * theme.getResources().getDisplayMetrics().density);
                                scroll.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                                scroll.layout(0, 0, w, h);
                                assertTextFits(guide);
                                if (palette.equals(AppTheme.OCEAN) && width == 320 && font == 2) capture(scroll, "permissions-320-2-" + dark);
                            }
            });
        }
    }
    @Test public void refreshPreservesRowsAndDoesNotRequestPermissions() {
        try (ActivityScenario<PermissionGuideHarnessActivity> screen = ActivityScenario.launch(PermissionGuideHarnessActivity.class)) {
            screen.onActivity(a -> {
                View row = find(a.guide, "任务与日程提醒，"); assertNotNull(row);
                int children = a.guide.getChildCount();
                a.controller.refresh(); a.controller.refresh();
                assertSame(row, find(a.guide, "任务与日程提醒，"));
                assertEquals(children, a.guide.getChildCount()); assertTrue(a.launches.isEmpty());
                assertNotNull(find(a.guide, "后台与电池限制，"));
                assertNotNull(find(a.guide, "麦克风，")); assertNotNull(find(a.guide, "系统日历，"));
            });
        }
    }
    @Test public void channelAndUnavailableSystemPagesUseSafeFallback() {
        try (ActivityScenario<PermissionGuideHarnessActivity> screen = ActivityScenario.launch(PermissionGuideHarnessActivity.class)) {
            screen.onActivity(a -> {
                find(a.guide, "任务与日程提醒，").performClick();
                Intent first = a.launches.get(0);
                assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, first.getAction());
                assertEquals(a.getPackageName(), first.getStringExtra(Settings.EXTRA_APP_PACKAGE));
                assertEquals("task_reminders", first.getStringExtra(Settings.EXTRA_CHANNEL_ID));
                a.rejectPrimary = true;
                find(a.guide, "勿扰与最终提醒效果，").performClick();
                assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, a.launches.get(a.launches.size() - 1).getAction());
                a.rejectAll = true;
                find(a.guide, "后台与电池限制，").performClick();
                a.controller.refresh();
            });
        }
    }
    private static View find(View root, String description) {
        if (root.getContentDescription() != null && root.getContentDescription().toString().startsWith(description)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), description); if (found != null) return found;
            }
        }
        return null;
    }

    private static void assertTextFits(View root) {
        if (root instanceof android.widget.TextView) {
            android.widget.TextView text = (android.widget.TextView) root;
            assertNotNull(text.getLayout());
            assertTrue("Text clipped: " + text.getText() + " layout=" + text.getLayout().getHeight() + " view=" + text.getHeight(),
                    text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                assertTrue(child.getRight() <= group.getWidth()); assertTrue(child.getLeft() >= 0);
                assertTextFits(child);
            }
        }
    }
    private static void capture(View view, String name) {
        java.io.File directory = new java.io.File(view.getContext().getExternalFilesDir(null), "focus-dial-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        finally { bitmap.recycle(); }
    }
}
