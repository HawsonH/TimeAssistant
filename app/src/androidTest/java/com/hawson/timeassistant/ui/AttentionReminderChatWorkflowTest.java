package com.hawson.timeassistant.ui;

import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.TimeAssistantApplication;
import com.hawson.timeassistant.data.AppRepository;
import com.hawson.timeassistant.domain.ReminderEvent;
import com.hawson.timeassistant.domain.TaskItem;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** All business fixtures are emulator-only; never execute this class on the phone. */
@RunWith(AndroidJUnit4.class)
public class AttentionReminderChatWorkflowTest {
    private Context context;
    private AppRepository repository;

    @Before public void prepare() {
        assertTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"));
        context = ApplicationProvider.getApplicationContext();
        assertEquals("com.hawson.timeassistant.interaction.qa", context.getPackageName());
        TimeAssistantApplication app = (TimeAssistantApplication) context;
        assertTrue(app.getAiServices().profiles.list().isEmpty()); assertTrue(app.getAiServices().accounts.accounts().isEmpty());
        repository = app.getRepository(); repository.clearAll();
        context.getSharedPreferences("time_assistant_tutorial", 0).edit().putInt("seen_version", 1)
                .putBoolean("intro_pending", false).putBoolean("context_enabled", false).commit();
        context.getSharedPreferences(AppTheme.PREFERENCES_NAME, 0).edit().putInt("last_page", 0).commit();
    }
    @After public void cleanup() { if (repository != null) repository.clearAll(); }

    @Test public void legacyFocusEnvelopeUsesBackgroundGatewayWithoutCreatingRecords() {
        context.sendBroadcast(new android.content.Intent(context, com.hawson.timeassistant.reminder.ReminderReceiver.class)
                .setAction(com.hawson.timeassistant.reminder.ReminderScheduler.ACTION_TASK_REMINDER)
                .putExtra(com.hawson.timeassistant.reminder.ReminderScheduler.EXTRA_REMINDER_KIND, "focus"));
        SystemClock.sleep(700);
        assertTrue(repository.getFocusHistory().isEmpty());
        assertTrue(repository.getAllReminderEvents().isEmpty());
    }

    @Test public void legacyRemindersAreNotRepostedButNewPendingDeliverySurvivesRestart() throws Exception {
        ReminderEvent event = ReminderEvent.triggered(ReminderEvent.TargetType.TASK, "id", "countdown", "title", 100);
        assertEquals("pending", ReminderEvent.fromJson(event.toJson()).getDeliveryState());
        org.json.JSONObject old = event.toJson(); old.remove("deliveryState"); old.remove("presentedAt");
        ReminderEvent restored = ReminderEvent.fromJson(old);
        assertEquals("posted", restored.getDeliveryState()); assertTrue(restored.getPresentedAt() > 0);
        try { ReminderEvent.fromJson(event.toJson().put("deliveryState", "unknown")); fail(); }
        catch (org.json.JSONException expected) { }
    }

