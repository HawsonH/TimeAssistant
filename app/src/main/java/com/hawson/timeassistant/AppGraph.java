package com.hawson.timeassistant;

import android.app.Activity;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.hawson.timeassistant.data.AppState;
import com.hawson.timeassistant.navigation.GuideRegistry;
import com.hawson.timeassistant.navigation.TutorialFactory;
import com.hawson.timeassistant.navigation.TutorialFeature;
import com.hawson.timeassistant.ui.ChatPageViewModel;
import com.hawson.timeassistant.ui.FocusPageViewModel;
import com.hawson.timeassistant.ui.SchedulePageViewModel;
import com.hawson.timeassistant.ui.SettingsPageViewModel;
import com.hawson.timeassistant.settings.AiConnectionsView;
import com.hawson.timeassistant.settings.AiSettingsViewModel;
import androidx.fragment.app.FragmentActivity;

/** Composition only. Features receive dependencies rather than fetching application singletons. */
public final class AppGraph {
    private final LiveData<AppState> state;
    private final TutorialFactory tutorials;
    public final AiServices ai;

    public AppGraph(LiveData<AppState> state, TutorialFactory tutorials, AiServices ai) {
        this.state = state;
        this.tutorials = tutorials;
        this.ai = ai;
    }

    public TutorialFeature tutorial(Activity activity, GuideRegistry registry, int brandIcon) {
        return tutorials.create(activity, registry, brandIcon);
    }
    public AiConnectionsView aiSettings(FragmentActivity owner, AiConnectionsView.Host host) {
        ViewModelProvider.Factory factory = new ViewModelProvider.Factory() {
            @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) {
                return type.cast(new AiSettingsViewModel(ai.profiles, ai.accounts, ai));
            }
        };
        return new AiConnectionsView(owner, owner,
                new ViewModelProvider(owner, factory).get(AiSettingsViewModel.class), host);
    }

    public ViewModelProvider.Factory pageViewModels() {
        return new ViewModelProvider.Factory() {
            @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) {
                ViewModel result;
                if (type == ChatPageViewModel.class) result = new ChatPageViewModel(state);
                else if (type == SchedulePageViewModel.class) result = new SchedulePageViewModel(state);
                else if (type == FocusPageViewModel.class) result = new FocusPageViewModel(state);
                else if (type == SettingsPageViewModel.class) result = new SettingsPageViewModel(state);
                else throw new IllegalArgumentException("Unknown page ViewModel: " + type.getName());
                return type.cast(result);
            }
        };
    }
}
