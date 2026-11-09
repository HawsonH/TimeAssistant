package com.hawson.timeassistant.navigation;

import android.app.Activity;

public interface TutorialFactory {
    TutorialFeature create(Activity activity, GuideRegistry registry, int brandIcon);
}
