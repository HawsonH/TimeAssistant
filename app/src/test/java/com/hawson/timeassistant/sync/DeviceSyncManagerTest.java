package com.hawson.timeassistant.sync;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DeviceSyncManagerTest {
    @Test
    public void pairingCodeAlwaysHasSixDigits() {
        for (int i = 0; i < 250; i++) {
            assertTrue(DeviceSyncManager.generatePairingCode().matches("\\d{6}"));
        }
    }

    @Test
    public void mergeUsesLatestEntitiesAndReturnsCanonicalJson() throws Exception {
        String local = "{"
                + "\"tasks\":["
                + "{\"id\":\"b\",\"createdAt\":5,\"title\":\"B\"},"
                + "{\"id\":\"a\",\"createdAt\":1,\"updatedAt\":10,\"title\":\"old\"}],"
                + "\"chats\":[{\"id\":\"m\",\"createdAt\":3,\"body\":\"local\"}],"
                + "\"focusHistory\":[{\"id\":\"f\",\"createdAt\":2,\"minutes\":20}],"
                + "\"currentFocus\":{\"id\":\"run\",\"updatedAt\":20,\"remaining\":100},"
                + "\"settings\":{"
                + "\"focusMinutes\":{\"value\":25,\"updatedAt\":10},"
                + "\"apiKey\":\"local-secret\","
                + "\"nested\":{\"api_key\":\"nested-secret\",\"value\":\"kept\"}}"
                + "}";
        String remote = "{"
                + "\"settings\":{"
                + "\"focusMinutes\":{\"value\":30,\"updatedAt\":11},"
                + "\"model\":{\"value\":\"desktop-model\",\"updatedAt\":8},"
                + "\"API-Key\":\"remote-secret\"},"
                + "\"currentFocus\":{\"id\":\"run\",\"updatedAt\":21,\"remaining\":90},"
                + "\"focusHistory\":["
                + "{\"id\":\"f\",\"createdAt\":1,\"minutes\":10},"
                + "{\"id\":\"g\",\"createdAt\":4,\"minutes\":25}],"
                + "\"chats\":[{\"id\":\"m\",\"createdAt\":4,\"body\":\"remote\"}],"
                + "\"tasks\":["
                + "{\"id\":\"a\",\"createdAt\":1,\"updatedAt\":11,\"title\":\"new\"},"
                + "{\"id\":\"c\",\"createdAt\":2,\"title\":\"C\"}]"
                + "}";

        String merged = DeviceSyncManager.mergeSnapshots(local, remote);

        assertEquals(merged, DeviceSyncManager.mergeSnapshots(remote, local));
        assertTrue(merged.indexOf("\"id\":\"a\"") < merged.indexOf("\"id\":\"b\""));
        assertTrue(merged.indexOf("\"id\":\"b\"") < merged.indexOf("\"id\":\"c\""));
        assertTrue(merged.contains(
                "\"currentFocus\":{\"id\":\"run\",\"remaining\":90,\"updatedAt\":21}"
        ));
        assertTrue(merged.contains(
                "{\"createdAt\":1,\"id\":\"a\",\"title\":\"new\",\"updatedAt\":11}"
        ));
        assertTrue(merged.contains(
                "\"chats\":[{\"body\":\"remote\",\"createdAt\":4,\"id\":\"m\"}]"
        ));
        assertTrue(merged.contains(
                "\"focusMinutes\":{\"updatedAt\":11,\"value\":30}"
        ));
        assertFalse(merged.contains("local-secret"));
        assertFalse(merged.contains("remote-secret"));
        assertFalse(merged.contains("nested-secret"));
        assertFalse(merged.toLowerCase().contains("apikey"));
        assertTrue(merged.contains("\"nested\":{\"value\":\"kept\"}"));
    }

    @Test
    public void mergeFallsBackToCreatedAtAndStableTieBreak() throws Exception {
        String first = "{"
                + "\"tasks\":[{\"id\":\"same\",\"createdAt\":8,\"title\":\"older\"}],"
                + "\"chats\":[],\"focusHistory\":[],\"currentFocus\":null,\"settings\":{}"
                + "}";
        String second = "{"
                + "\"tasks\":[{\"id\":\"same\",\"createdAt\":9,\"title\":\"newer\"}],"
                + "\"chats\":[],\"focusHistory\":[],\"currentFocus\":null,\"settings\":{}"
                + "}";

        String merged = DeviceSyncManager.mergeSnapshots(first, second);

        assertTrue(merged.contains("\"title\":\"newer\""));
        assertFalse(merged.contains("\"title\":\"older\""));
        assertEquals(merged, DeviceSyncManager.mergeSnapshots(second, first));
    }

    @Test
    public void taskTombstoneMergeIsCommutativeAndCanonical() throws Exception {
        String first = "{"
                + "\"tasks\":[],"
                + "\"taskTombstones\":["
                + "{\"id\":\"z-task\",\"deletedAt\":5,\"reason\":\"old\"},"
                + "{\"id\":\"a-task\",\"deletedAt\":4}],"
                + "\"chats\":[],\"focusHistory\":[],\"currentFocus\":null,\"settings\":{}"
                + "}";
        String second = "{"
                + "\"tasks\":[],"
                + "\"taskTombstones\":["
                + "{\"id\":\"z-task\",\"deletedAt\":5,\"updatedAt\":6,\"reason\":\"new\"}],"
                + "\"chats\":[],\"focusHistory\":[],\"currentFocus\":null,\"settings\":{}"
                + "}";

        String merged = DeviceSyncManager.mergeSnapshots(first, second);

        assertEquals(merged, DeviceSyncManager.mergeSnapshots(second, first));
        assertTrue(merged.contains(
                "\"taskTombstones\":["
                        + "{\"deletedAt\":4,\"id\":\"a-task\"},"
                        + "{\"deletedAt\":5,\"id\":\"z-task\",\"reason\":\"new\",\"updatedAt\":6}]"
        ));
    }

    @Test
    public void taskTombstoneWinsAnEqualVersionTask() throws Exception {
        String taskSide = "{"
                + "\"tasks\":[{\"id\":\"victim\",\"createdAt\":1,\"updatedAt\":10,"
                + "\"title\":\"stale\"}],"
                + "\"chats\":[],\"focusHistory\":[],\"currentFocus\":null,\"settings\":{}"
                + "}";
        String deletionSide = "{"
                + "\"tasks\":[],"
                + "\"taskTombstones\":[{\"id\":\"victim\",\"deletedAt\":10}],"
                + "\"chats\":[],\"focusHistory\":[],\"currentFocus\":null,\"settings\":{}"
                + "}";

        String merged = DeviceSyncManager.mergeSnapshots(taskSide, deletionSide);

        assertEquals(merged, DeviceSyncManager.mergeSnapshots(deletionSide, taskSide));
        assertTrue(merged.contains(
                "\"taskTombstones\":[{\"deletedAt\":10,\"id\":\"victim\"}]"
        ));
        assertTrue(merged.contains("\"tasks\":[]"));
        assertFalse(merged.contains("\"title\":\"stale\""));
    }

    @Test
    public void strictlyNewerTaskExplicitlyResurrectsAndRemovesTombstone() throws Exception {
        String deletionSide = "{"
                + "\"tasks\":[],"
                + "\"taskTombstones\":[{\"id\":\"revived\",\"deletedAt\":10}],"
                + "\"chats\":[],\"focusHistory\":[],\"currentFocus\":null,\"settings\":{}"
                + "}";
        String taskSide = "{"
                + "\"tasks\":[{\"id\":\"revived\",\"createdAt\":1,\"updatedAt\":11,"
                + "\"title\":\"back\"}],"
                + "\"chats\":[],\"focusHistory\":[],\"currentFocus\":null,\"settings\":{}"
                + "}";

        String merged = DeviceSyncManager.mergeSnapshots(deletionSide, taskSide);

        assertEquals(merged, DeviceSyncManager.mergeSnapshots(taskSide, deletionSide));
        assertTrue(merged.contains("\"taskTombstones\":[]"));
        assertTrue(merged.contains(
                "\"tasks\":[{\"createdAt\":1,\"id\":\"revived\","
                        + "\"title\":\"back\",\"updatedAt\":11}]"
        ));
    }

    @Test
    public void mergeMapsLocalRepositoryAliasesToCanonicalSyncFields() throws Exception {
        String local = "{\"tasks\":[],\"chatMessages\":["
                + "{\"id\":\"chat-local\",\"createdAt\":1,\"content\":\"local\"}],"
                + "\"focusRecords\":[{\"id\":\"focus-local\",\"createdAt\":2}],"
                + "\"currentFocus\":null,\"settings\":{}}";
        String remote = "{\"tasks\":[],\"chats\":["
                + "{\"id\":\"chat-remote\",\"createdAt\":3,\"content\":\"remote\"}],"
                + "\"focusHistory\":[{\"id\":\"focus-remote\",\"createdAt\":4}],"
                + "\"currentFocus\":null,\"settings\":{}}";

        String merged = DeviceSyncManager.mergeSnapshots(local, remote);

        assertTrue(merged.contains("\"chats\":["));
        assertTrue(merged.contains("\"id\":\"chat-local\""));
        assertTrue(merged.contains("\"id\":\"chat-remote\""));
        assertTrue(merged.contains("\"focusHistory\":["));
        assertTrue(merged.contains("\"id\":\"focus-local\""));
        assertTrue(merged.contains("\"id\":\"focus-remote\""));
        assertFalse(merged.contains("chatMessages"));
        assertFalse(merged.contains("focusRecords"));
    }

    @Test
    public void recurrenceOverridesAndReminderEventsMergeAsIndependentEntities() throws Exception {
        String local = "{\"tasks\":[],"
                + "\"recurringTaskGroups\":[{\"id\":\"series\",\"updatedAt\":2,\"title\":\"旧\"}],"
                + "\"recurringOccurrenceOverrides\":[{\"id\":\"series@10\",\"updatedAt\":4,\"type\":\"skipped\"}],"
                + "\"reminderEvents\":[{\"id\":\"event-a\",\"updatedAt\":5,\"state\":\"snoozed\"}],"
                + "\"settings\":{}}";
        String remote = "{\"tasks\":[],"
                + "\"recurringTaskGroups\":[{\"id\":\"series\",\"updatedAt\":3,\"title\":\"新\"}],"
                + "\"recurringOccurrenceOverrides\":[{\"id\":\"series@20\",\"updatedAt\":6,\"type\":\"modified\"}],"
                + "\"reminderEvents\":[{\"id\":\"event-b\",\"updatedAt\":7,\"state\":\"action_required\"}],"
                + "\"settings\":{}}";

        String merged = DeviceSyncManager.mergeSnapshots(local, remote);

        assertEquals(merged, DeviceSyncManager.mergeSnapshots(remote, local));
        assertTrue(merged.contains("\"title\":\"新\""));
        assertFalse(merged.contains("\"title\":\"旧\""));
        assertTrue(merged.contains("\"id\":\"series@10\""));
        assertTrue(merged.contains("\"id\":\"series@20\""));
        assertTrue(merged.contains("\"id\":\"event-a\""));
        assertTrue(merged.contains("\"id\":\"event-b\""));
    }

    @Test
    public void newerChatActionStateWinsEvenWhenCreatedAtMatches() throws Exception {
        String pending = "{\"tasks\":[],\"chats\":[{\"id\":\"proposal\","
                + "\"createdAt\":10,\"updatedAt\":10,"
                + "\"actionState\":\"pending_confirmation\"}],\"settings\":{}}";
        String executed = "{\"tasks\":[],\"chats\":[{\"id\":\"proposal\","
                + "\"createdAt\":10,\"updatedAt\":11,"
                + "\"actionState\":\"executed\"}],\"settings\":{}}";

        String merged = DeviceSyncManager.mergeSnapshots(pending, executed);

        assertTrue(merged.contains("\"actionState\":\"executed\""));
        assertFalse(merged.contains("pending_confirmation"));
    }

    @Test
    public void mergeRejectsEntityWithoutIdWithTypedError() throws Exception {
        String malformed = "{\"tasks\":[{\"updatedAt\":1}],\"settings\":{}}";

        try {
            DeviceSyncManager.mergeSnapshots("{}", malformed);
            fail("Expected malformed snapshot error");
        } catch (DeviceSyncManager.SyncException expected) {
            assertEquals(
                    DeviceSyncManager.ErrorCode.MALFORMED_SNAPSHOT,
                    expected.getCode()
            );
        }
    }

    @Test
    public void frameRoundTripsUtf8() throws Exception {
        String message = "{\"message\":\"专注 25 分钟\"}";
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        DeviceSyncManager.writeFrame(output, message);

        assertEquals(
                message,
                DeviceSyncManager.readFrame(new ByteArrayInputStream(output.toByteArray()))
        );
    }

    @Test
    public void frameRejectsOversizeBeforeAllocatingPayload() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(DeviceSyncManager.MAX_MESSAGE_BYTES + 1);

        try {
            DeviceSyncManager.readFrame(new ByteArrayInputStream(output.toByteArray()));
            fail("Expected message-too-large error");
        } catch (DeviceSyncManager.SyncException expected) {
            assertEquals(
                    DeviceSyncManager.ErrorCode.MESSAGE_TOO_LARGE,
                    expected.getCode()
            );
        }
    }

    @Test
    public void frameRejectsInvalidUtf8() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(output);
        byte[] invalidUtf8 = {(byte) 0xc3, (byte) 0x28};
        data.writeInt(invalidUtf8.length);
        data.write(invalidUtf8);

        try {
            DeviceSyncManager.readFrame(new ByteArrayInputStream(output.toByteArray()));
            fail("Expected malformed-message error");
        } catch (DeviceSyncManager.SyncException expected) {
            assertEquals(
                    DeviceSyncManager.ErrorCode.MALFORMED_MESSAGE,
                    expected.getCode()
            );
        }
    }

    @Test
    public void envelopeRejectsProtocolCodeAndIdentityErrors() throws Exception {
        String valid = "{"
                + "\"type\":\"sync_request\","
                + "\"protocolVersion\":1,"
                + "\"requestId\":\"request-1\","
                + "\"deviceId\":\"desktop-1\","
                + "\"pairingCode\":\"123456\","
                + "\"snapshot\":{}"
                + "}";
        DeviceSyncManager.validateEnvelope(
                valid, "sync_request", "123456", "android-1", null
        );

        assertEnvelopeError(
                valid.replace("\"protocolVersion\":1", "\"protocolVersion\":2"),
                "123456",
                "android-1",
                DeviceSyncManager.ErrorCode.UNSUPPORTED_PROTOCOL
        );
        assertEnvelopeError(
                valid,
                "654321",
                "android-1",
                DeviceSyncManager.ErrorCode.INVALID_PAIRING_CODE
        );
        assertEnvelopeError(
                valid.replace("\"desktop-1\"", "\"android-1\""),
                "123456",
                "android-1",
                DeviceSyncManager.ErrorCode.INVALID_DEVICE_ID
        );
    }

    @Test
    public void sanitizationRemovesSecretKeyVariantsAtAnyDepth() throws Exception {
        String sanitized = DeviceSyncManager.sanitizeSnapshot(
                "{\"apiKey\":\"one\",\"outer\":{\"API_KEY\":\"two\","
                        + "\"items\":[{\"api-key\":\"three\",\"safe\":true}]}}"
        );

        assertEquals(
                "{\"outer\":{\"items\":[{\"safe\":true}]}}",
                sanitized
        );
        assertFalse(sanitized.getBytes(StandardCharsets.UTF_8).length == 0);
    }

    @Test(timeout = 15_000)
    public void localhostJoinAppliesBothSnapshotsWithoutAdvertisingAnUnboundPort()
            throws Exception {
        String hostSnapshot = "{"
                + "\"tasks\":[{\"id\":\"host-task\",\"createdAt\":1,\"updatedAt\":1}],"
                + "\"chats\":[],\"focusHistory\":[],\"currentFocus\":null,\"settings\":{}"
                + "}";
        String joiningSnapshot = "{"
                + "\"tasks\":[{\"id\":\"joining-task\",\"createdAt\":2,\"updatedAt\":2}],"
                + "\"chats\":[],\"focusHistory\":[],\"currentFocus\":null,\"settings\":{}"
                + "}";
        String expectedMerged = DeviceSyncManager.mergeSnapshots(hostSnapshot, joiningSnapshot);
        RecordingSnapshotProvider hostProvider = new RecordingSnapshotProvider(hostSnapshot);
        RecordingSnapshotProvider joiningProvider = new RecordingSnapshotProvider(joiningSnapshot);
        AtomicReference<DeviceSyncManager.HostInfo> hostInfo = new AtomicReference<>();
        AtomicReference<DeviceSyncManager.Peer> hostPeerCallback = new AtomicReference<>();
        AtomicReference<DeviceSyncManager.Peer> joiningPeerCallback = new AtomicReference<>();
        AtomicReference<DeviceSyncManager.SyncError> error = new AtomicReference<>();
        CountDownLatch hostStarted = new CountDownLatch(1);
        CountDownLatch syncCompleted = new CountDownLatch(2);

        DeviceSyncManager host = new DeviceSyncManager(
                "host-device",
                hostProvider,
                new DeviceSyncManager.Callback() {
                    @Override public void onHostStarted(DeviceSyncManager.HostInfo info) {
                        hostInfo.set(info);
                        hostStarted.countDown();
                    }

                    @Override public void onPeerAvailable(DeviceSyncManager.Peer peer) {
                        hostPeerCallback.set(peer);
                    }

                    @Override public void onSyncComplete(String mergedSnapshot) {
                        syncCompleted.countDown();
                    }

                    @Override public void onError(DeviceSyncManager.SyncError syncError) {
                        error.compareAndSet(null, syncError);
                    }
                },
                0,
                Runnable::run
        );
        DeviceSyncManager joining = new DeviceSyncManager(
                "joining-device",
                joiningProvider,
                new DeviceSyncManager.Callback() {
                    @Override public void onPeerAvailable(DeviceSyncManager.Peer peer) {
                        joiningPeerCallback.set(peer);
                    }

                    @Override public void onSyncComplete(String mergedSnapshot) {
                        syncCompleted.countDown();
                    }

                    @Override public void onError(DeviceSyncManager.SyncError syncError) {
                        error.compareAndSet(null, syncError);
                    }
                },
                0,
                Runnable::run
        );

        try {
            host.host();
            assertTrue("ephemeral localhost host did not start",
                    hostStarted.await(5, TimeUnit.SECONDS));
            DeviceSyncManager.HostInfo started = hostInfo.get();
            assertNotNull(started);
            assertTrue(started.port > 0);
            assertEquals("host-device", started.deviceId);

            joining.join("127.0.0.1", started.port, started.pairingCode);

            assertTrue(syncFailureMessage(error),
                    syncCompleted.await(5, TimeUnit.SECONDS));
            assertNull(syncFailureMessage(error), error.get());
            assertEquals(expectedMerged, hostProvider.snapshot);
            assertEquals(expectedMerged, joiningProvider.snapshot);

            DeviceSyncManager.Peer joiningPeer = joiningPeerCallback.get();
            assertNotNull(joiningPeer);
            assertEquals("127.0.0.1", joiningPeer.host);
            assertEquals(started.port, joiningPeer.port);
            assertEquals(started.pairingCode, joiningPeer.pairingCode);
            assertEquals("host-device", joiningPeer.deviceId);
            assertEquals("host-device", joining.getPeer().deviceId);

            // The joining manager was configured with port 0 but never called host(). It must not
            // advertise a listenPort or cause the host to retain an unreachable incoming peer.
            assertNull(hostPeerCallback.get());
            assertNull(host.getPeer());
        } finally {
            joining.stop();
            host.stop();
        }
    }

    @Test(timeout = 20_000)
    public void bidirectionalJoinCreatesReachablePeersAndAllowsReverseSync()
            throws Exception {
        String originalHostSnapshot = "{"
                + "\"tasks\":[{\"id\":\"host-task\",\"createdAt\":1,\"updatedAt\":1}],"
                + "\"chats\":[],\"focusHistory\":[],\"currentFocus\":null,\"settings\":{}"
                + "}";
        String joiningSnapshot = "{"
                + "\"tasks\":[{\"id\":\"joining-task\",\"createdAt\":2,\"updatedAt\":2}],"
                + "\"chats\":[],\"focusHistory\":[],\"currentFocus\":null,\"settings\":{}"
                + "}";
        String expectedFirstMerge = DeviceSyncManager.mergeSnapshots(
                originalHostSnapshot,
                joiningSnapshot
        );
        RecordingSnapshotProvider originalHostProvider =
                new RecordingSnapshotProvider(originalHostSnapshot);
        RecordingSnapshotProvider joiningProvider =
                new RecordingSnapshotProvider(joiningSnapshot);
        AtomicReference<DeviceSyncManager.HostInfo> originalHostInfo = new AtomicReference<>();
        AtomicReference<DeviceSyncManager.HostInfo> joiningListenerInfo = new AtomicReference<>();
        AtomicReference<DeviceSyncManager.SyncError> error = new AtomicReference<>();
        AtomicInteger originalHostSyncCount = new AtomicInteger();
        AtomicInteger joiningSyncCount = new AtomicInteger();
        CountDownLatch originalHostStarted = new CountDownLatch(1);
        CountDownLatch joiningListenerStarted = new CountDownLatch(1);
        CountDownLatch firstSyncCompleted = new CountDownLatch(2);
        CountDownLatch reverseSyncCompleted = new CountDownLatch(2);

        DeviceSyncManager originalHost = new DeviceSyncManager(
                "original-host",
                originalHostProvider,
                new DeviceSyncManager.Callback() {
                    @Override public void onHostStarted(DeviceSyncManager.HostInfo info) {
                        originalHostInfo.set(info);
                        originalHostStarted.countDown();
                    }

                    @Override public void onSyncComplete(String mergedSnapshot) {
                        int count = originalHostSyncCount.incrementAndGet();
                        if (count == 1) firstSyncCompleted.countDown();
                        else if (count == 2) reverseSyncCompleted.countDown();
                    }

                    @Override public void onError(DeviceSyncManager.SyncError syncError) {
                        error.compareAndSet(null, syncError);
                    }
                },
                0,
                Runnable::run
        );
        DeviceSyncManager joining = new DeviceSyncManager(
                "bidirectional-joiner",
                joiningProvider,
                new DeviceSyncManager.Callback() {
                    @Override public void onHostStarted(DeviceSyncManager.HostInfo info) {
                        joiningListenerInfo.set(info);
                        joiningListenerStarted.countDown();
                    }

                    @Override public void onSyncComplete(String mergedSnapshot) {
                        int count = joiningSyncCount.incrementAndGet();
                        if (count == 1) firstSyncCompleted.countDown();
                        else if (count == 2) reverseSyncCompleted.countDown();
                    }

                    @Override public void onError(DeviceSyncManager.SyncError syncError) {
                        error.compareAndSet(null, syncError);
                    }
                },
                0,
                Runnable::run
        );

        try {
            originalHost.host();
            assertTrue("original ephemeral host did not start",
                    originalHostStarted.await(5, TimeUnit.SECONDS));
            DeviceSyncManager.HostInfo hostStarted = originalHostInfo.get();
            assertNotNull(hostStarted);

            joining.joinBidirectionally(
                    "127.0.0.1",
                    hostStarted.port,
                    hostStarted.pairingCode
            );

            assertTrue("joining listener did not bind",
                    joiningListenerStarted.await(5, TimeUnit.SECONDS));
            assertTrue(syncFailureMessage(error),
                    firstSyncCompleted.await(5, TimeUnit.SECONDS));
            assertNull(syncFailureMessage(error), error.get());
            assertEquals(expectedFirstMerge, originalHostProvider.snapshot);
            assertEquals(expectedFirstMerge, joiningProvider.snapshot);

            DeviceSyncManager.HostInfo joinerStarted = joiningListenerInfo.get();
            assertNotNull(joinerStarted);
            assertTrue(joinerStarted.port > 0);
            assertEquals(hostStarted.pairingCode, joinerStarted.pairingCode);
            assertTrue(originalHost.isHosting());
            assertTrue(joining.isHosting());

            DeviceSyncManager.Peer hostPeer = originalHost.getPeer();
            DeviceSyncManager.Peer joiningPeer = joining.getPeer();
            assertNotNull(hostPeer);
            assertNotNull(joiningPeer);
            assertEquals("bidirectional-joiner", hostPeer.deviceId);
            assertEquals(joinerStarted.port, hostPeer.port);
            assertEquals(hostStarted.pairingCode, hostPeer.pairingCode);
            assertEquals("original-host", joiningPeer.deviceId);
            assertEquals(hostStarted.port, joiningPeer.port);
            assertEquals(hostStarted.pairingCode, joiningPeer.pairingCode);

            String newHostState = DeviceSyncManager.mergeSnapshots(
                    originalHostProvider.snapshot,
                    "{\"tasks\":[{\"id\":\"reverse-sync-task\",\"createdAt\":3,"
                            + "\"updatedAt\":3}],\"chats\":[],\"focusHistory\":[],"
                            + "\"currentFocus\":null,\"settings\":{}}"
            );
            originalHostProvider.snapshot = newHostState;
            String expectedReverseMerge = DeviceSyncManager.mergeSnapshots(
                    newHostState,
                    joiningProvider.snapshot
            );

            // The device that originally hosted now initiates the reverse-direction exchange.
            originalHost.syncNow();

            assertTrue(syncFailureMessage(error),
                    reverseSyncCompleted.await(5, TimeUnit.SECONDS));
            assertNull(syncFailureMessage(error), error.get());
            assertEquals(expectedReverseMerge, originalHostProvider.snapshot);
            assertEquals(expectedReverseMerge, joiningProvider.snapshot);
            assertTrue(joiningProvider.snapshot.contains("\"id\":\"reverse-sync-task\""));
            assertEquals(joinerStarted.port, originalHost.getPeer().port);
            assertEquals(hostStarted.port, joining.getPeer().port);
        } finally {
            joining.stop();
            originalHost.stop();
        }
    }

    private static void assertEnvelopeError(
            String message,
            String pairingCode,
            String localDeviceId,
            DeviceSyncManager.ErrorCode errorCode
    ) throws Exception {
        try {
            DeviceSyncManager.validateEnvelope(
                    message,
                    "sync_request",
                    pairingCode,
                    localDeviceId,
                    null
            );
            fail("Expected " + errorCode);
        } catch (DeviceSyncManager.SyncException expected) {
            assertEquals(errorCode, expected.getCode());
        }
    }

    private static String syncFailureMessage(
            AtomicReference<DeviceSyncManager.SyncError> error
    ) {
        DeviceSyncManager.SyncError failure = error.get();
        return failure == null
                ? "localhost sync did not complete"
                : "localhost sync failed: " + failure.code + " (" + failure.message + ")";
    }

    private static final class RecordingSnapshotProvider
            implements DeviceSyncManager.SnapshotProvider {
        private volatile String snapshot;

        RecordingSnapshotProvider(String initialSnapshot) {
            snapshot = initialSnapshot;
        }

        @Override public String getSnapshot() {
            return snapshot;
        }

        @Override public void applyMergedSnapshot(String mergedSnapshot) {
            snapshot = mergedSnapshot;
        }
    }
}
