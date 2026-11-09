package com.hawson.timeassistant.settings;

import android.content.Context;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import com.hawson.timeassistant.DeviceFixtureSafety;
import com.hawson.timeassistant.connections.*;
import com.hawson.timeassistant.testing.AiSettingsHarnessActivity;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Injected ports only: callbacks must not discard another connection's unsaved draft. */
public class AiSettingsDraftIsolationTest {
    @Test public void accountCompletionKeepsUnsavedDraftAndSecret() {
        verifyDraft(true);
    }

    @Test public void backgroundConnectionNoticeKeepsDraftStatus() {
        verifyDraft(false);
    }

    private void verifyDraft(boolean accountCallback) {
        Context context = ApplicationProvider.getApplicationContext();
        DeviceFixtureSafety.requireIsolatedTarget(context);
        FixtureSettings settings = new FixtureSettings();
        AiSettingsViewModel[] model = {null};
        try (ActivityScenario<AiSettingsHarnessActivity> scenario =
                     ActivityScenario.launch(AiSettingsHarnessActivity.class)) {
            scenario.onActivity(activity -> {
                AiSettingsViewModel vm = new AiSettingsViewModel(new FixtureProfiles(), new FixtureAccounts(), settings);
                model[0] = vm;
                vm.edit(new AiProfile("fixture", "Fixture relay", AiProfile.Kind.RELAY,
                        AiProfile.Protocol.RESPONSES, "https://example.invalid/v1", "fixture-model", ""));
                vm.name = "Unsaved relay draft";
                vm.key = "fixture-only-secret";
                vm.changed();
                AiConnectionsView view = new AiConnectionsView(activity, activity, vm, new AiConnectionsView.Host() {
                    public void beforeChange(Runnable action) { action.run(); }
                    public void openBrowser(String url) { fail("No real browser in fixture test"); }
                });
                activity.setContentView(view);
            });
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                AiSettingsViewModel vm = model[0];
                if (accountCallback) vm.signedIn.setValue(new AiAccountPort.Account("fixture-account", "Fixture", false));
                else {
                    assertNotNull("Connection listener must be attached", settings.listener);
                    settings.listener.run();
                }
                assertNotNull("Background account changes must not leave the editor", vm.editing);
                assertTrue("Draft must remain dirty", vm.dirty);
                assertEquals("fixture-only-secret", vm.key);
                assertEquals("Unsaved relay draft", ((EditText) activity.findViewById(
                        com.hawson.timeassistant.settingsfeature.R.id.name)).getText().toString());
                assertEquals("fixture-only-secret", ((EditText) activity.findViewById(
                        com.hawson.timeassistant.settingsfeature.R.id.key)).getText().toString());
                if (!accountCallback) assertEquals("有未保存的修改", vm.notice.getValue());
            });
        } finally {
            if (model[0] != null) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .runOnMainSync(model[0]::onCleared);
        }
    }

    private static final class FixtureSettings implements AiSettingsPort {
        Runnable listener;
        public String test(AiProfile draft, String key, AiCancellation cancel) { throw new AssertionError("No requests"); }
        public List<String> models(AiProfile draft, String key, AiCancellation cancel) { throw new AssertionError("No requests"); }
        public void requireAllowed(String url) { }
        public void approvePrivateHttp(String url) { }
        public void revokePrivateHttp() { }
        public String connectionNotice() { return "Fixture connection ready"; }
        public void addConnectionListener(Runnable value) { listener = value; }
        public void removeConnectionListener(Runnable value) { if (listener == value) listener = null; }
    }

    private static final class FixtureProfiles implements AiProfilesPort {
        public List<AiProfile> list() { return Collections.emptyList(); }
        public AiProfile active() { return null; }
        public AiProfile save(AiProfile profile, String key) { throw new AssertionError("No writes"); }
        public void activate(String id) { throw new AssertionError("No writes"); }
        public void delete(String id) { throw new AssertionError("No writes"); }
        public AiProfile duplicate(String id) { throw new AssertionError("No writes"); }
        public String contextBoundary() { return "fixture"; }
        public long contextStartedAt() { return 0; }
    }

    private static final class FixtureAccounts implements AiAccountPort {
        public boolean available() { return true; }
        public List<Account> accounts() { return Collections.emptyList(); }
        public String beginSignIn(String reference, Listener listener) { throw new AssertionError("No real login"); }
        public boolean hasPendingSignIn() { return false; }
        public void completePendingSignIn(String uri, Listener listener) { throw new AssertionError("No real login"); }
        public void cancelSignIn() { }
        public boolean signOut(String reference) { throw new AssertionError("No real logout"); }
    }
}
