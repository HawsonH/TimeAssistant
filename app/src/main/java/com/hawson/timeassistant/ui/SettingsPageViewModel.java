package com.hawson.timeassistant.ui;

import androidx.lifecycle.LiveData;

import androidx.annotation.NonNull;

import com.hawson.timeassistant.data.AppState;

public final class SettingsPageViewModel extends RepositoryPageViewModel<SettingsPageUiState> {
    public SettingsPageViewModel(@NonNull LiveData<AppState> source) { super(source); }

    @Override
    protected SettingsPageUiState project(AppState state) {
        return new SettingsPageUiState(state.getRevision(), state.getSettings(),
                state.getTasks().size(), state.getChatMessages().size(),
                state.getFocusRecords().size(), state.getRecoveryMessage(), state.isWritesBlocked());
    }
}
