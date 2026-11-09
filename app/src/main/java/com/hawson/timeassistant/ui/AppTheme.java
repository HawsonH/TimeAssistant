/**
 * 应用外观主题
 *
 * 将主题偏好映射到资源样式，并提供统一的主题颜色解析入口。
 */
package com.hawson.timeassistant.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.hawson.timeassistant.R;

public final class AppTheme {
    static final String PREFERENCES_NAME = "time_assistant_ui";
    static final String PREFERENCE_KEY = "theme_id";
    static final String VIOLET = "violet";
    static final String NEUTRAL = "neutral";
    static final String OCEAN = "ocean";
    static final String FOREST = "forest";
    static final String CLAY = "clay";

    private AppTheme() { }

    static String selected(@NonNull SharedPreferences preferences) {
        return normalize(preferences.getString(PREFERENCE_KEY, NEUTRAL));
    }

    static String initialTheme(String stored, boolean existingUser) {
        return stored == null ? (existingUser ? VIOLET : NEUTRAL) : normalize(stored);
    }

    static void initialize(SharedPreferences preferences, boolean existingUser) {
        if (!preferences.contains(PREFERENCE_KEY)) {
            preferences.edit().putString(PREFERENCE_KEY, initialTheme(null, existingUser)).apply();
        }
    }

    static int styleFor(String themeId) {
        switch (normalize(themeId)) {
            case NEUTRAL: return R.style.Theme_TimeAssistantNeutral;
            case OCEAN: return R.style.Theme_TimeAssistantOcean;
            case FOREST: return R.style.Theme_TimeAssistantForest;
            case CLAY: return R.style.Theme_TimeAssistantClay;
            case VIOLET: return R.style.Theme_TimeAssistantViolet;
            default: return R.style.Theme_TimeAssistantNeutral;
        }
    }

    static String normalize(String themeId) {
        if (NEUTRAL.equals(themeId) || VIOLET.equals(themeId) || OCEAN.equals(themeId) || FOREST.equals(themeId) || CLAY.equals(themeId)) {
            return themeId;
        }
        return NEUTRAL;
    }

    @ColorInt
    public static int resolveColor(@NonNull Context context, int attribute,
                                   @ColorRes int fallbackColor) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attribute, value, true)) {
            return ContextCompat.getColor(context, fallbackColor);
        }
        if (value.resourceId != 0) {
            return ContextCompat.getColor(context, value.resourceId);
        }
        return value.data;
    }
}
