package com.hawson.timeassistant.ui;

import android.view.LayoutInflater;

import androidx.annotation.Nullable;

import com.hawson.timeassistant.databinding.PageSettingsBinding;

/** Owns the settings-page binding and its latest reactive state. */
public final class SettingsPageController {
    private PageSettingsBinding binding;
    private SettingsPageUiState state;

    public PageSettingsBinding inflate(LayoutInflater inflater) {
        binding = PageSettingsBinding.inflate(inflater);
        return binding;
    }

    public void render(SettingsPageUiState nextState) { state = nextState; }
    @Nullable public PageSettingsBinding getBinding() { return binding; }
    @Nullable public SettingsPageUiState getState() { return state; }
    public void clearBinding() { binding = null; }
}
