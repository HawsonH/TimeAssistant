package com.hawson.timeassistant.ui;

import android.view.LayoutInflater;

import androidx.annotation.Nullable;

import com.hawson.timeassistant.databinding.PageFocusBinding;
import com.hawson.timeassistant.domain.FocusState;
import com.hawson.timeassistant.ui.widget.TimerRingView;

/** Owns the focus-page binding and its latest reactive state. */
public final class FocusPageController {
    private PageFocusBinding binding;
    private FocusPageUiState state;
    private FocusLayoutTransition layoutTransition;
    private FocusDialController dialController;

    public void attachDial(FocusDialController.Host host) {
        if (binding != null) dialController = new FocusDialController(binding.timerRing, host);
    }

    public boolean isDurationPreviewing() { return dialController != null && dialController.isPreviewing(); }

    public void cancelDialGesture() { if (dialController != null) dialController.cancel(); }

    public PageFocusBinding inflate(LayoutInflater inflater) {
        stopMotion();
        dialController = null;
        binding = PageFocusBinding.inflate(inflater);
        layoutTransition = new FocusLayoutTransition(binding);
        return binding;
    }

    public void setStarted(boolean started, boolean animate) {
        if (layoutTransition != null) layoutTransition.setStarted(started, animate);
    }

    public void stopMotion() {
        cancelDialGesture();
        if (layoutTransition != null) layoutTransition.stopMotion();
        if (binding != null) {
            binding.timerRing.setMotionActive(false);
            binding.timerRing.stopMotion();
        }
    }

    public void updatePresentation(FocusState focus, boolean active) {
        if (binding == null) return;
        binding.timerRing.setMotionActive(active);
        TimerRingView.PresentationState next;
        switch (focus.getState()) {
            case RUNNING: next = TimerRingView.PresentationState.RUNNING; break;
            case PAUSED: next = TimerRingView.PresentationState.PAUSED; break;
            case FINISHED: next = TimerRingView.PresentationState.FINISHED; break;
            default: next = TimerRingView.PresentationState.READY;
        }
        if (next != TimerRingView.PresentationState.READY) cancelDialGesture();
        binding.timerRing.setPresentationState(next);
        if (dialController != null) dialController.updateAccessibility();
        boolean editable = next == TimerRingView.PresentationState.READY
                && focus.getMode() == FocusState.Mode.FOCUS;
        binding.focusTaskTitle.setClickable(editable);
        binding.focusTaskTitle.setFocusable(editable);
    }

    public void render(FocusPageUiState nextState) { state = nextState; }
    @Nullable public PageFocusBinding getBinding() { return binding; }
    @Nullable public FocusPageUiState getState() { return state; }
    public void clearBinding() { stopMotion(); layoutTransition = null; dialController = null; binding = null; }
}
