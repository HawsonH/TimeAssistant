package com.hawson.timeassistant.testing;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.hawson.timeassistant.R;
import com.hawson.timeassistant.navigation.GuideRegistry;
import com.hawson.timeassistant.navigation.GuideTarget;
import com.hawson.timeassistant.navigation.TutorialFeature;
import com.hawson.timeassistant.onboarding.TutorialCoordinator;

/** Debug-only host isolates tutorial verification from application business state. */
public final class TutorialHarnessActivity extends AppCompatActivity {
    public static final String TEST_PREFS = "tutorial_test_progress";
    private TutorialFeature tutorial;

    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return super.getSharedPreferences(
                "time_assistant_tutorial".equals(name) ? TEST_PREFS : name, mode);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView target = new TextView(this);
        target.setText("教程测试锚点");
        setContentView(target);
        GuideRegistry registry = new GuideRegistry();
        registry.register(GuideTarget.CREATE_TASK, target);
        tutorial = new TutorialCoordinator(this, registry, R.drawable.ic_launcher_foreground);
        tutorial.initialize(getIntent().getBooleanExtra("existing_user", false));
    }

    public void replay() { tutorial.replay(); }
    @Override protected void onStart() { super.onStart(); tutorial.onStart(); }
    @Override protected void onStop() { tutorial.onStop(); super.onStop(); }
    @Override protected void onDestroy() { tutorial.dispose(); super.onDestroy(); }
}
