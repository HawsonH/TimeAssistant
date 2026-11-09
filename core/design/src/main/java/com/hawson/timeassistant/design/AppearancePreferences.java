package com.hawson.timeassistant.design;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;

public final class AppearancePreferences {
    private final SharedPreferences preferences;

    public AppearancePreferences(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences("time_assistant_appearance", Context.MODE_PRIVATE);
    }

    public int nightMode() {
        int value = preferences.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        return value == AppCompatDelegate.MODE_NIGHT_YES || value == AppCompatDelegate.MODE_NIGHT_NO
                ? value : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    public void setNightMode(int value) {
        preferences.edit().putInt("night_mode", value).apply();
        AppCompatDelegate.setDefaultNightMode(nightMode());
    }

    public MotionSpec.Mode motionMode() {
        try {
            return MotionSpec.Mode.valueOf(preferences.getString("motion", "FULL"));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return MotionSpec.Mode.FULL;
        }
    }

    public void setMotionMode(MotionSpec.Mode value) {
        preferences.edit().putString("motion", value.name()).apply();
    }
}
