package com.hawson.timeassistant.ui;

import com.hawson.timeassistant.domain.AppSettings;

/** Immutable settings-page summary that also exposes recoverable-storage state. */
public final class SettingsPageUiState {
    private final long revision;
    private final AppSettings settings;
    private final int taskCount;
    private final int chatCount;
    private final int focusRecordCount;
    private final String recoveryMessage;
    private final boolean writesBlocked;

    public SettingsPageUiState(long revision, AppSettings settings, int taskCount, int chatCount,
                               int focusRecordCount, String recoveryMessage, boolean writesBlocked) {
        this.revision = revision;
        this.settings = settings.copy();
        this.taskCount = taskCount;
        this.chatCount = chatCount;
        this.focusRecordCount = focusRecordCount;
        this.recoveryMessage = recoveryMessage;
        this.writesBlocked = writesBlocked;
    }

    public long getRevision() { return revision; }
    public AppSettings getSettings() { return settings.copy(); }
    public int getTaskCount() { return taskCount; }
    public int getChatCount() { return chatCount; }
    public int getFocusRecordCount() { return focusRecordCount; }
    public String getRecoveryMessage() { return recoveryMessage; }
    public boolean isWritesBlocked() { return writesBlocked; }
}
