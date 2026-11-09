package com.hawson.timeassistant.ui;

import static org.junit.Assert.assertEquals;

import com.hawson.timeassistant.R;

import org.junit.Test;

public class AppThemeTest {
    @Test
    public void unknownThemeFallsBackToNeutral() {
        assertEquals(AppTheme.NEUTRAL, AppTheme.normalize("unknown"));
        assertEquals(AppTheme.NEUTRAL, AppTheme.normalize(null));
    }

    @Test
    public void migrationRetainsImplicitAndExplicitExistingPalettes() {
        assertEquals(AppTheme.VIOLET, AppTheme.initialTheme(null, true));
        assertEquals(AppTheme.NEUTRAL, AppTheme.initialTheme(null, false));
        assertEquals(AppTheme.OCEAN, AppTheme.initialTheme(AppTheme.OCEAN, true));
        assertEquals(AppTheme.NEUTRAL, AppTheme.initialTheme(AppTheme.NEUTRAL, true));
    }

    @Test
    public void presetsMapToTheirResourceStyles() {
        assertEquals(R.style.Theme_TimeAssistantNeutral, AppTheme.styleFor(AppTheme.NEUTRAL));
        assertEquals(R.style.Theme_TimeAssistantViolet, AppTheme.styleFor(AppTheme.VIOLET));
        assertEquals(R.style.Theme_TimeAssistantOcean, AppTheme.styleFor(AppTheme.OCEAN));
        assertEquals(R.style.Theme_TimeAssistantForest, AppTheme.styleFor(AppTheme.FOREST));
        assertEquals(R.style.Theme_TimeAssistantClay, AppTheme.styleFor(AppTheme.CLAY));
    }
}
