package com.hawson.timeassistant.ui;

import androidx.lifecycle.LiveData;

import androidx.annotation.NonNull;

import com.hawson.timeassistant.data.AppState;

public final class SchedulePageViewModel extends RepositoryPageViewModel<SchedulePageUiState> {
    public final com.hawson.timeassistant.ui.schedule.AttentionCompletionBuffer completions =
            new com.hawson.timeassistant.ui.schedule.AttentionCompletionBuffer();
    public SchedulePageViewModel(@NonNull LiveData<AppState> source) { super(source); }

    @Override
    protected SchedulePageUiState project(AppState state) {
        return new SchedulePageUiState(state.getRevision(), state.getTasks(),
                state.getRecurringGroups(), state.getRecurringOverrides());
    }
}
