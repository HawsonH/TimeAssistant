package com.hawson.timeassistant.focus;

import com.hawson.timeassistant.data.AppRepository;

/** Shared foreground/background entry point for idempotent natural timer completion. */
public final class FocusCompletionUseCase {
    private final AppRepository repository;

    public FocusCompletionUseCase(AppRepository repository) {
        if (repository == null) throw new IllegalArgumentException("repository must not be null");
        this.repository = repository;
    }

    public AppRepository.FocusCompletion completeIfDue(long now) {
        return repository.completeCurrentFocusNaturally(now);
    }
}
