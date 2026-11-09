package com.hawson.timeassistant.settings;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.DeviceFixtureSafety;
import com.hawson.timeassistant.connections.AiQuotaSnapshot;
import com.hawson.timeassistant.testing.AiSettingsHarnessActivity;
import com.hawson.timeassistant.quota.sub2api.Sub2ApiQuotaCodec;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Local fixture only: never clears repositories, modifies sources or accesses account credentials. */
public class AiQuotaPresentationTest {
    @Test public void windowsRemainingAndUnknownValuesAreExplicit() throws Exception {
        AiQuotaSnapshot data = fixture();
        try (ActivityScenario<AiSettingsHarnessActivity> screen = launch()) {
            screen.onActivity(activity -> {
                AiQuotaView view = new AiQuotaView(activity); view.render(data, System.currentTimeMillis());
                activity.setContentView(view);
                String all = text(view);
                assertTrue(all.contains("5 小时窗口")); assertTrue(all.contains("每周窗口"));
                assertTrue(all.contains("剩余 68%")); assertTrue(all.contains("余额：12.340000000001"));
                assertTrue(all.contains("剩余比例未知")); assertTrue(all.contains("单个应用限额"));
            });
        }
    }
    @Test public void missingDataDoesNotShowZeroOrPretendOfficialDirectRead() {
        try (ActivityScenario<AiSettingsHarnessActivity> screen = launch()) {
            screen.onActivity(activity -> {
                AiQuotaView view = new AiQuotaView(activity); view.render(null, System.currentTimeMillis());
                activity.setContentView(view);
                assertFalse(text(view).contains("0%")); assertTrue(text(view).contains("尚未读取"));
                String official = activity.getString(com.hawson.timeassistant.settingsfeature.R.string.quota_official_unavailable);
                assertTrue(official.contains("直读接口")); assertTrue(official.contains("未知"));
                view.render(new AiQuotaSnapshot("", "", "", 0, 0, Collections.emptyList(),
                        new AiQuotaSnapshot.Credits(false, false, null), null), System.currentTimeMillis());
                assertTrue(text(view).contains("不可用，余额未返回"));
                assertFalse(text(view).contains("余额：0"));
            });
        }
    }
    @Test public void snapshotCanBeScrolledToFinalNoticeAtCurrentSize() throws Exception {
        AiQuotaSnapshot data = fixture();
        ScrollView[] scroll = {null}; AiQuotaView[] view = {null};
        String variant = InstrumentationRegistry.getArguments().getString("uiVariant", "default");
        try (ActivityScenario<AiSettingsHarnessActivity> screen = launch()) {
            screen.onActivity(activity -> {
                view[0] = new AiQuotaView(activity); view[0].setPadding(48, 48, 48, 48);
                view[0].render(data, System.currentTimeMillis());
                scroll[0] = new ScrollView(activity); scroll[0].addView(view[0]);
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll[0], (v, insets) -> {
                    androidx.core.graphics.Insets bars = insets.getInsets(
                            androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
                });
                activity.setContentView(scroll[0]);
                androidx.core.view.ViewCompat.requestApplyInsets(scroll[0]);
                androidx.core.view.WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView())
                        .setAppearanceLightStatusBars(androidx.core.graphics.ColorUtils.calculateLuminance(
                                com.hawson.timeassistant.design.UiKit.text(activity)) < 0.5);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            android.os.SystemClock.sleep(500);
            capture("quota-fixture-" + variant + "-start.png");
            screen.onActivity(activity -> {
                for (TextView label : labels(view[0])) {
                    assertNotNull(label.getLayout());
                    assertEquals("Quota text must wrap rather than ellipsize", 0,
                            label.getLayout().getEllipsisCount(label.getLayout().getLineCount() - 1));
                }
                scroll[0].scrollTo(0, view[0].getHeight());
            });
            android.os.SystemClock.sleep(500);
            screen.onActivity(activity -> {
                TextView last = labels(view[0]).get(labels(view[0]).size() - 1);
                Rect visible = new Rect(); assertTrue(last.getLocalVisibleRect(visible));
                assertEquals(last.getHeight(), visible.height());
            });
            capture("quota-fixture-" + variant + ".png");
        }
    }
    private void capture(String filename) throws Exception {
        java.io.File directory = new java.io.File(ApplicationProvider.<Context>getApplicationContext().getExternalFilesDir(null), "ui-workflows");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        android.graphics.Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(directory, filename))) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
        } finally { image.recycle(); }
    }
    private AiQuotaSnapshot fixture() throws Exception {
        return Sub2ApiQuotaCodec.decode(new JSONObject("{\"plan_type\":\"plus\",\"fetched_at\":1790000000,\"rate_limit\":{\"allowed\":false,\"primary_window\":{\"limit_window_seconds\":604800,\"used_percent\":50},\"secondary_window\":{\"limit_window_seconds\":18000,\"used_percent\":32,\"reset_at\":1790000600}},\"additional_rate_limits\":[{\"limit_name\":\"其他模型窗口：长名称示例\",\"rate_limit\":{\"primary_window\":{}}}],\"credits\":{\"balance\":\"12.340000000001\"}}"), 0);
    }
    private ActivityScenario<AiSettingsHarnessActivity> launch() {
        Context context = ApplicationProvider.getApplicationContext(); DeviceFixtureSafety.requireIsolatedTarget(context);
        assertFalse(context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());
        assertTrue(context.getSystemService(android.os.PowerManager.class).isInteractive());
        ActivityScenario<AiSettingsHarnessActivity> screen = ActivityScenario.launch(AiSettingsHarnessActivity.class);
        boolean landscape = "landscape".equals(InstrumentationRegistry.getArguments().getString("uiVariant", "default"));
        screen.onActivity(activity -> activity.setRequestedOrientation(landscape
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        android.os.SystemClock.sleep(1200);
        screen.onActivity(activity -> assertEquals(landscape ? android.content.res.Configuration.ORIENTATION_LANDSCAPE
                : android.content.res.Configuration.ORIENTATION_PORTRAIT, activity.getResources().getConfiguration().orientation));
        return screen;
    }
    private static List<TextView> labels(View view) {
        List<TextView> result = new ArrayList<>();
        if (view instanceof TextView) result.add((TextView) view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) result.addAll(labels(((ViewGroup) view).getChildAt(i)));
        return result;
    }
    private static String text(View view) {
        StringBuilder value = new StringBuilder(); for (TextView label : labels(view)) value.append(label.getText()).append('\n');
        return value.toString();
    }
}
