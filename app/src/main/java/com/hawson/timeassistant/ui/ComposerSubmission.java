package com.hawson.timeassistant.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** A snapshot of one send, never a reference to the live composer. */
final class ComposerSubmission<T> {
    private static final AtomicLong IDS = new AtomicLong();
    final long id = IDS.incrementAndGet();
    final String text, connection;
    final long conversation;
    final List<T> attachments;
    long clearedRevision;
    private boolean pending = true;

    ComposerSubmission(String text, List<T> attachments, String connection, long conversation) {
        this.text = text; this.attachments = Collections.unmodifiableList(new ArrayList<>(attachments));
        this.connection = connection; this.conversation = conversation;
    }
    synchronized boolean accept(String currentConnection, long currentConversation) {
        if (!pending || !connection.equals(currentConnection) || conversation != currentConversation) return false;
        pending = false; return true;
    }
    synchronized boolean cancel() {
        if (!pending) return false;
        pending = false; return true;
    }
}
