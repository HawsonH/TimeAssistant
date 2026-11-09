package com.hawson.timeassistant.ui;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import com.hawson.timeassistant.DeviceFixtureSafety;
import com.hawson.timeassistant.R;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.hawson.timeassistant.ui.WorkflowDriver.*;

/** Local drafts only: no credentials, task content or network requests are used. */
public class DeviceConnectionUiTest {
    private Context prepare() {
        Context context = ApplicationProvider.getApplicationContext();
        DeviceFixtureSafety.requireIsolatedTarget(context);
        requireUnlockedDisplay(context);
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().putInt("seen_version", 1)
                .putBoolean("intro_pending", false).putBoolean("context_enabled", false).commit();
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().putInt("last_page", 0).commit();
        return context;
    }

    @Test public void longConnectionDraftStaysAboveKeyboardAndSurvivesRecreation() throws Exception {
        prepare();
        String model = "fixture-only-long-model-name-for-small-screen-and-keyboard-verification";
        String url = "https://example.invalid/fixture-only/long-connection-endpoint/v1";
        String variant = androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("uiVariant", "default");
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            verifyConfiguration(screen);
            screen.onActivity(TimeAssistantActivity::openSettings); idle();
            click("AI 连接与账号"); click("添加连接"); click("自定义服务");
            fillId(com.hawson.timeassistant.settingsfeature.R.id.name, "未保存的本地界面验证");
            fillId(com.hawson.timeassistant.settingsfeature.R.id.url, url);
            fillId(com.hawson.timeassistant.settingsfeature.R.id.model, model);
            clickId(com.hawson.timeassistant.settingsfeature.R.id.model);
            boolean[] visible = {false};
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            do {
                idle();
                screen.onActivity(activity -> {
                    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
                    visible[0] = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
                });
            } while (!visible[0] && SystemClock.elapsedRealtime() < deadline);
            assertTrue("A real software keyboard must be visible", visible[0]);
            capture("connection-" + variant + "-keyboard-long-model");
            screen.onActivity(activity -> {
                EditText field = (EditText) find(v -> v.getId() == com.hawson.timeassistant.settingsfeature.R.id.model);
                Rect frame = new Rect(), bounds = new Rect();
                activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
                assertTrue(field.getGlobalVisibleRect(bounds));
                int[] location = new int[2]; field.getLocationOnScreen(location);
                assertTrue("Focused model field is covered by the keyboard",
                        location[1] + field.getHeight() <= frame.bottom + 2);
            });
            androidx.test.espresso.Espresso.closeSoftKeyboard(); idle();
            screen.recreate(); idle();
            screen.onActivity(activity -> {
                assertEquals(model, ((EditText) find(v -> v.getId() == com.hawson.timeassistant.settingsfeature.R.id.model)).getText().toString());
                assertEquals(url, ((EditText) find(v -> v.getId() == com.hawson.timeassistant.settingsfeature.R.id.url)).getText().toString());
            });
            click("返回连接列表"); click("继续编辑");
            capture("connection-" + variant + "-long-draft-restored");
            click("返回连接列表"); click("放弃");
        }
    }

    @Test public void primaryChatToolsHaveLabelsAnd48DpTouchAreas() {
        Context context = prepare();
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            verifyConfiguration(screen);
            screen.onActivity(activity -> activity.showPage(0)); idle();
            screen.onActivity(activity -> {
                int minimum = Math.round(48 * context.getResources().getDisplayMetrics().density);
                for (int id : new int[]{R.id.settingsButton, R.id.fileButton, R.id.voiceButton, R.id.sendButton}) {
                    View control = activity.findViewById(id);
                    assertNotNull(control);
                    assertTrue("Touch width below 48dp: " + id, control.getWidth() >= minimum);
                    assertTrue("Touch height below 48dp: " + id, control.getHeight() >= minimum);
                    CharSequence description = control.getContentDescription();
                    CharSequence text = control instanceof TextView ? ((TextView) control).getText() : "";
                    assertTrue("Accessible name missing: " + id,
                            (description != null && description.length() > 0) || text.length() > 0);
                }
            });
        }
    }

    @Test public void longAddressAndModelWrapWithoutHorizontalOverflow() throws Exception {
        prepare();
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            verifyConfiguration(screen);
            screen.onActivity(TimeAssistantActivity::openSettings); idle();
            click("AI 连接与账号"); click("添加连接"); click("自定义服务");
            fillId(com.hawson.timeassistant.settingsfeature.R.id.url,
                    "https://example.invalid/fixture-only/long-connection-endpoint/v1");
            fillId(com.hawson.timeassistant.settingsfeature.R.id.model,
                    "fixture-only-long-model-name-for-small-screen-and-keyboard-verification");
            screen.onActivity(activity -> {
                for (int id : new int[]{com.hawson.timeassistant.settingsfeature.R.id.url,
                        com.hawson.timeassistant.settingsfeature.R.id.model}) {
                    EditText field = (EditText) find(view -> view.getId() == id);
                    assertNotNull(field);
                    int available = field.getWidth() - field.getCompoundPaddingLeft() - field.getCompoundPaddingRight();
                    assertFalse("Non-secret input must not scroll horizontally", field.isHorizontallyScrollable());
                    if (field.getPaint().measureText(field.getText().toString()) > available)
                        assertTrue("Long non-secret text must wrap: " + id, field.getLineCount() > 1);
                    for (int line = 0; line < field.getLayout().getLineCount(); line++)
                        assertTrue("Text exceeds input width: " + id, field.getLayout().getLineWidth(line) <= available + 2);
                }
            });
            click("返回连接列表"); click("放弃");
        }
    }

    private void verifyConfiguration(ActivityScenario<MainActivity> screen) {
        String variant = androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("uiVariant", "default");
        if (variant.equals("landscape")) {
            screen.onActivity(activity -> activity.setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            idle();
        }
        screen.onActivity(activity -> {
            android.content.res.Configuration config = activity.getResources().getConfiguration();
            if (variant.equals("landscape")) assertEquals("Landscape must actually be applied",
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE, config.orientation);
            if (variant.equals("font2")) assertTrue("Font scale 2.0 must actually be applied", config.fontScale >= 1.99f);
            if (variant.equals("compact320")) assertTrue("Expected <=320dp viewport", config.screenWidthDp <= 320);
        });
    }
}
