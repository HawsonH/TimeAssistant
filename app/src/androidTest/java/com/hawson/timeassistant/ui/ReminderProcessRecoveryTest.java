package com.hawson.timeassistant.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.TaskItem;
import org.junit.Test;
import static org.junit.Assert.*;

/** Two instrumentation processes with an explicit force-stop between phases. */
public class ReminderProcessRecoveryTest {
    @Test public void persistedCountdownRecoversAfterExplicitStopAndRestart() throws Exception {
        assertTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"));
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals("com.hawson.timeassistant.interaction.qa", context.getPackageName());
        TimeAssistantApplication app = (TimeAssistantApplication) context;
        assertTrue(app.getAiServices().accounts.accounts().isEmpty());
        assertTrue(app.getAiServices().profiles.list().isEmpty());
        AppRepository repository = app.getRepository();
        SharedPreferences fixture = context.getSharedPreferences("reminder_recovery_fixture", 0);
        String phase = InstrumentationRegistry.getArguments().getString("reminderPhase", "");
        assertTrue("Explicit seed/verify phase required", phase.equals("seed") || phase.equals("verify"));
        if (phase.equals("seed")) {
            repository.clearAll();
            long at = System.currentTimeMillis() + 12_000;
            TaskItem task = repository.upsertTask(new TaskItem("process-recovery-reminder").setCountdown(at, 0));
            assertTrue(fixture.edit().putString("task", task.getId()).putLong("target", at).commit());
            return;
        }
        try {
            String id = fixture.getString("task", ""); assertFalse(id.isEmpty());
            assertNotNull(repository.getTask(id));
            long deadline = SystemClock.elapsedRealtime() + 15_000;
            while (repository.getAllReminderEvents().isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
            assertEquals(1, repository.getAllReminderEvents().size());
            assertEquals(fixture.getLong("target", -1), repository.getAllReminderEvents().get(0).getTriggerAt());
            assertFalse(repository.getTask(id).hasCountdown());
            assertFalse(repository.getTask(id).getStatus().isTerminal());
            app.getReminderCoordinator().receive(id, "countdown", fixture.getLong("target", -1));
            assertEquals(1, repository.getAllReminderEvents().size());
        } finally { repository.clearAll(); fixture.edit().clear().commit(); }
    }
}
