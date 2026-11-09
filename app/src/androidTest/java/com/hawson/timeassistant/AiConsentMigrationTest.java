package com.hawson.timeassistant;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.hawson.timeassistant.data.local.LocalIntegrationSettingsStore;
import com.hawson.timeassistant.proactive.ProactiveSettings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AiConsentMigrationTest {
    private Context base;
    private Context isolated;
    private String name;

    @Before public void setUp() {
        base = ApplicationProvider.getApplicationContext();
        assertTrue(base.getPackageName().endsWith(".qa"));
        name = "consent_test_" + UUID.randomUUID();
        isolated = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) {
                return base.getSharedPreferences(name, mode);
            }
        };
    }

    @After public void tearDown() { base.deleteSharedPreferences(name); }

    @Test public void legacyEnabledFlagsDoNotGrantNewConsent() {
        base.getSharedPreferences(name, 0).edit().putBoolean("ai_learning_enabled", true)
                .putBoolean("proactive_enabled", true).commit();
        LocalIntegrationSettingsStore settings = new LocalIntegrationSettingsStore(isolated);
        assertFalse(settings.isLearningEnabled());
        assertFalse(settings.getProactiveSettings().enabled);
    }

    @Test public void learningAndProactiveGrantsAreIndependentAndRevocable() {
        LocalIntegrationSettingsStore settings = new LocalIntegrationSettingsStore(isolated);
        settings.grantLearningConsent();
        assertTrue(settings.isLearningEnabled());
        assertFalse(settings.getProactiveSettings().enabled);
        settings.grantProactiveConsent();
        ProactiveSettings proactive = settings.getProactiveSettings();
        proactive.enabled = true;
        settings.saveProactiveSettings(proactive);
        settings.setLearningEnabled(false);
        assertFalse(settings.isLearningEnabled());
        assertTrue(settings.getProactiveSettings().enabled);
        proactive.enabled = false;
        settings.saveProactiveSettings(proactive);
        assertFalse(settings.getProactiveSettings().enabled);
    }
}
