package com.hawson.timeassistant.navigation;

/** The application shell can replace this feature without depending on its presentation. */
public interface TutorialFeature {
    void initialize(boolean existingUser);
    void replay();
    void requestGuide(GuideTarget target);
    void onStart();
    void onStop();
    void dispose();
}
