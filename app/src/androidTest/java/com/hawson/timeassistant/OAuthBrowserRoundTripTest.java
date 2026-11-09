package com.hawson.timeassistant;

import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.testing.OAuthBrowserHarnessActivity;
import org.junit.*;
import static org.junit.Assert.*;

public class OAuthBrowserRoundTripTest {
    @Test public void generatedLoopbackPageReturnsThroughRealChromeAuthTab() throws Exception {
        Assume.assumeTrue("browser-fixture".equals(InstrumentationRegistry.getArguments().getString("oauthMode")));
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent start = new Intent(context, OAuthBrowserHarnessActivity.class).putExtra("localFixture", true);
        boolean[] received = {false}, returned = {false}; String[] failure = {""};
        try (ActivityScenario<OAuthBrowserHarnessActivity> scenario = ActivityScenario.launch(start)) {
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                scenario.onActivity(activity -> {
                    received[0] = activity.received; returned[0] = activity.returned; failure[0] = activity.failure;
                });
                if (returned[0] || !failure[0].isEmpty()) break;
                Thread.sleep(100);
            }
            android.os.Bundle report = new android.os.Bundle();
            report.putBoolean("fixture_callback_received", received[0]);
            report.putBoolean("auth_tab_returned", returned[0]); report.putString("fixture_failure", failure[0]);
            InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
            assertTrue("Local browser callback not received: " + failure[0], received[0]);
            assertTrue("Auth Tab did not return: " + failure[0], returned[0]);
        }
    }
}
