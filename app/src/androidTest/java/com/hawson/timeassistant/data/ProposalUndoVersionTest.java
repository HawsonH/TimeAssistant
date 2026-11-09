package com.hawson.timeassistant.data;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.hawson.timeassistant.domain.AppSettings;
import com.hawson.timeassistant.domain.FocusState;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Uses Android JSON but no Context, database, secure store, device state or network. */
@RunWith(AndroidJUnit4.class)
public final class ProposalUndoVersionTest {
    @Test
    public void restoredSettingsWinOverFutureDatedExecutedSettingsAndKeepLocalKey() throws Exception {
        AppRepository repository = new AppRepository(() -> {});
        repository.saveSettings(repository.getSettings().setApiKey("test-only-secret"));
        String originalGreeting = repository.getSettings().getGreetingText();
        long future = System.currentTimeMillis() + 86_400_000L;
        AppRepository.ProposalSessionResult<Void> session = repository.runProposalSession("settings", () -> {
            JSONObject changed = repository.getSettings().setGreetingText("changed").toJson();
            changed.put("updatedAt", future);
            repository.saveSettings(AppSettings.fromJson(changed));
            return null;
        });
        assertTrue(repository.undoProposalSession(session.undoTicket, "settings"));
        AppSettings restored = repository.getSettings();
        assertEquals(originalGreeting, restored.getGreetingText());
        assertEquals("test-only-secret", restored.getApiKey());
        assertTrue(restored.getUpdatedAt() > future);
    }

    @Test
    public void restoredFocusWinsOverFutureDatedExecutedFocus() throws Exception {
        AppRepository repository = new AppRepository(() -> {});
        long future = System.currentTimeMillis() + 86_400_000L;
        AppRepository.ProposalSessionResult<Void> session = repository.runProposalSession("focus", () -> {
            repository.setCurrentFocus(new FocusState(null, FocusState.Mode.FOCUS,
                    60_000L, 60_000L, FocusState.State.READY, 0L, 0, 0, future));
            return null;
        });
        assertTrue(repository.undoProposalSession(session.undoTicket, "focus"));
        assertEquals(FocusState.State.IDLE, repository.getCurrentFocus().getState());
        assertTrue(repository.getCurrentFocus().getUpdatedAt() > future);
    }
}
