package com.hawson.timeassistant.ui;

import com.hawson.timeassistant.domain.ChatMessage;
import com.hawson.timeassistant.domain.FocusState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable data required to render the conversation page. */
public final class ChatPageUiState {
    private final long revision;
    private final List<ChatMessage> messages;
    private final FocusState focusState;

    public ChatPageUiState(long revision, List<ChatMessage> messages, FocusState focusState) {
        this.revision = revision;
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        this.focusState = focusState == null ? FocusState.idle() : focusState;
    }

    public long getRevision() { return revision; }
    public List<ChatMessage> getMessages() { return messages; }
    public FocusState getFocusState() { return focusState; }
}
