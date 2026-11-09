package com.hawson.timeassistant.navigation;

import android.view.View;
import java.lang.ref.WeakReference;
import java.util.EnumMap;

/** Semantic anchors are rebound whenever a page is recreated; no cross-module resource IDs. */
public final class GuideRegistry {
    private final EnumMap<GuideTarget, WeakReference<View>> anchors =
            new EnumMap<>(GuideTarget.class);

    public void register(GuideTarget target, View view) {
        anchors.put(target, new WeakReference<>(view));
    }

    public View find(GuideTarget target) {
        WeakReference<View> reference = anchors.get(target);
        View view = reference == null ? null : reference.get();
        return view != null && view.isAttachedToWindow() && view.isShown() ? view : null;
    }

    public void clear() { anchors.clear(); }
}
