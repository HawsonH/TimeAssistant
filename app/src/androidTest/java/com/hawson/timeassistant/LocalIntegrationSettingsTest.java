package com.hawson.timeassistant;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.hawson.timeassistant.data.local.LocalIntegrationSettingsStore;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class LocalIntegrationSettingsTest {
    @Test public void defaultsToLearningOffUntilExplicitConsentAndCalendarExperimentOff() {
        Context context = ApplicationProvider.getApplicationContext();
        LocalIntegrationSettingsStore settings = new LocalIntegrationSettingsStore(context);
        settings.reset();
        assertFalse(settings.isLearningEnabled());
        assertFalse(settings.isCalendarExperimentEnabled());
        settings.grantLearningConsent();
        assertTrue(settings.isLearningEnabled());
    }
}
