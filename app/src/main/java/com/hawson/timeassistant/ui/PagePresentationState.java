package com.hawson.timeassistant.ui;

import android.os.Bundle;

/** Screen-only state: never persisted with tasks, credentials or synchronization data. */
final class PagePresentationState {
    final int[] scroll = new int[3];
    boolean followChat = true;
    final ChatDisplayState chat = new ChatDisplayState();

    void save(Bundle bundle) {
        bundle.putIntArray("ui.page.scroll", scroll);
        bundle.putBoolean("ui.chat.follow", followChat);
        bundle.putBundle("ui.chat.display", chat.save());
    }

    void restore(Bundle bundle) {
        if (bundle == null) return;
        int[] saved = bundle.getIntArray("ui.page.scroll");
        if (saved != null) System.arraycopy(saved, 0, scroll, 0, Math.min(scroll.length, saved.length));
        followChat = bundle.getBoolean("ui.chat.follow", true);
        chat.restore(bundle.getBundle("ui.chat.display"));
    }
}
