package com.hawson.timeassistant.ui;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class ComposerSubmissionTest {
    @Test public void snapshotsDoNotShareLiveAttachmentsAndAcceptExactlyOnce() {
        ArrayList<String> live = new ArrayList<>(Arrays.asList("one", "two"));
        ComposerSubmission<String> send = new ComposerSubmission<>("hello", live, "profile-1", 1);
        live.clear(); live.add("new");
        assertEquals(Arrays.asList("one", "two"), send.attachments);
        assertTrue(send.accept("profile-1", 1)); assertFalse(send.accept("profile-1", 1));
        assertFalse(send.cancel());
    }
    @Test public void cancelAndSessionChangesRejectLateCallbacks() {
        ComposerSubmission<String> send = new ComposerSubmission<>("hello", Arrays.asList("one"), "a", 1);
        assertFalse(send.accept("b", 1)); assertFalse(send.accept("a", 2));
        assertTrue(send.cancel()); assertFalse(send.accept("a", 1));
        ComposerSubmission<String> next = new ComposerSubmission<>("next", Arrays.asList("two"), "b", 2);
        assertTrue(next.id > send.id); assertTrue(next.accept("b", 2));
    }
}
