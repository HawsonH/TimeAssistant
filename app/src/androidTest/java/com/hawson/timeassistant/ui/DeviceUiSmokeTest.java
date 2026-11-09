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
import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.connections.AiProfile;
import com.hawson.timeassistant.data.AppRepository;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Read-only navigation: deliberately contains no fixtures, clearing, login or network probes. */
@RunWith(AndroidJUnit4.class)
public class DeviceUiSmokeTest {
    private void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        android.os.SystemClock.sleep(350);
    }
    @Test public void navigationPreservesUserDataAndConnection() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AppRepository repository = AppRepository.get(context);
        com.hawson.timeassistant.AppGraph graph = ((TimeAssistantApplication) context).getAppGraph();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            idle();
            JSONObject before = new JSONObject(repository.exportJson());
            String connection = connectionState(graph);
            int[] originalPage = {0};
            scenario.onActivity(activity -> {
                originalPage[0] = activity.currentPage;
                activity.showPage(0);
            }); idle();
            scenario.onActivity(activity -> assertNotNull(activity.findViewById(R.id.chatInput)));
            scenario.onActivity(activity -> activity.showPage(1)); idle();
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.selectedDateButton));
                activity.showPage(2);
            }); idle();
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.timerRing));
                activity.openSettings();
            }); idle();
            if ("1".equals(InstrumentationRegistry.getArguments().getString("captureDevice"))) {
                android.graphics.Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                java.io.File directory = new java.io.File(context.getExternalFilesDir(null), "ui-device");
                assertTrue(directory.isDirectory() || directory.mkdirs());
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, "oneplus-settings.png"))) {
                    assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
                }
                image.recycle();
            }
            for (String section : new String[]{"外观与教程", "AI 连接与账号", "主动整理", "安排偏好与通知", "日历连接", "同步与数据"}) {
                scenario.onActivity(activity -> clickText(activity.pageContainer, section)); idle();
                scenario.onActivity(activity -> {
                    TextView title = activity.findViewById(R.id.settingsTitle);
                    assertEquals(section, title.getText().toString());
                    activity.findViewById(R.id.backButton).performClick();
                }); idle();
            }
            scenario.onActivity(activity -> { activity.closeSettings(); activity.showPage(originalPage[0]); });
            idle();
            JSONObject after = new JSONObject(repository.exportJson());
            for (String key : new String[]{"tasks", "recurringTaskGroups", "recurringOccurrenceOverrides", "chatMessages", "focusRecords", "settings"}) {
                assertTrue("Navigation modified " + key, before.optString(key).equals(after.optString(key)));
            }
            assertTrue("Navigation changed connection/model selection", connection.equals(connectionState(graph)));
            long authorized = graph.ai.accounts.accounts().stream().filter(account -> account.authorized).count();
            android.util.Log.i("DeviceUiSmoke", "Profiles=" + graph.ai.profiles.list().size() + "; authorizedAccounts=" + authorized);
            android.util.Log.i("DeviceUiSmoke", "Read-only navigation passed; persisted task/chat/settings and connection/model unchanged; no model request issued");
        }
    }
    private String connectionState(com.hawson.timeassistant.AppGraph graph) {
        StringBuilder state = new StringBuilder();
        AiProfile active = graph.ai.profiles.active();
        state.append(active == null ? "" : active.id);
        for (AiProfile profile : graph.ai.profiles.list()) state.append('|').append(profile.id).append(':').append(profile.model);
        for (com.hawson.timeassistant.connections.AiAccountPort.Account account : graph.ai.accounts.accounts()) {
            state.append('|').append(account.reference).append(':').append(account.authorized);
        }
        return state.toString();
    }
    private void clickText(View view, String text) {
        View found = find(view, text);
        assertNotNull("Missing section " + text, found);
        while (!found.isClickable()) found = (View) found.getParent();
        found.performClick();
    }
    private View find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText()) && view.isShown()) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = find(group.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }
}
