package com.hawson.timeassistant.ui;

import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.hawson.timeassistant.databinding.PageFocusBinding;
import com.hawson.timeassistant.design.MotionSpec;

/** Owns only the prepared/started layout, independently of timer settlement. */
final class FocusLayoutTransition implements MotionSpec.Owner {
    private final PageFocusBinding binding;
    private final ViewGroup root;
    private Boolean started;

    FocusLayoutTransition(PageFocusBinding binding) {
        this.binding = binding;
        root = binding.getRoot();
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) { stopMotion(); }
        });
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (or > ol && ob > ot && (r - l != or - ol || b - t != ob - ot))
                stopMotion();
        });
    }

    void setStarted(boolean next, boolean animate) {
        if (started != null && started == next) return;
        boolean transition = started != null && animate && root.isLaidOut()
                && root.isShown() && MotionSpec.duration(root.getContext(), MotionSpec.SETTLE) > 0;
        started = next;
        if (transition) {
            TransitionSet changes = new TransitionSet().setOrdering(TransitionSet.ORDERING_TOGETHER);
            if (MotionSpec.full(root.getContext())) {
                ChangeBounds bounds = new ChangeBounds();
                bounds.addTarget(binding.focusContent).addTarget(binding.timerRing)
                        .addTarget(binding.focusTaskTitle).addTarget(binding.focusActions)
                        .addTarget(binding.timerButton).addTarget(binding.endFocusSlot);
                bounds.addTarget((View) binding.focusModes.getParent());
                bounds.addTarget((View) binding.timerRing.getParent());
                if (binding.focusUpperSpace != null) bounds.addTarget(binding.focusUpperSpace);
                if (binding.focusLowerSpace != null) bounds.addTarget(binding.focusLowerSpace);
                changes.addTransition(bounds);
            }
            Fade fade = new Fade();
            fade.addTarget(binding.focusModes).addTarget(binding.endFocusButton);
            changes.addTransition(fade);
            changes.setDuration(MotionSpec.duration(root.getContext(), MotionSpec.SETTLE));
            changes.setInterpolator(MotionSpec.EASE_OUT);
            // A new capture pauses and retargets the running transition from its visible bounds.
            TransitionManager.beginDelayedTransition(root, changes);
        } else {
            TransitionManager.endTransitions(root);
        }
        applyEndpoint();
    }

    private void applyEndpoint() {
        if (started == null) return;
        binding.focusModes.setVisibility(started ? View.GONE : View.VISIBLE);
        binding.timerRing.setExpanded(started);
        LinearLayout.LayoutParams slot = (LinearLayout.LayoutParams) binding.endFocusSlot.getLayoutParams();
        slot.weight = started ? .7f : 0f;
        slot.setMarginStart(started ? Math.round(8 * root.getResources().getDisplayMetrics().density) : 0);
        binding.endFocusSlot.setLayoutParams(slot);
        binding.endFocusButton.setVisibility(started ? View.VISIBLE : View.INVISIBLE);
        binding.focusModes.setImportantForAccessibility(started
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    @Override public void stopMotion() {
        TransitionManager.endTransitions(root);
        applyEndpoint();
    }
}
