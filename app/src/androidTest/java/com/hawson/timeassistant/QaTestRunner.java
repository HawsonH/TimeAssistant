package com.hawson.timeassistant;

import androidx.test.runner.AndroidJUnitRunner;

/** Destructive repository fixtures must never execute against the user's release package. */
public final class QaTestRunner extends AndroidJUnitRunner {
    @Override public void onStart() {
        String id = getTargetContext().getPackageName();
        if (!id.endsWith(".qa") && !id.endsWith(".qa.callback")) {
            throw new IllegalStateException("Instrumentation is restricted to the QA applicationId");
        }
        boolean emulator = android.os.Build.FINGERPRINT.contains("generic")
                || android.os.Build.MODEL.contains("sdk_gphone");
        if (!emulator && !id.equals("com.hawson.timeassistant.interaction.qa")) {
            throw new IllegalStateException("Account-bearing QA packages are protected; use -PinteractionQa=true");
        }
        super.onStart();
    }
}
