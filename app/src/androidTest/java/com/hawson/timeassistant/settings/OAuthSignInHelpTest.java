package com.hawson.timeassistant.settings;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.DeviceFixtureSafety;
import com.hawson.timeassistant.testing.AiSettingsHarnessActivity;
import org.junit.Test;
import static org.junit.Assert.*;

/** Presentation-only fixtures: never start OAuth, touch credentials, or read the clipboard. */
public class OAuthSignInHelpTest {
    @Test public void pendingHelpOffersLocalRecoveryWithoutExecutingIt() {
        int[] requested = {0};
        try (ActivityScenario<AiSettingsHarnessActivity> screen = launch()) {
            screen.onActivity(activity -> {
                AlertDialog dialog = OAuthSignInHelp.show(activity, true, () -> requested[0]++);
                TextView message = dialog.findViewById(android.R.id.message);
                assertNotNull(message);
                assertTrue(message.getText().toString().contains("复制地址栏中的完整地址"));
                assertTrue(message.getText().toString().contains("不要发到聊天"));
                assertTrue(message.getText().toString().contains("十分钟"));
                assertEquals(0, requested[0]);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> assertEquals(1, requested[0]));
        }
    }

    @Test public void completedOrExpiredHelpDoesNotOfferStaleCallback() {
        try (ActivityScenario<AiSettingsHarnessActivity> screen = launch()) {
            screen.onActivity(activity -> {
                AlertDialog dialog = OAuthSignInHelp.show(activity, false,
                        () -> fail("No pending sign-in must not trigger callback entry"));
                View paste = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                assertTrue(paste == null || paste.getVisibility() != View.VISIBLE);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
        }
    }

    @Test public void recoveryCommandsRemainVisibleAtCurrentDisplaySize() throws Exception {
        AlertDialog[] shown = {null};
        boolean[] needsScroll = {false};
        try (ActivityScenario<AiSettingsHarnessActivity> screen = launch()) {
            String variant = InstrumentationRegistry.getArguments().getString("uiVariant", "default");
            if ("landscape".equals(variant)) {
                screen.onActivity(activity -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                boolean[] landscape = {false};
                do {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    screen.onActivity(activity -> landscape[0] = activity.getResources().getConfiguration().orientation
                            == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
                } while (!landscape[0] && android.os.SystemClock.elapsedRealtime() < deadline);
                assertTrue("Landscape must actually be applied", landscape[0]);
                // Resource orientation changes before the system rotation animation finishes.
                android.os.SystemClock.sleep(1000);
            }
            screen.onActivity(activity -> shown[0] = OAuthSignInHelp.show(activity, true, () -> { }));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> {
                for (int action : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL}) {
                    View button = shown[0].getButton(action);
                    Rect visible = new Rect();
                    assertTrue(button.getGlobalVisibleRect(visible));
                    assertEquals("Command must be fully visible", button.getHeight(), visible.height());
                    assertEquals("Command must not overflow horizontally", button.getWidth(), visible.width());
                }
            });
            InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(300, 5000);
            long initialPixels = capture(variant);
            screen.onActivity(activity -> {
                TextView message = shown[0].findViewById(android.R.id.message);
                android.view.ViewParent parent = message.getParent();
                while (parent instanceof View) {
                    if (parent instanceof androidx.core.widget.NestedScrollView) {
                        needsScroll[0] = ((View) parent).canScrollVertically(1);
                        ((androidx.core.widget.NestedScrollView) parent).scrollTo(0, message.getHeight());
                        break;
                    }
                    if (parent instanceof android.widget.ScrollView) {
                        needsScroll[0] = ((View) parent).canScrollVertically(1);
                        ((android.widget.ScrollView) parent).scrollTo(0, message.getHeight());
                        break;
                    }
                    parent = parent.getParent();
                }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(300, 5000);
            long finalPixels = capture(variant + "-end");
            if (needsScroll[0]) assertNotEquals("Scrolling must change the rendered tutorial", initialPixels, finalPixels);
            screen.onActivity(activity -> {
                TextView message = shown[0].findViewById(android.R.id.message);
                Rect lastLine = new Rect();
                message.getLayout().getLineBounds(message.getLayout().getLineCount() - 1, lastLine);
                // Dialog window coordinates differ from absolute screen coordinates.
                lastLine.offset(message.getTotalPaddingLeft(), message.getTotalPaddingTop());
                Rect visible = new Rect();
                assertTrue(message.getLocalVisibleRect(visible));
                assertTrue("Final privacy guidance must be reachable by scrolling: line=" + lastLine
                                + "; visible=" + visible + "; textHeight=" + message.getHeight()
                                + "; layoutHeight=" + message.getLayout().getHeight()
                                + "; scrollY=" + message.getScrollY(),
                        lastLine.top >= visible.top && lastLine.bottom <= visible.bottom);
            });
            screen.onActivity(activity -> shown[0].dismiss());
        }
    }

    private long capture(String variant) throws Exception {
            // Idle message queues do not guarantee that SurfaceFlinger has presented the new frame.
            android.os.SystemClock.sleep(500);
            java.io.File directory = new java.io.File(ApplicationProvider.<Context>getApplicationContext()
                    .getExternalFilesDir(null), "ui-workflows");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            android.graphics.Bitmap bitmap = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().takeScreenshot();
            assertNotNull(bitmap);
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                    new java.io.File(directory, "oauth-login-help-" + variant + ".png"))) {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
                java.util.Set<Integer> colors = new java.util.HashSet<>();
                long pixels = 1;
                for (int y = bitmap.getHeight() / 5; y < bitmap.getHeight() * 4 / 5; y += 8) {
                    for (int x = bitmap.getWidth() / 8; x < bitmap.getWidth() * 7 / 8; x += 8) {
                        colors.add(bitmap.getPixel(x, y));
                        pixels = pixels * 31 + bitmap.getPixel(x, y);
                    }
                }
                assertTrue("Tutorial screenshot must contain rendered content, not a blank window", colors.size() > 10);
                return pixels;
            } finally { bitmap.recycle(); }
    }

    private ActivityScenario<AiSettingsHarnessActivity> launch() {
        Context context = ApplicationProvider.getApplicationContext();
        DeviceFixtureSafety.requireIsolatedTarget(context);
        assertFalse(context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());
        assertTrue(context.getSystemService(android.os.PowerManager.class).isInteractive());
        return ActivityScenario.launch(AiSettingsHarnessActivity.class);
    }
}
