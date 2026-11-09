package com.hawson.timeassistant.ui;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.hawson.timeassistant.data.AppState;

/** Maps the repository's application state into one page-specific immutable state. */
abstract class RepositoryPageViewModel<T> extends ViewModel {
    private final LiveData<AppState> source;
    private final MutableLiveData<T> uiState = new MutableLiveData<>();
    private final Observer<AppState> stateObserver = state -> uiState.setValue(project(state));

    RepositoryPageViewModel(LiveData<AppState> source) {
        this.source = source;
        source.observeForever(stateObserver);
    }

    public final LiveData<T> getUiState() { return uiState; }

    protected abstract T project(AppState state);

    @Override
    protected void onCleared() {
        source.removeObserver(stateObserver);
    }
}
