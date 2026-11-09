package com.hawson.timeassistant.ui;

import androidx.lifecycle.LiveData;

import androidx.annotation.NonNull;

import com.hawson.timeassistant.data.AppState;

public final class FocusPageViewModel extends RepositoryPageViewModel<FocusPageUiState> {
    public FocusPageViewModel(@NonNull LiveData<AppState> source) { super(source); }

    @Override
    protected FocusPageUiState project(AppState state) {
        return new FocusPageUiState(state.getRevision(), state.getCurrentFocus(),
                state.getSettings(), state.getTasks(), state.getFocusRecords());
    }
}
