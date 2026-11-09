package com.hawson.timeassistant.proactive;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.hawson.timeassistant.data.local.LocalIntegrationSettingsStore;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/** Generation-stamped daily checks, coalesced by minute across both trigger sources. */
public final class ProactiveScheduleCoordinator {
    public static final String INPUT_ROUTINE = "routine";
    public static final String INPUT_SCHEDULED_AT = "scheduled_at";
    public static final String INPUT_MANUAL = "manual";
    public static final String INPUT_GENERATION = "generation";
    public static final String INPUT_MINUTE = "minute";
    private static final String TAG = "proactive_ai";
    private static final long GRACE_MILLIS = 90L * 60_000L;

    private ProactiveScheduleCoordinator() { }

    public static void ensureScheduled(Context context) {
        synchronized (LocalIntegrationSettingsStore.PROACTIVE_LOCK) {
            scheduleAll(context);
        }
    }

    public static void rescheduleAll(Context context) {
        synchronized (LocalIntegrationSettingsStore.PROACTIVE_LOCK) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG);
            scheduleAll(context);
        }
    }

    public static void runMiddayCheckNow(Context context) {
        runCheckNow(context);
    }

    public static void runTodoReviewNow(Context context) { runCheckNow(context); }

    public static void runCheckNow(Context context) {
        synchronized (LocalIntegrationSettingsStore.PROACTIVE_LOCK) {
            ProactiveSettings settings = new LocalIntegrationSettingsStore(context).getProactiveSettings();
            if (!settings.enabled || (!settings.todoReviewEnabled && !settings.attentionTriggerEnabled)) return;
            enqueue(context, settings, -1, System.currentTimeMillis() + 1_000L, true);
        }
    }

    private static void scheduleAll(Context context) {
        ProactiveSettings settings = new LocalIntegrationSettingsStore(context).getProactiveSettings();
        if (!settings.enabled) return;
        long now = System.currentTimeMillis();
        for (int minute : ProactiveSchedulePlan.minutes(settings)) {
            enqueue(context, settings, minute, nextTriggerAt(now, minute, false), false);
        }
    }

    static void scheduleTomorrow(Context context, String generation, int minute, long scheduledAt) {
        synchronized (LocalIntegrationSettingsStore.PROACTIVE_LOCK) {
            ProactiveSettings settings = new LocalIntegrationSettingsStore(context).getProactiveSettings();
            if (!ProactiveSchedulePlan.isCurrent(settings, generation, minute, false)) return;
            Calendar next = Calendar.getInstance();
            next.setTimeInMillis(scheduledAt);
            next.add(Calendar.DAY_OF_YEAR, 1);
            next.set(Calendar.HOUR_OF_DAY, minute / 60);
            next.set(Calendar.MINUTE, minute % 60);
            next.set(Calendar.SECOND, 0);
            next.set(Calendar.MILLISECOND, 0);
            long trigger = next.getTimeInMillis();
            if (trigger <= System.currentTimeMillis()) {
                trigger = nextTriggerAt(System.currentTimeMillis(), minute, false);
            }
            enqueue(context, settings, minute, trigger, false);
        }
    }

    static void deferCheck(Context context, String generation, int minute, boolean manual) {
        synchronized (LocalIntegrationSettingsStore.PROACTIVE_LOCK) {
            ProactiveSettings settings = new LocalIntegrationSettingsStore(context).getProactiveSettings();
            if (!ProactiveSchedulePlan.isCurrent(settings, generation, minute, manual)) return;
            enqueue(context, settings, minute, System.currentTimeMillis() + 30L * 60_000L, manual);
        }
    }

    static long nextTriggerAt(long now, int minuteOfDay, boolean allowGrace) {
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(now);
        target.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        target.set(Calendar.MINUTE, minuteOfDay % 60);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        long value = target.getTimeInMillis();
        if (value < now) {
            if (allowGrace && now - value <= GRACE_MILLIS) return now + 5_000L;
            target.add(Calendar.DAY_OF_YEAR, 1);
            value = target.getTimeInMillis();
        }
        return value;
    }

    private static void enqueue(Context context, ProactiveSettings settings, int minute,
                                long triggerAt, boolean manual) {
        Constraints.Builder constraints = new Constraints.Builder();
        if (settings.backgroundAiEnabled) constraints.setRequiredNetworkType(NetworkType.CONNECTED);
        Data data = new Data.Builder().putInt(INPUT_MINUTE, minute)
                .putString(INPUT_GENERATION, settings.scheduleGeneration)
                .putLong(INPUT_SCHEDULED_AT, triggerAt).putBoolean(INPUT_MANUAL, manual).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ProactiveWorker.class)
                .setInputData(data).setConstraints(constraints.build())
                .setInitialDelay(Math.max(0, triggerAt - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                .addTag(TAG).build();
        String uniqueName = "proactive_" + settings.scheduleGeneration + '_' + minute + '_'
                + (triggerAt / 60_000L);
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request);
    }
}
