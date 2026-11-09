package com.hawson.timeassistant.onboarding;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import androidx.appcompat.app.AlertDialog;
import com.hawson.timeassistant.design.MotionSpec;
import com.hawson.timeassistant.navigation.GuideRegistry;
import com.hawson.timeassistant.navigation.GuideTarget;
import com.hawson.timeassistant.navigation.TutorialFeature;
import com.hawson.timeassistant.onboarding.databinding.DialogTutorialBinding;
import com.google.android.material.snackbar.Snackbar;

/** Owns presentation and local tutorial progress; has no task, network or permission dependency. */
public final class TutorialCoordinator implements TutorialFeature {
    private final Activity activity;
    private final GuideRegistry registry;
    private final SharedPreferences preferences;
    private final int brandIcon;
    private AlertDialog introduction;
    private Snackbar guide;
    private boolean started;
    private boolean introPending;
    private GuideTarget pendingGuide;

    public TutorialCoordinator(Activity activity, GuideRegistry registry, int brandIcon) {
        this.activity = activity;
        this.registry = registry;
        this.brandIcon = brandIcon;
        preferences = activity.getSharedPreferences("time_assistant_tutorial", Context.MODE_PRIVATE);
    }

    @Override public void initialize(boolean existingUser) {
        int seenVersion = preferences.getInt("seen_version", 0);
        introPending = preferences.getBoolean("intro_pending", false)
                || TutorialSession.shouldAutoStart(seenVersion, existingUser);
        if (existingUser && seenVersion == 0 && !introPending) {
            preferences.edit().putInt("seen_version", TutorialSession.VERSION).apply();
            android.widget.Toast.makeText(activity, R.string.tutorial_existing_user_hint,
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override public void replay() {
        dismissPresentations();
        preferences.edit().clear().putBoolean("intro_pending", true).apply();
        introPending = true;
        showIntroduction();
    }

    @Override public void onStart() {
        started = true;
        if (introPending) showIntroduction();
        else showGuideIfReady();
    }

    @Override public void onStop() {
        started = false;
        dismissPresentations();
    }

    @Override public void dispose() {
        onStop();
        pendingGuide = null;
    }

    @Override public void requestGuide(GuideTarget target) {
        pendingGuide = target;
        showGuideIfReady();
    }

    private void showIntroduction() {
        if (!started || activity.isFinishing() || activity.isDestroyed()
                || introduction != null) return;
        preferences.edit().putBoolean("intro_pending", true).apply();
        DialogTutorialBinding binding = DialogTutorialBinding.inflate(activity.getLayoutInflater());
        binding.brandIcon.setImageResource(brandIcon);
        introduction = new com.hawson.timeassistant.design.SoftAlertDialogBuilder(activity)
                .setView(binding.getRoot())
                .setNegativeButton(R.string.tutorial_skip, (dialog, which) -> complete(false))
                .setPositiveButton(R.string.tutorial_done, (dialog, which) -> complete(true))
                .create();
        introduction.setOnCancelListener(dialog -> complete(false));
        MotionSpec.configureDialog(introduction);
        introduction.setOnShowListener(dialog -> {
            binding.stepLabel.setVisibility(View.GONE);
            binding.stepTitle.setText("时屿");
            binding.stepBody.setText("说出安排，确认计划，专注当下。");
            androidx.core.view.ViewCompat.setAccessibilityHeading(binding.stepTitle, true);
        });
        introduction.show();
    }


    private void complete(boolean contextEnabled) {
        introPending = false;
        preferences.edit().putInt("seen_version", TutorialSession.VERSION)
                .putBoolean("intro_pending", false)
                .putBoolean("context_enabled", contextEnabled).remove("intro_step").apply();
        if (introduction != null) introduction.dismiss();
        introduction = null;
        pendingGuide = null;
    }

    private void showGuideIfReady() {
        if (!started || introPending || introduction != null || guide != null
                || pendingGuide == null || activity.isFinishing() || activity.isDestroyed()
                || !preferences.getBoolean("context_enabled", false)) return;
        GuideTarget target = pendingGuide;
        if (preferences.getBoolean("guide_" + target.name(), false)) return;
        View anchor = registry.find(target);
        if (anchor == null) return;
        int title;
        int body;
        switch (target) {
            case CREATE_TASK:
                title = R.string.guide_schedule_title;
                body = R.string.guide_schedule_body;
                break;
            case FOCUS_MODES:
                title = R.string.guide_focus_title;
                body = R.string.guide_focus_body;
                break;
            default:
                title = R.string.guide_chat_title;
                body = R.string.guide_chat_body;
        }
        guide = Snackbar.make(anchor, body, Snackbar.LENGTH_LONG)
                .setAction(R.string.guide_dismiss, view -> markGuideSeen(target));
        guide.addCallback(new Snackbar.Callback() {
            @Override public void onDismissed(Snackbar bar, int event) {
                if (event != Snackbar.Callback.DISMISS_EVENT_MANUAL) markGuideSeen(target);
            }
        });
        guide.show();
    }

    private void markGuideSeen(GuideTarget target) {
        preferences.edit().putBoolean("guide_" + target.name(), true).apply();
        guide = null;
    }

    private void dismissPresentations() {
        if (introduction != null) {
            introduction.setOnCancelListener(null);
            introduction.dismiss();
            introduction = null;
        }
        if (guide != null) {
            guide.dismiss();
            guide = null;
        }
    }
}
