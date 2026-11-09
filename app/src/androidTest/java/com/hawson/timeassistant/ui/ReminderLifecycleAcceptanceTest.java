package com.hawson.timeassistant.ui;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.ReminderEvent;
import com.hawson.timeassistant.domain.TaskItem;
import com.hawson.timeassistant.reminder.ReminderReceiver;
import com.hawson.timeassistant.reminder.ReminderScheduler;
import java.util.concurrent.CountDownLatch;
import org.junit.*;
import static org.junit.Assert.*;

/** Destructive lifecycle and display fixtures are restricted to an account-free emulator. */
public class ReminderLifecycleAcceptanceTest {
    private Context context;
    private AppRepository repository;

    @Before public void prepare() throws Exception {
        assertTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"));
        context = ApplicationProvider.getApplicationContext();
        assertEquals("com.hawson.timeassistant.interaction.qa", context.getPackageName());
        TimeAssistantApplication app = (TimeAssistantApplication) context;
        assertTrue(app.getAiServices().profiles.list().isEmpty());
        assertTrue(app.getAiServices().accounts.accounts().isEmpty());
        repository = app.getRepository(); repository.clearAll();
        shell("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS");
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().putInt("seen_version", 1)
                .putBoolean("intro_pending", false).putBoolean("context_enabled", false).commit();
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().putInt("last_page", 1).commit();
    }

    @After public void cleanup() {
        if (repository == null) return;
        context.getSystemService(NotificationManager.class).cancelAll();
        repository.clearAll();
    }

    @Test public void editedDeletedAndCompletedTargetsRejectOldBroadcasts() throws Exception {
        long old = System.currentTimeMillis() - 100;
        TaskItem edited = repository.upsertTask(new TaskItem("edited").setCountdown(old + 60_000, 0));
        TaskItem deleted = repository.upsertTask(new TaskItem("deleted").setCountdown(old, 0));
        repository.deleteTask(deleted.getId());
        TaskItem completed = repository.upsertTask(new TaskItem("completed").setCountdown(old, 0)
                .setStatus(TaskItem.Status.COMPLETED));
        broadcast(edited.getId(), old); broadcast(deleted.getId(), old); broadcast(completed.getId(), old);
        SystemClock.sleep(600);
        assertTrue(repository.getAllReminderEvents().isEmpty());
        assertEquals(old + 60_000, repository.getTask(edited.getId()).getCountdownTargetAt());
    }

    @Test public void simultaneousReceiversPublishOneBusinessEvent() throws Exception {
        long at = System.currentTimeMillis();
        TaskItem task = repository.upsertTask(new TaskItem("race").setCountdown(at, 0));
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable deliver = () -> {
            try {
                start.await();
                ((TimeAssistantApplication) context).getReminderCoordinator().receive(task.getId(), "countdown", at);
            } catch (Throwable error) { failure.compareAndSet(null, error); }
        };
        Thread one = new Thread(deliver), two = new Thread(deliver);
        one.start(); two.start(); start.countDown(); one.join(5_000); two.join(5_000);
        assertFalse(one.isAlive()); assertFalse(two.isAlive()); assertNull(failure.get());
        assertEquals(1, repository.getAllReminderEvents().size());
        assertFalse(repository.getTask(task.getId()).hasCountdown());
    }

    @Test public void backgroundAlarmNotificationOpensTaskAndDeletedTargetFallsBackSafely() throws Exception {
        TaskItem task;
        android.app.PendingIntent open = null;
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            WorkflowDriver.idle();
            task = repository.upsertTask(new TaskItem("notification-open-fixture")
                    .setCountdown(System.currentTimeMillis() + 1_800, 0));
            screen.onActivity(a -> a.moveTaskToBack(true));
            ReminderEvent event = awaitEvent();
            for (android.service.notification.StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications())
                if (event.getId().equals(item.getTag())) open = item.getNotification().contentIntent;
            assertNotNull(open);
        }
        try {
            open.send();
            WorkflowDriver.awaitCondition("notification task destination", () -> {
                MainActivity activity = resumedActivity();
                return activity != null && activity.currentPage == 1 && WorkflowDriver.text(task.getTitle()) != null;
            });
            repository.deleteTask(task.getId()); open.send(); SystemClock.sleep(700); WorkflowDriver.idle();
            WorkflowDriver.awaitCondition("deleted target fallback", () -> {
                MainActivity activity = resumedActivity();
                return activity != null && activity.currentPage == 1;
            });
            assertNull(repository.getTask(task.getId()));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                MainActivity activity = resumedActivity();
                if (activity != null) activity.finish();
            });
        }
    }

    private MainActivity resumedActivity() {
        for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
            if (activity instanceof MainActivity) return (MainActivity) activity;
        return null;
    }

    @Test public void realAlarmDeliversWhileDisplayIsOffWithoutCompletingTask() throws Exception {
        TaskItem task = repository.upsertTask(new TaskItem("screen-off-reminder")
                .setCountdown(System.currentTimeMillis() + 2_000, 0));
        try {
            shell("input keyevent 223"); SystemClock.sleep(300);
            assertFalse(context.getSystemService(PowerManager.class).isInteractive());
            assertEquals("posted", awaitEvent().getDeliveryState());
            assertFalse(repository.getTask(task.getId()).getStatus().isTerminal());
            assertEquals(1, repository.getAllReminderEvents().size());
        } finally { shell("input keyevent 224"); }
    }

    private ReminderEvent awaitEvent() {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (ReminderEvent event : repository.getAllReminderEvents())
                if (!"pending".equals(event.getDeliveryState())) return event;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Reminder delivery timed out");
    }

    private void broadcast(String id, long target) {
        context.sendBroadcast(new Intent(context, ReminderReceiver.class)
                .setAction(ReminderScheduler.ACTION_TASK_REMINDER)
                .putExtra(ReminderScheduler.EXTRA_TASK_ID, id)
                .putExtra(ReminderScheduler.EXTRA_REMINDER_KIND, "countdown")
                .putExtra(ReminderScheduler.EXTRA_TRIGGER_AT, target));
    }

    static void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024]; while (input.read(buffer) >= 0) { }
        }
    }
}
