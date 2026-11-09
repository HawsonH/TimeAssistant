package com.hawson.timeassistant.design;

import android.view.View;
import android.widget.FrameLayout;

/** Owns only page presentation; replacing or finishing a transition never changes application state. */
public final class ContentTransition {
    private ContentTransition() { }

    public static void replace(FrameLayout container, View incoming, int direction, boolean animate) {
        State previous = (State) container.getTag(R.id.content_transition_state);
        if (previous != null && previous.incoming == incoming) {
            if (previous.handle != null) previous.handle.finish();
            return;
        }
        if (previous != null) previous.interrupt(container);
        View old = container.getChildCount() == 0 ? null : container.getChildAt(0);
        if (old == incoming) return;
        if (old != null) MotionSpec.cancelTree(old);
        if (!animate || old == null || MotionSpec.duration(container.getContext(), MotionSpec.CONTENT) == 0) {
            container.removeAllViews();
            container.addView(incoming, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        State state = new State(old, incoming, previous);
        container.setTag(R.id.content_transition_state, state);
        old.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        container.addView(incoming, new FrameLayout.LayoutParams(-1, -1));
        incoming.setClickable(true);
        float offset = MotionSpec.full(container.getContext()) ? UiKit.dp(container.getContext(), 4) * direction : 0;
        incoming.setAlpha(0f);
        incoming.setTranslationX(state.translation + offset);
        state.handle = MotionSpec.animate(container, MotionSpec.CONTENT, f -> {
            old.setAlpha(state.oldAlpha * (1 - f));
            incoming.setAlpha(state.alpha * f);
            incoming.setTranslationX(state.translation + offset * (1 - f));
        }, () -> state.finish(container));
    }

    private static final class State {
        final View old, incoming;
        final float alpha, translation, oldAlpha, oldRestAlpha, oldRestTranslation;
        final int oldAccessibility;
        final boolean clickable;
        MotionHandle handle;
        State(View old, View incoming, State previous) {
            this.old = old; this.incoming = incoming;
            alpha = incoming.getAlpha(); translation = incoming.getTranslationX();
            oldAlpha = old.getAlpha(); clickable = incoming.isClickable();
            oldRestAlpha = previous != null && old == previous.incoming ? previous.alpha : oldAlpha;
            oldRestTranslation = previous != null && old == previous.incoming ? previous.translation : old.getTranslationX();
            oldAccessibility = old.getImportantForAccessibility();
        }
        void interrupt(FrameLayout container) {
            if (handle != null) handle.cancel();
            container.removeView(old);
            restoreOutgoing();
            incoming.setClickable(clickable);
            container.setTag(R.id.content_transition_state, null);
        }
        void finish(FrameLayout container) {
            container.removeView(old);
            restoreOutgoing();
            incoming.setClickable(clickable);
            incoming.setAlpha(alpha); incoming.setTranslationX(translation);
            if (container.getTag(R.id.content_transition_state) == this)
                container.setTag(R.id.content_transition_state, null);
        }
        void restoreOutgoing() {
            old.setAlpha(oldRestAlpha); old.setTranslationX(oldRestTranslation);
            old.setImportantForAccessibility(oldAccessibility);
        }
    }
}
