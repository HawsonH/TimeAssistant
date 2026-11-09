package com.hawson.timeassistant.ui;

import androidx.lifecycle.LiveData;

import androidx.annotation.NonNull;

import com.hawson.timeassistant.data.AppState;
import com.hawson.timeassistant.ai.TaskActionExecutor;
import com.hawson.timeassistant.domain.ChatMessage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ChatPageViewModel extends RepositoryPageViewModel<ChatPageUiState> {
    ComposerSubmission<PendingAttachment> preparingSubmission, recoverableSubmission;
    long composerRevision;
    final List<PendingAttachment> attachments = new java.util.ArrayList<>();
    private final Map<String, String> proposalSummaries = new HashMap<>();
    private final Map<String, TaskActionExecutor.ExecutionResult> undoResults = new HashMap<>();
    private final Set<String> focusResults = new HashSet<>();
    private final Set<String> busyProposals = new HashSet<>();
    private final androidx.lifecycle.MutableLiveData<Long> proposalVersion = new androidx.lifecycle.MutableLiveData<>(0L);

    public LiveData<Long> proposalChanges() { return proposalVersion; }

    private void notifyProposalChanged() {
        Long version = proposalVersion.getValue();
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            proposalVersion.setValue(version == null ? 1L : version + 1L);
        else proposalVersion.postValue(version == null ? 1L : version + 1L);
    }

    public ChatPageViewModel(@NonNull LiveData<AppState> source) { super(source); }

    /** Main-thread session state only; callers own execution, persistence and row invalidation. */
    public void recordProposalResult(String messageId, String summary,
                                     TaskActionExecutor.ExecutionResult undoResult, boolean focusPrepared) {
        proposalSummaries.put(messageId, summary);
        if (undoResult == null) undoResults.remove(messageId); else undoResults.put(messageId, undoResult);
        if (focusPrepared) focusResults.add(messageId); else focusResults.remove(messageId);
        busyProposals.remove(messageId);
        notifyProposalChanged();
    }

    public TaskActionExecutor.ExecutionResult proposalUndoResult(String messageId) {
        return undoResults.get(messageId);
    }

    public void setProposalBusy(String messageId, boolean busy) {
        if (busy) busyProposals.add(messageId); else busyProposals.remove(messageId);
        notifyProposalChanged();
    }

    public boolean isProposalBusy(String messageId) {
        return busyProposals.contains(messageId);
    }

    public ProposalPresentation proposalPresentation(ChatMessage message, int count,
                                                      List<Integer> pending, boolean historical,
                                                      boolean canUndo, String undoUnavailableReason) {
        return new ProposalPresentation(message, count, pending, historical,
                proposalSummaries.get(message.getId()), canUndo && undoResults.containsKey(message.getId()),
                undoUnavailableReason, isProposalBusy(message.getId()), focusResults.contains(message.getId()));
    }

    @Override
    protected void onCleared() {
        proposalSummaries.clear();
        undoResults.clear();
        focusResults.clear();
        busyProposals.clear();
        super.onCleared();
    }

    @Override
    protected ChatPageUiState project(AppState state) {
        return new ChatPageUiState(state.getRevision(), state.getChatMessages(),
                state.getCurrentFocus());
    }
}
