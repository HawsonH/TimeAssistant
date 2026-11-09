package com.hawson.timeassistant;

import android.content.Context;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import static org.junit.Assert.assertTrue;

/** Physical-device fixtures require a separate package and explicit runner opt-in. */
public final class DeviceFixtureSafety {
    private DeviceFixtureSafety() { }
    public static void requireIsolatedTarget(Context context) {
        boolean emulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone");
        boolean isolatedPhone = context.getPackageName().equals("com.hawson.timeassistant.interaction.qa")
                && "true".equals(InstrumentationRegistry.getArguments().getString("physicalFixtures"));
        assertTrue("Destructive fixtures require an emulator or opted-in isolated device QA package",
                emulator || isolatedPhone);
    }
}
