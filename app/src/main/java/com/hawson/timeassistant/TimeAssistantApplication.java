package com.hawson.timeassistant;

import android.app.Application;

import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.data.FocusSessionGateway;
import com.hawson.timeassistant.data.ReminderStateGateway;
import com.hawson.timeassistant.data.SyncSnapshotGateway;
import com.hawson.timeassistant.data.core.TimeAssistantCoreDatabase;
import com.hawson.timeassistant.onboarding.TutorialCoordinator;
import com.hawson.timeassistant.design.AppearancePreferences;
import androidx.appcompat.app.AppCompatDelegate;

/** Application-level composition root for long-lived repository and persistence dependencies. */
public final class TimeAssistantApplication extends Application {
    private AppRepository repository;
    private FocusSessionGateway focusSessionGateway;
    private ReminderStateGateway reminderStateGateway;
    private SyncSnapshotGateway syncSnapshotGateway;
    private AppGraph appGraph;
    private AiServices aiServices;
    private com.hawson.timeassistant.reminder.ReminderCoordinator reminderCoordinator;

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(new AppearancePreferences(this).nightMode());
        repository = AppRepository.get(this);
        focusSessionGateway = new FocusSessionGateway(repository);
        reminderStateGateway = new ReminderStateGateway(repository);
        reminderCoordinator = new com.hawson.timeassistant.reminder.ReminderCoordinator(this, repository);
        syncSnapshotGateway = new SyncSnapshotGateway(repository);
        com.hawson.timeassistant.domain.AppSettings legacySettings = repository.getSettings();
        aiServices = new AiServices(this, legacySettings.getBaseUrl(), legacySettings.getModel(),
                legacySettings.getApiKey());
        if (aiServices.migrationError == null && !legacySettings.getApiKey().isEmpty()) {
            legacySettings.setApiKey("");
            repository.saveSettings(legacySettings);
        }
        appGraph = new AppGraph(repository.observeState(), TutorialCoordinator::new, aiServices);
    }

    public AppRepository getRepository() {
        return repository;
    }

    public TimeAssistantCoreDatabase getCoreDatabase() {
        return repository.getCoreDatabase();
    }

    public FocusSessionGateway getFocusSessionGateway() { return focusSessionGateway; }
    public ReminderStateGateway getReminderStateGateway() { return reminderStateGateway; }
    public SyncSnapshotGateway getSyncSnapshotGateway() { return syncSnapshotGateway; }
    public AppGraph getAppGraph() { return appGraph; }
    public AiServices getAiServices() { return aiServices; }
    public com.hawson.timeassistant.reminder.ReminderCoordinator getReminderCoordinator() { return reminderCoordinator; }
}
