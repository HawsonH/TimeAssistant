package com.hawson.timeassistant.ui;

import android.view.LayoutInflater;
import androidx.annotation.Nullable;
import com.hawson.timeassistant.databinding.PageScheduleBinding;
import com.hawson.timeassistant.design.ContentTransition;
import com.hawson.timeassistant.design.MotionSpec;

/** Owns schedule binding, presentation and per-tab scroll positions. */
public final class SchedulePageController {
    private PageScheduleBinding binding;
    private SchedulePageUiState state;
    private final java.util.Map<String, Integer> scrollPositions = new java.util.HashMap<>();

    public void saveScroll(String key) {
        if (binding != null) scrollPositions.put(key, binding.scheduleScroll.getScrollY());
    }
    public void restoreScroll(String key) {
        PageScheduleBinding target = binding;
        int offset = scrollPositions.getOrDefault(key, 0);
        if (target != null) target.scheduleScroll.post(() -> target.scheduleScroll.scrollTo(0, offset));
    }
    public boolean hasSavedScroll(String key) { return scrollPositions.containsKey(key); }
    public void replace(android.widget.FrameLayout container, android.view.View incoming, int direction) {
        ContentTransition.replace(container, incoming, direction, true);
    }
    public PageScheduleBinding inflate(LayoutInflater inflater) {
        if (binding != null && binding.getRoot().isAttachedToWindow()) {
            binding.scheduleBody.removeAllViews();
            return binding;
        }
        binding = PageScheduleBinding.inflate(inflater);
        return binding;
    }
    public void animateBody(int direction) {
        if (binding != null) MotionSpec.enterHorizontal(binding.scheduleBody, direction);
    }
    public void render(SchedulePageUiState nextState) { state = nextState; }
    @Nullable public PageScheduleBinding getBinding() { return binding; }
    @Nullable public SchedulePageUiState getState() { return state; }
    public void clearBinding() {
        if (binding != null) MotionSpec.cancelTree(binding.getRoot());
        binding = null;
    }
}
