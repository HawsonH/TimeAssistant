package com.hawson.timeassistant.testing;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ScrollView;
import androidx.appcompat.app.AppCompatActivity;
import com.hawson.timeassistant.ui.settings.SystemPermissionGuideController;
import com.hawson.timeassistant.settings.PermissionGuideView;
import java.util.ArrayList;
import java.util.List;

/** Intercepts system navigation. Tests never change actual device permissions. */
public final class PermissionGuideHarnessActivity extends AppCompatActivity {
    public final List<Intent> launches = new ArrayList<>();
    public boolean rejectPrimary, rejectAll;
    public SystemPermissionGuideController controller;
    public PermissionGuideView guide;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        controller = new SystemPermissionGuideController(this);
        guide = controller.createView();
        ScrollView scroll = new ScrollView(this); scroll.addView(guide); setContentView(scroll);
    }
    @Override public void startActivity(Intent intent) {
        launches.add(intent);
        if (rejectAll || rejectPrimary && !android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(intent.getAction()))
            throw new ActivityNotFoundException("Simulated unavailable system screen");
    }
}
