package com.hawson.timeassistant.ui;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hawson.timeassistant.domain.ReminderEvent;
import com.hawson.timeassistant.reminder.ReminderNotifications;
import org.junit.Test;
import static org.junit.Assert.*;

/** One non-persisted test notification; never creates tasks or changes permissions. */
public class ReminderPhoneDeliveryTest {
    @Test public void submitOneNotificationWithoutChangingUserChannelSettings() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("notificationProbe"));
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals("com.hawson.timeassistant.interaction.qa", context.getPackageName());
        WorkflowDriver.requireUnlockedDisplay(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        assertTrue("System notification permission must already be enabled", manager.areNotificationsEnabled());
        NotificationChannel before = Build.VERSION.SDK_INT >= 26
                ? manager.getNotificationChannel(ReminderNotifications.CHANNEL_ID) : null;
        ReminderEvent event = ReminderEvent.triggered(ReminderEvent.TargetType.REVIEW,
                "acceptance-notification", "test", "时屿验收测试提醒", System.currentTimeMillis());
        try {
            assertEquals("posted", ReminderNotifications.post(context, event));
            SystemClock.sleep(500);
            boolean visible = false;
            for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications())
                if (event.getId().equals(notification.getTag())) visible = true;
            assertTrue("The submitted notification must be active in NotificationManager", visible);
            Bundle evidence = new Bundle();
            evidence.putBoolean("notification_active", visible);
            evidence.putInt("interruption_filter", manager.getCurrentInterruptionFilter());
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel after = manager.getNotificationChannel(ReminderNotifications.CHANNEL_ID);
                evidence.putInt("channel_importance", after.getImportance());
                evidence.putBoolean("channel_sound_configured", after.getSound() != null);
                evidence.putBoolean("channel_vibration_configured", after.shouldVibrate());
                if (before != null) {
                    assertEquals(before.getImportance(), after.getImportance());
                    assertEquals(before.getSound(), after.getSound());
                    assertEquals(before.shouldVibrate(), after.shouldVibrate());
                }
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence);
            SystemClock.sleep(20_000);
        } finally { manager.cancel(event.getId(), 0); }
    }
}