    @Test public void attentionCheckboxRetainsRowAndRotationRetainsUndoWithoutRebuildingPage() {
        TaskItem task = repository.upsertTask(new TaskItem("attention-fixture").setStatus(TaskItem.Status.NEEDS_RESCHEDULING));
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> { a.showPage(1); a.scheduleMode = 2; a.renderCurrentPage(); }); idle();
            View[] root = {null};
            screen.onActivity(a -> {
                root[0] = a.pageContainer.getChildAt(0);
                View check = description(a.pageContainer, "完成：attention-fixture"); assertNotNull(check); check.performClick();
                assertEquals(TaskItem.Status.COMPLETED, repository.getTask(task.getId()).getStatus());
            }); idle();
            screen.onActivity(a -> {
                assertSame(root[0], a.pageContainer.getChildAt(0));
                CheckBox check = (CheckBox) description(a.pageContainer, "撤销完成：attention-fixture");
                assertNotNull(check); assertTrue(check.isChecked());
            });
            screen.recreate(); idle();
            screen.onActivity(a -> {
                View check = description(a.pageContainer, "撤销完成：attention-fixture"); assertNotNull(check); check.performClick();
            }); idle();
            assertEquals(TaskItem.Status.NEEDS_RESCHEDULING, repository.getTask(task.getId()).getStatus());
            screen.onActivity(a -> assertNotNull(description(a.pageContainer, "完成：attention-fixture")));
        }
    }

    @Test public void attentionRowLeavesLocallyAfterFiveSecondsAndCommitSurvivesLeaving() {
        TaskItem task = repository.upsertTask(new TaskItem("expire-fixture").setStatus(TaskItem.Status.NEEDS_RESCHEDULING));
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> { a.showPage(1); a.scheduleMode = 2; a.renderCurrentPage(); }); idle();
            View[] root = {null};
            screen.onActivity(a -> { root[0] = a.pageContainer.getChildAt(0);
                description(a.pageContainer, "完成：expire-fixture").performClick(); }); idle();
            SystemClock.sleep(5_400); idle();
            screen.onActivity(a -> {
                assertSame(root[0], a.pageContainer.getChildAt(0));
                assertNull(description(a.pageContainer, "撤销完成：expire-fixture"));
                a.showPage(0);
            });
            assertEquals(TaskItem.Status.COMPLETED, repository.getTask(task.getId()).getStatus());
        }
    }

    @Test public void attachmentSendClearsImmediatelyAndDoesNotEraseTheNextDraft() throws Exception {
        File file = new File(context.getCacheDir(), "composer-fixture.txt");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write("fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        CountDownLatch unblock = new CountDownLatch(1);
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            idle();
            screen.onActivity(a -> {
                ExecutorService executor = (ExecutorService) field(a, "attachmentExecutor");
                executor.execute(() -> { try { unblock.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } });
                attachments(a).add(new PendingAttachment(Uri.fromFile(file), file.getName(), "text/plain", file.length()));
                a.chatInput.setText("first draft"); invoke(a, "sendCurrentInput");
                assertEquals("", a.chatInput.getText().toString()); assertTrue(attachments(a).isEmpty());
                a.chatInput.setText("next draft");
            });
            unblock.countDown(); SystemClock.sleep(1_100); idle();
            assertEquals(1, repository.getChatMessages().stream().filter(m -> m.getContent().equals("first draft")).count());
            screen.onActivity(a -> assertEquals("next draft", field(a, "chatDraft")));
        } finally { unblock.countDown(); assertTrue(file.delete()); }
    }

    @Test public void failedPreparationKeepsNewInputAndOldSubmissionCanBeRecovered() throws Exception {
        CountDownLatch unblock = new CountDownLatch(1);
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            idle();
            screen.onActivity(a -> {
                ((ExecutorService) field(a, "attachmentExecutor")).execute(() -> {
                    try { unblock.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                });
                attachments(a).add(new PendingAttachment(Uri.fromFile(new File(context.getCacheDir(), "does-not-exist")), "missing.txt", "text/plain", 1));
                a.chatInput.setText("original"); invoke(a, "sendCurrentInput"); a.chatInput.setText("keep this");
            });
            unblock.countDown(); SystemClock.sleep(700); idle();
            screen.onActivity(a -> {
                assertEquals("keep this", a.chatInput.getText().toString());
                ChatPageViewModel model = (ChatPageViewModel) field(a, "chatPageViewModel");
                assertNotNull(model.recoverableSubmission); assertEquals("original", model.recoverableSubmission.text);
                assertEquals(1, model.recoverableSubmission.attachments.size());
            });
            assertTrue(repository.getChatMessages().isEmpty());
        } finally { unblock.countDown(); }
    }

    @Test public void failedPreparationRestoresUnchangedDraftAndAttachments() throws Exception {
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            idle();
            screen.onActivity(a -> {
                attachments(a).add(new PendingAttachment(Uri.fromFile(new File(context.getCacheDir(), "missing-unchanged")), "missing.txt", "text/plain", 1));
                a.chatInput.setText("restore original"); invoke(a, "sendCurrentInput");
                assertEquals("", a.chatInput.getText().toString());
            });
            SystemClock.sleep(700); idle();
            screen.onActivity(a -> {
                assertEquals("restore original", a.chatInput.getText().toString());
                assertEquals(1, attachments(a).size());
                assertNull(((ChatPageViewModel) field(a, "chatPageViewModel")).preparingSubmission);
            });
            assertTrue(repository.getChatMessages().isEmpty());
        }
    }

    @Test public void cancelDuringPreparationRejectsLateCallbackAndRetainsNewDraft() throws Exception {
        File file = new File(context.getCacheDir(), "cancel-fixture.txt");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(65); }
        CountDownLatch unblock = new CountDownLatch(1);
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            idle();
            screen.onActivity(a -> {
                ((ExecutorService) field(a, "attachmentExecutor")).execute(() -> {
                    try { unblock.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                });
                attachments(a).add(new PendingAttachment(Uri.fromFile(file), file.getName(), "text/plain", file.length()));
                a.chatInput.setText("cancel this"); invoke(a, "sendCurrentInput");
                a.chatInput.setText("keep new"); invoke(a, "cancelPreparingSubmission");
            });
            unblock.countDown(); SystemClock.sleep(700); idle();
            screen.onActivity(a -> {
                assertEquals("keep new", a.chatInput.getText().toString());
                ChatPageViewModel model = (ChatPageViewModel) field(a, "chatPageViewModel");
                assertNull(model.preparingSubmission); assertNotNull(model.recoverableSubmission);
            });
            assertTrue(repository.getChatMessages().isEmpty());
        } finally { unblock.countDown(); assertTrue(file.delete()); }
    }

    @Test public void rotationDuringPreparationRestoresDraftWithoutResending() throws Exception {
        File file = new File(context.getCacheDir(), "rotate-composer-fixture.txt");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(65); }
        CountDownLatch unblock = new CountDownLatch(1);
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            idle();
            screen.onActivity(a -> {
                ((ExecutorService) field(a, "attachmentExecutor")).execute(() -> {
                    try { unblock.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                });
                attachments(a).add(new PendingAttachment(Uri.fromFile(file), file.getName(), "text/plain", file.length()));
                a.chatInput.setText("rotate safely"); invoke(a, "sendCurrentInput");
            });
            screen.recreate(); unblock.countDown(); idle();
            screen.onActivity(a -> {
                assertEquals("rotate safely", a.chatInput.getText().toString());
                assertEquals(1, attachments(a).size());
                assertNull(((ChatPageViewModel) field(a, "chatPageViewModel")).preparingSubmission);
            });
            SystemClock.sleep(400); assertTrue(repository.getChatMessages().isEmpty());
        } finally { unblock.countDown(); assertTrue(file.delete()); }
    }

    @Test public void realAlarmPostsNotificationAndPersistsOnlyOneEvent() throws Exception {
        shell("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS");
        TaskItem task = repository.upsertTask(new TaskItem("alarm-fixture").setRequiresTimeArrangement(false)
                .setCountdown(System.currentTimeMillis() + 2_000, 0));
        long timeout = SystemClock.elapsedRealtime() + 10_000;
        while (repository.getAllReminderEvents().isEmpty() && SystemClock.elapsedRealtime() < timeout) SystemClock.sleep(100);
        assertEquals(1, repository.getAllReminderEvents().size());
        SystemClock.sleep(400);
        ReminderEvent event = repository.getAllReminderEvents().get(0);
        assertEquals("posted", event.getDeliveryState()); assertFalse(repository.getTask(task.getId()).hasCountdown());
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        boolean posted = false;
        for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications())
            if (event.getId().equals(item.getTag())) posted = true;
        assertTrue(posted);
        ((TimeAssistantApplication) context).getReminderCoordinator().receive(task.getId(), "countdown", event.getTriggerAt());
        assertEquals(1, repository.getAllReminderEvents().size());
        manager.cancel(event.getId(), 0);
    }

    @Test public void foregroundReminderAndDiagnosticsRespectSystemPermission() throws Exception {
        boolean blocked = "true".equals(InstrumentationRegistry.getArguments().getString("notificationBlocked"));
        if (!blocked) shell("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS");
        assertEquals(!blocked, context.getSystemService(NotificationManager.class).areNotificationsEnabled());
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            idle();
            TaskItem task = repository.upsertTask(new TaskItem("blocked-reminder-fixture").setRequiresTimeArrangement(false)
                    .setCountdown(System.currentTimeMillis() + 1_500, 0));
            long timeout = SystemClock.elapsedRealtime() + 10_000;
            while (repository.getAllReminderEvents().isEmpty() && SystemClock.elapsedRealtime() < timeout) SystemClock.sleep(100);
            SystemClock.sleep(700); idle();
            assertEquals(1, repository.getAllReminderEvents().size());
            assertEquals(blocked ? "blocked" : "posted", repository.getAllReminderEvents().get(0).getDeliveryState());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("知道了"))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("知道了"))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            SystemClock.sleep(300);
            assertTrue(repository.getAllReminderEvents().get(0).getPresentedAt() > 0);
            screen.onActivity(a -> com.hawson.timeassistant.reminder.ReminderDiagnostics.show(a, () -> fail("No automatic permission request")));
            idle();
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("任务提醒诊断"))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            assertFalse(repository.getTask(task.getId()).hasCountdown());
        }
    }

    private static void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(350); }
    private static Object field(Object target, String name) {
        try { Field field = TimeAssistantActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    @SuppressWarnings("unchecked") private static List<PendingAttachment> attachments(MainActivity a) {
        return (List<PendingAttachment>) field(a, "pendingAttachments");
    }
    private static void invoke(Object target, String name) {
        try { Method method = TimeAssistantActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(target); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static View description(View view, String value) {
        if (value.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = description(((ViewGroup) view).getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
    private static void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024]; while (input.read(buffer) >= 0) { }
        }
    }
}
