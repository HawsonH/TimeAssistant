package com.hawson.timeassistant.ui;

/** Position is captured before recording; changed drafts require explicit insertion. */
final class VoiceDraft {
    final String original;
    final int start, end;
    VoiceDraft(String original, int start, int end) {
        this.original = original;
        this.start = Math.max(0, Math.min(original.length(), start));
        this.end = Math.max(this.start, Math.min(original.length(), end));
    }
    String insert(String transcript) { return original.substring(0, start) + transcript + original.substring(end); }
    boolean unchanged(String value) { return original.equals(value); }
}
