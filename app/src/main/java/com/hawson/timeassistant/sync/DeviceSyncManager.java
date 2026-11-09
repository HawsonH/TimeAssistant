/**
 * 设备同步管理器
 *
 * 在局域网内通过 TCP 交换数据快照，支持配对码加入与双向同步。
 */
package com.hawson.timeassistant.sync;

import android.os.Handler;
import android.os.Looper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Account-free, local-network snapshot synchronization.
 *
 * <p>The class deliberately depends on {@link SnapshotProvider}, rather than a concrete
 * repository. Provider methods run on an I/O worker. All {@link Callback} methods are dispatched
 * through the supplied main-thread executor (the Android main looper by default).</p>
 */
public final class DeviceSyncManager {
    public static final int PROTOCOL_VERSION = 1;
    public static final int DEFAULT_PORT = 37681;
    public static final int MAX_MESSAGE_BYTES = 2 * 1024 * 1024;
    public static final int CONNECT_TIMEOUT_MS = 5_000;
    public static final int READ_TIMEOUT_MS = 10_000;

    private static final int MAX_JSON_DEPTH = 64;
    private static final int MAX_DEVICE_ID_LENGTH = 128;
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern PAIRING_CODE_PATTERN = Pattern.compile("\\d{6}");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Object stateLock = new Object();
    private final String deviceId;
    private final SnapshotProvider snapshotProvider;
    private final Callback callback;
    private final Executor callbackExecutor;
    private final ExecutorService ioExecutor;
    private final int configuredPort;
    private final Set<Socket> activeSockets = Collections.synchronizedSet(new HashSet<>());

    private volatile ServerSocket serverSocket;
    private volatile boolean hostStarting;
    private volatile boolean stopped;
    private volatile String pairingCode;
    private volatile Peer peer;

    public DeviceSyncManager(
            String deviceId,
            SnapshotProvider snapshotProvider,
            Callback callback
    ) {
        this(deviceId, snapshotProvider, callback, DEFAULT_PORT, androidMainThreadExecutor());
    }

    public DeviceSyncManager(
            String deviceId,
            SnapshotProvider snapshotProvider,
            Callback callback,
            int port
    ) {
        this(deviceId, snapshotProvider, callback, port, androidMainThreadExecutor());
    }

    /**
     * Constructor for callers that already own a main-thread executor and for host-side tests.
     */
    public DeviceSyncManager(
            String deviceId,
            SnapshotProvider snapshotProvider,
            Callback callback,
            int port,
            Executor mainThreadExecutor
    ) {
        if (!isValidDeviceId(deviceId)) {
            throw new IllegalArgumentException("deviceId must contain 1-128 safe ASCII characters");
        }
        if (snapshotProvider == null) throw new IllegalArgumentException("snapshotProvider is required");
        if (callback == null) throw new IllegalArgumentException("callback is required");
        if (mainThreadExecutor == null) throw new IllegalArgumentException("mainThreadExecutor is required");
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("port is out of range");

        this.deviceId = deviceId;
        this.snapshotProvider = snapshotProvider;
        this.callback = callback;
        this.configuredPort = port;
        this.callbackExecutor = mainThreadExecutor;
        this.ioExecutor = Executors.newCachedThreadPool(new SyncThreadFactory());
    }

    /**
     * Starts listening and returns the six-digit code that should be shown to the joining device.
     * Binding completes asynchronously and is confirmed by {@link Callback#onHostStarted(HostInfo)}.
     */
    public String host() {
        final String code;
        synchronized (stateLock) {
            if (stopped) {
                reportError(ErrorCode.STOPPED);
                return null;
            }
            if (hostStarting || serverSocket != null) return pairingCode;
            pairingCode = generatePairingCode();
            code = pairingCode;
            hostStarting = true;
        }

        try {
            ioExecutor.execute(() -> runHost(code, null));
        } catch (RejectedExecutionException rejected) {
            synchronized (stateLock) {
                hostStarting = false;
            }
            reportError(ErrorCode.STOPPED);
            return null;
        }
        return code;
    }

    /** Pairs with a host and performs the first two-way snapshot exchange. */
    public void join(String host, int port, String code) {
        if (stopped) {
            reportError(ErrorCode.STOPPED);
            return;
        }
        if (!isValidHost(host) || !isValidPort(port)) {
            reportError(ErrorCode.INVALID_ARGUMENT);
            return;
        }
        if (!isValidPairingCode(code)) {
            reportError(ErrorCode.INVALID_PAIRING_CODE);
            return;
        }
        Peer candidate = new Peer(host.trim(), port, code, null);
        submitExchange(candidate);
    }

    /**
     * Starts a real local listener using the remote host's pairing code, then joins that host.
     *
     * <p>The first exchange is submitted only after the listener has bound successfully, so the
     * request can advertise a reachable listenPort. This lets the original host retain a Peer and
     * initiate later syncs back to this device. {@link #join(String, int, String)} remains the
     * outbound-only variant.</p>
     */
    public void joinBidirectionally(String host, int port, String code) {
        if (stopped) {
            reportError(ErrorCode.STOPPED);
            return;
        }
        if (!isValidHost(host) || !isValidPort(port)) {
            reportError(ErrorCode.INVALID_ARGUMENT);
            return;
        }
        if (!isValidPairingCode(code)) {
            reportError(ErrorCode.INVALID_PAIRING_CODE);
            return;
        }

        Peer candidate = new Peer(host.trim(), port, code, null);
        boolean startListener = false;
        ErrorCode stateError = null;
        synchronized (stateLock) {
            if (stopped) {
                stateError = ErrorCode.STOPPED;
            } else {
                ServerSocket current = serverSocket;
                boolean alreadyListening = current != null
                        && current.isBound()
                        && !current.isClosed();
                if (alreadyListening) {
                    if (!constantTimeEquals(pairingCode, code)) {
                        stateError = ErrorCode.INVALID_ARGUMENT;
                    }
                } else if (hostStarting) {
                    stateError = ErrorCode.INVALID_ARGUMENT;
                } else {
                    pairingCode = code;
                    hostStarting = true;
                    startListener = true;
                }
            }
        }
        if (stateError != null) {
            reportError(stateError);
            return;
        }
        if (!startListener) {
            submitExchange(candidate);
            return;
        }

        try {
            ioExecutor.execute(() -> runHost(code, candidate));
        } catch (RejectedExecutionException rejected) {
            synchronized (stateLock) {
                hostStarting = false;
            }
            reportError(ErrorCode.STOPPED);
        }
    }

    /** Synchronizes with the last joined or restored peer. */
    public void syncNow() {
        if (stopped) {
            reportError(ErrorCode.STOPPED);
            return;
        }
        Peer current = peer;
        if (current == null || !isValidPort(current.port)) {
            reportError(ErrorCode.NOT_PAIRED);
            return;
        }
        submitExchange(current);
    }

    /**
     * Restores peer data persisted by the calling layer. Passing null clears the current peer.
     */
    public void setPeer(Peer restoredPeer) {
        if (restoredPeer == null) {
            peer = null;
            return;
        }
        if (!isValidHost(restoredPeer.host)
                || !isValidPort(restoredPeer.port)
                || !isValidPairingCode(restoredPeer.pairingCode)
                || (restoredPeer.deviceId != null && !isValidDeviceId(restoredPeer.deviceId))) {
            reportError(ErrorCode.INVALID_ARGUMENT);
            return;
        }
        peer = restoredPeer;
    }

    public Peer getPeer() {
        return peer;
    }

    public String getPairingCode() {
        return pairingCode;
    }

    public int getConfiguredPort() {
        return configuredPort;
    }

    public boolean isHosting() {
        ServerSocket current = serverSocket;
        return current != null && !current.isClosed();
    }

    /** Stops listening, closes in-flight sockets, and makes this manager terminal. */
    public void stop() {
        synchronized (stateLock) {
            if (stopped) return;
            stopped = true;
            hostStarting = false;
        }
        closeQuietly(serverSocket);
        synchronized (activeSockets) {
            for (Socket socket : activeSockets) closeQuietly(socket);
            activeSockets.clear();
        }
        ioExecutor.shutdownNow();
        dispatch(callback::onStopped);
    }

    /**
     * Merges two snapshot JSON objects and returns canonical JSON.
     *
     * <p>tasks, chats and focusHistory are merged by id, choosing the greatest updatedAt (or
     * createdAt when updatedAt is absent). Task tombstones are merged by id and suppress a task at
     * an equal or older version; only a strictly newer task revives it. currentFocus is chosen by
     * updatedAt. Settings merge per key. Equal-version conflicts use canonical JSON as a stable
     * tie-breaker. Secret apiKey keys are removed recursively before any result is returned.</p>
     */
    public static String mergeSnapshots(String localSnapshot, String remoteSnapshot)
            throws SyncException {
        Map<String, Object> local = parseSnapshot(localSnapshot);
        Map<String, Object> remote = parseSnapshot(remoteSnapshot);
        return Json.stringify(mergeSnapshotMaps(local, remote));
    }

    /** Returns canonical snapshot JSON with apiKey fields removed recursively. */
    public static String sanitizeSnapshot(String snapshot) throws SyncException {
        return Json.stringify(parseSnapshot(snapshot));
    }

    public static String generatePairingCode() {
        return String.format(java.util.Locale.ROOT, "%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private void submitExchange(Peer candidate) {
        try {
            ioExecutor.execute(() -> exchangeWithPeer(candidate));
        } catch (RejectedExecutionException rejected) {
            reportError(ErrorCode.STOPPED);
        }
    }

    private void runHost(String code, Peer exchangeAfterStart) {
        ServerSocket listening = null;
        try {
            listening = new ServerSocket();
            listening.setReuseAddress(true);
            listening.bind(new InetSocketAddress(configuredPort));
            synchronized (stateLock) {
                if (stopped) {
                    closeQuietly(listening);
                    return;
                }
                serverSocket = listening;
                hostStarting = false;
            }
            HostInfo info = new HostInfo(listening.getLocalPort(), code, deviceId);
            dispatch(() -> callback.onHostStarted(info));
            if (exchangeAfterStart != null) submitExchange(exchangeAfterStart);

            while (!stopped && listening == serverSocket) {
                Socket socket = listening.accept();
                configureSocket(socket);
                activeSockets.add(socket);
                try {
                    ioExecutor.execute(() -> handleIncoming(socket, code));
                } catch (RejectedExecutionException rejected) {
                    activeSockets.remove(socket);
                    closeQuietly(socket);
                }
            }
        } catch (SocketException socketClosed) {
            if (!stopped) reportError(ErrorCode.IO_ERROR);
        } catch (IOException io) {
            if (!stopped) reportError(ErrorCode.IO_ERROR);
        } finally {
            closeQuietly(listening);
            synchronized (stateLock) {
                if (serverSocket == listening) serverSocket = null;
                hostStarting = false;
            }
        }
    }

    private void handleIncoming(Socket socket, String expectedCode) {
        String requestId = null;
        String remoteDeviceId = null;
        try {
            String payload = readFrame(socket.getInputStream());
            Map<String, Object> request = parseMessage(payload);
            requestId = optionalString(request, "requestId");
            remoteDeviceId = requireDeviceId(request, "deviceId");
            validateEnvelopeMap(request, "sync_request", expectedCode, deviceId, null, false);

            Object snapshotValue = request.get("snapshot");
            if (!(snapshotValue instanceof Map)) {
                throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
            }

            Map<String, Object> local = readLocalSnapshot();
            Map<String, Object> remote = sanitizeObject(castMap(snapshotValue), 0);
            Map<String, Object> merged = mergeSnapshotMaps(local, remote);
            String mergedJson = Json.stringify(merged);
            applyLocalSnapshot(mergedJson);

            Map<String, Object> response = new TreeMap<>();
            response.put("type", "sync_response");
            response.put("protocolVersion", PROTOCOL_VERSION);
            response.put("requestId", requestId);
            response.put("deviceId", deviceId);
            response.put("targetDeviceId", remoteDeviceId);
            response.put("pairingCode", expectedCode);
            response.put("snapshot", merged);
            writeFrame(socket.getOutputStream(), Json.stringify(response));

            int advertisedPort = optionalPort(request.get("listenPort"));
            if (advertisedPort > 0) {
                Peer incomingPeer = new Peer(
                        socket.getInetAddress().getHostAddress(),
                        advertisedPort,
                        expectedCode,
                        remoteDeviceId
                );
                peer = incomingPeer;
                dispatch(() -> callback.onPeerAvailable(incomingPeer));
            }
            dispatch(() -> callback.onSyncComplete(mergedJson));
        } catch (SyncException sync) {
            sendError(socket, requestId, remoteDeviceId, sync.code);
            reportError(sync.code);
        } catch (SocketTimeoutException timeout) {
            sendError(socket, requestId, remoteDeviceId, ErrorCode.TIMEOUT);
            reportError(ErrorCode.TIMEOUT);
        } catch (IOException io) {
            if (!stopped) reportError(ErrorCode.IO_ERROR);
        } finally {
            activeSockets.remove(socket);
            closeQuietly(socket);
        }
    }

    private void exchangeWithPeer(Peer candidate) {
        Socket socket = new Socket();
        activeSockets.add(socket);
        try (Socket ignored = socket) {
            socket.connect(new InetSocketAddress(candidate.host, candidate.port), CONNECT_TIMEOUT_MS);
            configureSocket(socket);

            String requestId = UUID.randomUUID().toString();
            Map<String, Object> request = new TreeMap<>();
            request.put("type", "sync_request");
            request.put("protocolVersion", PROTOCOL_VERSION);
            request.put("requestId", requestId);
            request.put("deviceId", deviceId);
            if (candidate.deviceId != null) request.put("targetDeviceId", candidate.deviceId);
            request.put("pairingCode", candidate.pairingCode);
            int listenPort = advertisedPort();
            if (listenPort > 0) request.put("listenPort", listenPort);
            request.put("snapshot", readLocalSnapshot());
            writeFrame(socket.getOutputStream(), Json.stringify(request));

            Map<String, Object> response = parseMessage(readFrame(socket.getInputStream()));
            if ("error".equals(optionalString(response, "type"))) {
                validateRemoteError(response, requestId, candidate);
            }
            validateEnvelopeMap(
                    response,
                    "sync_response",
                    candidate.pairingCode,
                    deviceId,
                    candidate.deviceId,
                    true
            );
            if (!requestId.equals(requireString(response, "requestId"))) {
                throw new SyncException(ErrorCode.MALFORMED_MESSAGE);
            }
            String remoteDeviceId = requireDeviceId(response, "deviceId");
            Object snapshotValue = response.get("snapshot");
            if (!(snapshotValue instanceof Map)) {
                throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
            }

            // Re-read local state so edits made while the request was in flight are not discarded.
            Map<String, Object> currentLocal = readLocalSnapshot();
            Map<String, Object> returned = sanitizeObject(castMap(snapshotValue), 0);
            String mergedJson = Json.stringify(mergeSnapshotMaps(currentLocal, returned));
            applyLocalSnapshot(mergedJson);

            Peer confirmed = new Peer(
                    candidate.host,
                    candidate.port,
                    candidate.pairingCode,
                    remoteDeviceId
            );
            peer = confirmed;
            dispatch(() -> callback.onPeerAvailable(confirmed));
            dispatch(() -> callback.onSyncComplete(mergedJson));
        } catch (SyncException sync) {
            reportError(sync.code);
        } catch (SocketTimeoutException timeout) {
            reportError(ErrorCode.TIMEOUT);
        } catch (IOException io) {
            if (!stopped) reportError(ErrorCode.IO_ERROR);
        } finally {
            activeSockets.remove(socket);
            closeQuietly(socket);
        }
    }

    private void validateRemoteError(Map<String, Object> response, String requestId, Peer candidate)
            throws SyncException {
        requireProtocolVersion(response);
        String responseRequestId = optionalString(response, "requestId");
        if (responseRequestId != null && !requestId.equals(responseRequestId)) {
            throw new SyncException(ErrorCode.PEER_MISMATCH);
        }
        String remoteId = requireDeviceId(response, "deviceId");
        if (candidate.deviceId != null && !candidate.deviceId.equals(remoteId)) {
            throw new SyncException(ErrorCode.PEER_MISMATCH);
        }
        String target = optionalString(response, "targetDeviceId");
        if (target != null && !deviceId.equals(target)) {
            throw new SyncException(ErrorCode.PEER_MISMATCH);
        }
        String rawCode = optionalString(response, "errorCode");
        ErrorCode remoteCode;
        try {
            remoteCode = rawCode == null ? ErrorCode.REMOTE_ERROR : ErrorCode.valueOf(rawCode);
        } catch (IllegalArgumentException unknown) {
            remoteCode = ErrorCode.REMOTE_ERROR;
        }
        throw new SyncException(remoteCode);
    }

    private void sendError(
            Socket socket,
            String requestId,
            String targetDeviceId,
            ErrorCode errorCode
    ) {
        if (socket == null || socket.isClosed() || !socket.isConnected()) return;
        try {
            Map<String, Object> response = new TreeMap<>();
            response.put("type", "error");
            response.put("protocolVersion", PROTOCOL_VERSION);
            response.put("deviceId", deviceId);
            if (requestId != null) response.put("requestId", requestId);
            if (targetDeviceId != null) response.put("targetDeviceId", targetDeviceId);
            response.put("errorCode", errorCode.name());
            response.put("message", messageFor(errorCode));
            writeFrame(socket.getOutputStream(), Json.stringify(response));
        } catch (IOException | SyncException ignored) {
            // The original failure is more useful than a best-effort error-response failure.
        }
    }

    private Map<String, Object> readLocalSnapshot() throws SyncException {
        final String raw;
        try {
            raw = snapshotProvider.getSnapshot();
        } catch (Exception providerFailure) {
            throw new SyncException(ErrorCode.SNAPSHOT_PROVIDER_ERROR);
        }
        return parseSnapshot(raw);
    }

    private void applyLocalSnapshot(String mergedSnapshot) throws SyncException {
        try {
            snapshotProvider.applyMergedSnapshot(mergedSnapshot);
        } catch (Exception providerFailure) {
            throw new SyncException(ErrorCode.SNAPSHOT_PROVIDER_ERROR);
        }
    }

    private int advertisedPort() {
        ServerSocket current = serverSocket;
        if (current != null && current.isBound() && !current.isClosed()) {
            return current.getLocalPort();
        }
        return -1;
    }

    private static void configureSocket(Socket socket) throws SocketException {
        socket.setSoTimeout(READ_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
    }

    private void reportError(ErrorCode code) {
        SyncError error = new SyncError(code, messageFor(code));
        dispatch(() -> callback.onError(error));
    }

    private void dispatch(Runnable action) {
        try {
            callbackExecutor.execute(action);
        } catch (RuntimeException ignored) {
            // Callback delivery must never crash an I/O worker.
        }
    }

    private static Executor androidMainThreadExecutor() {
        Handler handler = new Handler(Looper.getMainLooper());
        return action -> {
            if (Looper.myLooper() == Looper.getMainLooper()) action.run();
            else handler.post(action);
        };
    }

    static void writeFrame(OutputStream output, String message) throws IOException, SyncException {
        if (message == null) throw new SyncException(ErrorCode.MALFORMED_MESSAGE);
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        validateFrameLength(bytes.length);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(bytes.length);
        data.write(bytes);
        data.flush();
    }

    static String readFrame(InputStream input) throws IOException, SyncException {
        DataInputStream data = new DataInputStream(input);
        final int length;
        try {
            length = data.readInt();
        } catch (EOFException end) {
            throw new SyncException(ErrorCode.MALFORMED_MESSAGE);
        }
        validateFrameLength(length);
        byte[] bytes = new byte[length];
        data.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new SyncException(ErrorCode.MALFORMED_MESSAGE);
        }
    }

    static void validateFrameLength(int length) throws SyncException {
        if (length <= 0) throw new SyncException(ErrorCode.MALFORMED_MESSAGE);
        if (length > MAX_MESSAGE_BYTES) throw new SyncException(ErrorCode.MESSAGE_TOO_LARGE);
    }

    static void validateEnvelope(
            String json,
            String expectedType,
            String expectedCode,
            String localDeviceId,
            String expectedRemoteDeviceId
    ) throws SyncException {
        validateEnvelopeMap(
                parseMessage(json),
                expectedType,
                expectedCode,
                localDeviceId,
                expectedRemoteDeviceId,
                false
        );
    }

    private static void validateEnvelopeMap(
            Map<String, Object> message,
            String expectedType,
            String expectedCode,
            String localDeviceId,
            String expectedRemoteDeviceId,
            boolean requireTarget
    ) throws SyncException {
        if (!expectedType.equals(requireString(message, "type"))) {
            throw new SyncException(ErrorCode.MALFORMED_MESSAGE);
        }
        requireProtocolVersion(message);
        if (!constantTimeEquals(expectedCode, requireString(message, "pairingCode"))) {
            throw new SyncException(ErrorCode.INVALID_PAIRING_CODE);
        }
        String remoteDeviceId = requireDeviceId(message, "deviceId");
        if (remoteDeviceId.equals(localDeviceId)) {
            throw new SyncException(ErrorCode.INVALID_DEVICE_ID);
        }
        if (expectedRemoteDeviceId != null && !expectedRemoteDeviceId.equals(remoteDeviceId)) {
            throw new SyncException(ErrorCode.PEER_MISMATCH);
        }
        String target = optionalString(message, "targetDeviceId");
        if ((requireTarget && target == null) || (target != null && !localDeviceId.equals(target))) {
            throw new SyncException(ErrorCode.PEER_MISMATCH);
        }
        String requestId = requireString(message, "requestId");
        if (requestId.length() > 128) throw new SyncException(ErrorCode.MALFORMED_MESSAGE);
    }

    private static void requireProtocolVersion(Map<String, Object> message) throws SyncException {
        int version = requireInt(message.get("protocolVersion"), ErrorCode.MALFORMED_MESSAGE);
        if (version != PROTOCOL_VERSION) {
            throw new SyncException(ErrorCode.UNSUPPORTED_PROTOCOL);
        }
    }

    private static Map<String, Object> parseMessage(String json) throws SyncException {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map)) throw new SyncException(ErrorCode.MALFORMED_MESSAGE);
        return castMap(parsed);
    }

    private static Map<String, Object> parseSnapshot(String json) throws SyncException {
        if (json == null) throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (SyncException malformed) {
            throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
        }
        if (!(parsed instanceof Map)) throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
        return sanitizeObject(castMap(parsed), 0);
    }

    private static Map<String, Object> mergeSnapshotMaps(
            Map<String, Object> local,
            Map<String, Object> remote
    ) throws SyncException {
        Map<String, Object> result = new TreeMap<>();
        TaskMergeResult taskMerge = mergeTasksAndTombstones(
                local.get("tasks"),
                remote.get("tasks"),
                local.get("taskTombstones"),
                remote.get("taskTombstones")
        );
        result.put("tasks", taskMerge.tasks);
        result.put("taskTombstones", taskMerge.tombstones);
        TaskMergeResult seriesMerge = mergeTasksAndTombstones(
                local.get("recurringTaskGroups"), remote.get("recurringTaskGroups"),
                local.get("recurringTaskGroupTombstones"),
                remote.get("recurringTaskGroupTombstones"));
        result.put("recurringTaskGroups", seriesMerge.tasks);
        result.put("recurringTaskGroupTombstones", seriesMerge.tombstones);
        TaskMergeResult overrideMerge = mergeTasksAndTombstones(
                local.get("recurringOccurrenceOverrides"),
                remote.get("recurringOccurrenceOverrides"),
                local.get("recurringOccurrenceOverrideTombstones"),
                remote.get("recurringOccurrenceOverrideTombstones"));
        result.put("recurringOccurrenceOverrides", overrideMerge.tasks);
        result.put("recurringOccurrenceOverrideTombstones", overrideMerge.tombstones);
        result.put("reminderEvents", mergeEntityArrays(
                local.get("reminderEvents"), remote.get("reminderEvents")));
        // Older/local repository exports used chatMessages/focusRecords.  Accept those aliases
        // as input too, while always emitting the canonical protocol field names.
        result.put("chats", mergeEntityArrays(
                firstPresent(local, "chats", "chatMessages"),
                firstPresent(remote, "chats", "chatMessages")));
        result.put(
                "focusHistory",
                mergeEntityArrays(
                        firstPresent(local, "focusHistory", "focusRecords"),
                        firstPresent(remote, "focusHistory", "focusRecords"))
        );
        result.put("currentFocus", mergeCurrentFocus(local.get("currentFocus"), remote.get("currentFocus")));
        result.put("settings", mergeSettings(local.get("settings"), remote.get("settings")));

        Set<String> otherKeys = new HashSet<>();
        otherKeys.addAll(local.keySet());
        otherKeys.addAll(remote.keySet());
        otherKeys.remove("tasks");
        otherKeys.remove("taskTombstones");
        otherKeys.remove("recurringTaskGroups");
        otherKeys.remove("recurringTaskGroupTombstones");
        otherKeys.remove("recurringOccurrenceOverrides");
        otherKeys.remove("recurringOccurrenceOverrideTombstones");
        otherKeys.remove("reminderEvents");
        otherKeys.remove("chats");
        otherKeys.remove("focusHistory");
        otherKeys.remove("chatMessages");
        otherKeys.remove("focusRecords");
        otherKeys.remove("currentFocus");
        otherKeys.remove("settings");
        for (String key : otherKeys) {
            if (isSecretKey(key)) continue;
            result.put(key, chooseStableValue(local.get(key), remote.get(key), true));
        }
        return result;
    }

    private static TaskMergeResult mergeTasksAndTombstones(
            Object localTasksValue,
            Object remoteTasksValue,
            Object localTombstonesValue,
            Object remoteTombstonesValue
    ) throws SyncException {
        Map<String, Map<String, Object>> tasksById = new TreeMap<>();
        mergeEntitySide(tasksById, requireArrayOrEmpty(localTasksValue));
        mergeEntitySide(tasksById, requireArrayOrEmpty(remoteTasksValue));

        Map<String, Map<String, Object>> tombstonesById = new TreeMap<>();
        mergeTombstoneSide(tombstonesById, requireArrayOrEmpty(localTombstonesValue));
        mergeTombstoneSide(tombstonesById, requireArrayOrEmpty(remoteTombstonesValue));

        for (String id : new HashSet<>(tombstonesById.keySet())) {
            Map<String, Object> task = tasksById.get(id);
            if (task == null) continue;
            Map<String, Object> tombstone = tombstonesById.get(id);
            long taskVersion = timestamp(task, true);
            long tombstoneVersion = tombstoneTimestamp(tombstone);
            if (tombstoneVersion >= taskVersion) {
                // Deletion wins an exact-version tie so a stale task cannot be resurrected.
                tasksById.remove(id);
            } else {
                // A strictly newer task is an explicit resurrection and supersedes the tombstone.
                tombstonesById.remove(id);
            }
        }
        return new TaskMergeResult(
                new ArrayList<>(tasksById.values()),
                new ArrayList<>(tombstonesById.values())
        );
    }

    private static List<Object> mergeEntityArrays(Object localValue, Object remoteValue)
            throws SyncException {
        List<Object> local = requireArrayOrEmpty(localValue);
        List<Object> remote = requireArrayOrEmpty(remoteValue);
        Map<String, Map<String, Object>> byId = new TreeMap<>();
        mergeEntitySide(byId, local);
        mergeEntitySide(byId, remote);
        return new ArrayList<>(byId.values());
    }

    private static Object firstPresent(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key)) return values.get(key);
        }
        return null;
    }

    private static void mergeEntitySide(
            Map<String, Map<String, Object>> byId,
            List<Object> values
    ) throws SyncException {
        for (Object value : values) {
            if (!(value instanceof Map)) throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
            Map<String, Object> entity = sanitizeObject(castMap(value), 0);
            String id = optionalString(entity, "id");
            if (id == null || id.isEmpty() || id.length() > 256) {
                throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
            }
            Map<String, Object> existing = byId.get(id);
            if (existing == null || compareVersioned(entity, existing, true) > 0) {
                byId.put(id, entity);
            }
        }
    }

    private static void mergeTombstoneSide(
            Map<String, Map<String, Object>> byId,
            List<Object> values
    ) throws SyncException {
        for (Object value : values) {
            if (!(value instanceof Map)) throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
            Map<String, Object> tombstone = sanitizeObject(castMap(value), 0);
            String id = optionalString(tombstone, "id");
            if (id == null || id.isEmpty() || id.length() > 256) {
                throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
            }
            tombstoneTimestamp(tombstone);
            Map<String, Object> existing = byId.get(id);
            if (existing == null || compareTombstones(tombstone, existing) > 0) {
                byId.put(id, tombstone);
            }
        }
    }

    private static Object mergeCurrentFocus(Object local, Object remote) throws SyncException {
        if (local != null && !(local instanceof Map)) {
            throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
        }
        if (remote != null && !(remote instanceof Map)) {
            throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
        }
        return chooseStableValue(local, remote, false);
    }

    private static Map<String, Object> mergeSettings(Object localValue, Object remoteValue)
            throws SyncException {
        Map<String, Object> local = requireObjectOrEmpty(localValue);
        Map<String, Object> remote = requireObjectOrEmpty(remoteValue);
        Map<String, Object> result = new TreeMap<>();
        Set<String> keys = new HashSet<>();
        keys.addAll(local.keySet());
        keys.addAll(remote.keySet());
        for (String key : keys) {
            if (isSecretKey(key)) continue;
            result.put(key, mergeSettingValue(local.get(key), remote.get(key)));
        }
        return result;
    }

    /**
     * Settings are nested in the Android snapshot. Merge unversioned object members recursively
     * so a change to focus preferences does not erase an unrelated time-preference change. A
     * map carrying its own updatedAt/createdAt remains an atomic versioned value.
     */
    private static Object mergeSettingValue(Object first, Object second) throws SyncException {
        if (!(first instanceof Map) || !(second instanceof Map)) {
            return chooseStableValue(first, second, true);
        }
        Map<String, Object> firstMap = castMap(first);
        Map<String, Object> secondMap = castMap(second);
        if (firstMap.containsKey("updatedAt") || firstMap.containsKey("createdAt")
                || secondMap.containsKey("updatedAt") || secondMap.containsKey("createdAt")) {
            return chooseStableValue(first, second, true);
        }
        Map<String, Object> merged = new TreeMap<>();
        Set<String> keys = new HashSet<>();
        keys.addAll(firstMap.keySet());
        keys.addAll(secondMap.keySet());
        for (String key : keys) {
            if (isSecretKey(key)) continue;
            Object firstValue = firstMap.get(key);
            Object secondValue = secondMap.get(key);
            merged.put(key, mergeSettingValue(firstValue, secondValue));
        }
        return merged;
    }

    private static Object chooseStableValue(Object first, Object second, boolean allowCreatedAt)
            throws SyncException {
        if (first == null) return second;
        if (second == null) return first;
        if (first instanceof Map && second instanceof Map) {
            int comparison = compareVersioned(
                    castMap(first),
                    castMap(second),
                    allowCreatedAt
            );
            return comparison >= 0 ? first : second;
        }
        String firstJson = Json.stringify(first);
        String secondJson = Json.stringify(second);
        return firstJson.compareTo(secondJson) >= 0 ? first : second;
    }

    private static int compareVersioned(
            Map<String, Object> first,
            Map<String, Object> second,
            boolean allowCreatedAt
    ) throws SyncException {
        long firstTimestamp = timestamp(first, allowCreatedAt);
        long secondTimestamp = timestamp(second, allowCreatedAt);
        int timestampComparison = Long.compare(firstTimestamp, secondTimestamp);
        if (timestampComparison != 0) return timestampComparison;
        return Json.stringify(first).compareTo(Json.stringify(second));
    }

    private static int compareTombstones(
            Map<String, Object> first,
            Map<String, Object> second
    ) throws SyncException {
        int timestampComparison = Long.compare(
                tombstoneTimestamp(first),
                tombstoneTimestamp(second)
        );
        if (timestampComparison != 0) return timestampComparison;
        return Json.stringify(first).compareTo(Json.stringify(second));
    }

    private static long timestamp(Map<String, Object> value, boolean allowCreatedAt)
            throws SyncException {
        Object timestamp = value.get("updatedAt");
        if (timestamp == null && allowCreatedAt) timestamp = value.get("createdAt");
        if (timestamp == null) return Long.MIN_VALUE;
        return parseTimestamp(timestamp);
    }

    private static long tombstoneTimestamp(Map<String, Object> tombstone)
            throws SyncException {
        Object deletedAt = tombstone.get("deletedAt");
        Object updatedAt = tombstone.get("updatedAt");
        if (deletedAt == null && updatedAt == null) {
            throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
        }
        if (deletedAt != null) parseTimestamp(deletedAt);
        return parseTimestamp(updatedAt != null ? updatedAt : deletedAt);
    }

    private static long parseTimestamp(Object timestamp) throws SyncException {
        try {
            if (timestamp instanceof BigDecimal) return ((BigDecimal) timestamp).longValueExact();
            if (timestamp instanceof Number) return ((Number) timestamp).longValue();
            if (timestamp instanceof String) return Long.parseLong((String) timestamp);
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
        }
        throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
    }

    private static final class TaskMergeResult {
        final List<Object> tasks;
        final List<Object> tombstones;

        TaskMergeResult(List<Object> tasks, List<Object> tombstones) {
            this.tasks = tasks;
            this.tombstones = tombstones;
        }
    }

    private static List<Object> requireArrayOrEmpty(Object value) throws SyncException {
        if (value == null) return new ArrayList<>();
        if (!(value instanceof List)) throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
        return castList(value);
    }

    private static Map<String, Object> requireObjectOrEmpty(Object value) throws SyncException {
        if (value == null) return new TreeMap<>();
        if (!(value instanceof Map)) throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
        return castMap(value);
    }

    private static Map<String, Object> sanitizeObject(Map<String, Object> source, int depth)
            throws SyncException {
        if (depth > MAX_JSON_DEPTH) throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
        Map<String, Object> result = new TreeMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (isSecretKey(entry.getKey())) continue;
            result.put(entry.getKey(), sanitizeValue(entry.getValue(), depth + 1));
        }
        return result;
    }

    private static Object sanitizeValue(Object value, int depth) throws SyncException {
        if (depth > MAX_JSON_DEPTH) throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
        if (value instanceof Map) return sanitizeObject(castMap(value), depth);
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : castList(value)) result.add(sanitizeValue(item, depth + 1));
            return result;
        }
        return value;
    }

    private static boolean isSecretKey(String key) {
        if (key == null) return false;
        String normalized = key.replace("_", "").replace("-", "");
        return "apikey".equalsIgnoreCase(normalized);
    }

    private static int requireInt(Object value, ErrorCode errorCode) throws SyncException {
        try {
            if (value instanceof BigDecimal) return ((BigDecimal) value).intValueExact();
            if (value instanceof Number) return ((Number) value).intValue();
            if (value instanceof String) return Integer.parseInt((String) value);
        } catch (ArithmeticException | NumberFormatException ignored) {
            throw new SyncException(errorCode);
        }
        throw new SyncException(errorCode);
    }

    private static int optionalPort(Object value) {
        if (value == null) return -1;
        try {
            int port = requireInt(value, ErrorCode.MALFORMED_MESSAGE);
            return isValidPort(port) ? port : -1;
        } catch (SyncException ignored) {
            return -1;
        }
    }

    private static String requireString(Map<String, Object> object, String key) throws SyncException {
        String value = optionalString(object, key);
        if (value == null || value.isEmpty()) throw new SyncException(ErrorCode.MALFORMED_MESSAGE);
        return value;
    }

    private static String requireDeviceId(Map<String, Object> object, String key) throws SyncException {
        String value = optionalString(object, key);
        if (!isValidDeviceId(value)) throw new SyncException(ErrorCode.INVALID_DEVICE_ID);
        return value;
    }

    private static String optionalString(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value instanceof String ? (String) value : null;
    }

    private static boolean isValidDeviceId(String value) {
        return value != null
                && value.length() <= MAX_DEVICE_ID_LENGTH
                && DEVICE_ID_PATTERN.matcher(value).matches();
    }

    private static boolean isValidPairingCode(String value) {
        return value != null && PAIRING_CODE_PATTERN.matcher(value).matches();
    }

    private static boolean isValidHost(String value) {
        return value != null && !value.trim().isEmpty() && value.trim().length() <= 255;
    }

    private static boolean isValidPort(int value) {
        return value >= 1 && value <= 65_535;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String messageFor(ErrorCode code) {
        switch (code) {
            case INVALID_ARGUMENT: return "Invalid sync address or peer configuration.";
            case STOPPED: return "Device sync has been stopped.";
            case NOT_PAIRED: return "No paired device is available.";
            case IO_ERROR: return "The peer could not be reached on the local network.";
            case TIMEOUT: return "The sync connection timed out.";
            case MESSAGE_TOO_LARGE: return "The sync message exceeds the allowed size.";
            case MALFORMED_MESSAGE: return "The peer sent an invalid sync message.";
            case MALFORMED_SNAPSHOT: return "The peer sent an invalid data snapshot.";
            case UNSUPPORTED_PROTOCOL: return "The peer uses an unsupported sync protocol version.";
            case INVALID_PAIRING_CODE: return "The six-digit pairing code was rejected.";
            case INVALID_DEVICE_ID: return "The peer device identifier is invalid.";
            case PEER_MISMATCH: return "The response did not come from the expected paired device.";
            case SNAPSHOT_PROVIDER_ERROR: return "Local data could not be read or updated.";
            case REMOTE_ERROR: return "The paired device rejected the sync request.";
            default: return "Device sync failed.";
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best effort during shutdown.
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best effort during shutdown.
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }

    public interface SnapshotProvider {
        /** Returns one complete snapshot JSON object. Called on an I/O worker. */
        String getSnapshot() throws Exception;

        /**
         * Applies canonical merged JSON. Missing apiKey fields must mean "leave the local secret
         * unchanged", because secrets are intentionally never included in sync snapshots.
         */
        void applyMergedSnapshot(String mergedSnapshot) throws Exception;
    }

    public interface Callback {
        default void onHostStarted(HostInfo hostInfo) {}

        /** Persist host, port and pairingCode here if sync should survive an app restart. */
        default void onPeerAvailable(Peer peer) {}

        default void onSyncComplete(String mergedSnapshot) {}

        default void onStopped() {}

        void onError(SyncError error);
    }

    public enum ErrorCode {
        INVALID_ARGUMENT,
        STOPPED,
        NOT_PAIRED,
        IO_ERROR,
        TIMEOUT,
        MESSAGE_TOO_LARGE,
        MALFORMED_MESSAGE,
        MALFORMED_SNAPSHOT,
        UNSUPPORTED_PROTOCOL,
        INVALID_PAIRING_CODE,
        INVALID_DEVICE_ID,
        PEER_MISMATCH,
        SNAPSHOT_PROVIDER_ERROR,
        REMOTE_ERROR
    }

    public static final class SyncException extends Exception {
        private final ErrorCode code;

        SyncException(ErrorCode code) {
            super(messageFor(code));
            this.code = code;
        }

        public ErrorCode getCode() {
            return code;
        }
    }

    public static final class SyncError {
        public final ErrorCode code;
        public final String message;

        SyncError(ErrorCode code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    public static final class HostInfo {
        public final int port;
        public final String pairingCode;
        public final String deviceId;

        HostInfo(int port, String pairingCode, String deviceId) {
            this.port = port;
            this.pairingCode = pairingCode;
            this.deviceId = deviceId;
        }
    }

    public static final class Peer {
        public final String host;
        public final int port;
        public final String pairingCode;
        public final String deviceId;

        public Peer(String host, int port, String pairingCode, String deviceId) {
            this.host = host;
            this.port = port;
            this.pairingCode = pairingCode;
            this.deviceId = deviceId;
        }
    }

    private static final class SyncThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "device-sync-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    /** Minimal strict JSON parser/writer kept here so local JVM tests need no Android JSON runtime. */
    private static final class Json {
        static Object parse(String source) throws SyncException {
            if (source == null) throw new SyncException(ErrorCode.MALFORMED_MESSAGE);
            Parser parser = new Parser(source);
            Object result = parser.readValue(0);
            parser.skipWhitespace();
            if (!parser.isAtEnd()) throw new SyncException(ErrorCode.MALFORMED_MESSAGE);
            return result;
        }

        static String stringify(Object value) throws SyncException {
            StringBuilder output = new StringBuilder();
            writeValue(output, value, 0);
            return output.toString();
        }

        private static void writeValue(StringBuilder output, Object value, int depth)
                throws SyncException {
            if (depth > MAX_JSON_DEPTH) throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
            if (value == null) {
                output.append("null");
            } else if (value instanceof String) {
                writeString(output, (String) value);
            } else if (value instanceof Boolean) {
                output.append(value);
            } else if (value instanceof BigDecimal) {
                BigDecimal decimal = ((BigDecimal) value).stripTrailingZeros();
                output.append(decimal.signum() == 0 ? "0" : decimal.toPlainString());
            } else if (value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long) {
                output.append(value);
            } else if (value instanceof Float || value instanceof Double) {
                double number = ((Number) value).doubleValue();
                if (!Double.isFinite(number)) throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
                output.append(BigDecimal.valueOf(number).stripTrailingZeros().toPlainString());
            } else if (value instanceof Map) {
                output.append('{');
                boolean first = true;
                Map<String, Object> sorted = new TreeMap<>(castMap(value));
                for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                    if (!first) output.append(',');
                    first = false;
                    writeString(output, entry.getKey());
                    output.append(':');
                    writeValue(output, entry.getValue(), depth + 1);
                }
                output.append('}');
            } else if (value instanceof List) {
                output.append('[');
                boolean first = true;
                for (Object item : castList(value)) {
                    if (!first) output.append(',');
                    first = false;
                    writeValue(output, item, depth + 1);
                }
                output.append(']');
            } else {
                throw new SyncException(ErrorCode.MALFORMED_SNAPSHOT);
            }
        }

        private static void writeString(StringBuilder output, String value) {
            output.append('"');
            for (int i = 0; i < value.length(); i++) {
                char character = value.charAt(i);
                switch (character) {
                    case '"': output.append("\\\""); break;
                    case '\\': output.append("\\\\"); break;
                    case '\b': output.append("\\b"); break;
                    case '\f': output.append("\\f"); break;
                    case '\n': output.append("\\n"); break;
                    case '\r': output.append("\\r"); break;
                    case '\t': output.append("\\t"); break;
                    default:
                        if (character < 0x20) {
                            output.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                        } else {
                            output.append(character);
                        }
                }
            }
            output.append('"');
        }

        private static final class Parser {
            private final String source;
            private int position;

            Parser(String source) {
                this.source = source;
            }

            Object readValue(int depth) throws SyncException {
                if (depth > MAX_JSON_DEPTH) throw malformed();
                skipWhitespace();
                if (isAtEnd()) throw malformed();
                char next = source.charAt(position);
                if (next == '{') return readObject(depth + 1);
                if (next == '[') return readArray(depth + 1);
                if (next == '"') return readString();
                if (next == 't') return readLiteral("true", Boolean.TRUE);
                if (next == 'f') return readLiteral("false", Boolean.FALSE);
                if (next == 'n') return readLiteral("null", null);
                if (next == '-' || (next >= '0' && next <= '9')) return readNumber();
                throw malformed();
            }

            Map<String, Object> readObject(int depth) throws SyncException {
                position++;
                skipWhitespace();
                Map<String, Object> result = new LinkedHashMap<>();
                if (consume('}')) return result;
                while (true) {
                    skipWhitespace();
                    if (isAtEnd() || source.charAt(position) != '"') throw malformed();
                    String key = readString();
                    if (result.containsKey(key)) throw malformed();
                    skipWhitespace();
                    if (!consume(':')) throw malformed();
                    result.put(key, readValue(depth));
                    skipWhitespace();
                    if (consume('}')) return result;
                    if (!consume(',')) throw malformed();
                }
            }

            List<Object> readArray(int depth) throws SyncException {
                position++;
                skipWhitespace();
                List<Object> result = new ArrayList<>();
                if (consume(']')) return result;
                while (true) {
                    result.add(readValue(depth));
                    skipWhitespace();
                    if (consume(']')) return result;
                    if (!consume(',')) throw malformed();
                }
            }

            String readString() throws SyncException {
                if (!consume('"')) throw malformed();
                StringBuilder result = new StringBuilder();
                while (!isAtEnd()) {
                    char character = source.charAt(position++);
                    if (character == '"') return result.toString();
                    if (character < 0x20) throw malformed();
                    if (character != '\\') {
                        result.append(character);
                        continue;
                    }
                    if (isAtEnd()) throw malformed();
                    char escaped = source.charAt(position++);
                    switch (escaped) {
                        case '"': result.append('"'); break;
                        case '\\': result.append('\\'); break;
                        case '/': result.append('/'); break;
                        case 'b': result.append('\b'); break;
                        case 'f': result.append('\f'); break;
                        case 'n': result.append('\n'); break;
                        case 'r': result.append('\r'); break;
                        case 't': result.append('\t'); break;
                        case 'u': result.append(readUnicodeEscape()); break;
                        default: throw malformed();
                    }
                }
                throw malformed();
            }

            char readUnicodeEscape() throws SyncException {
                if (position + 4 > source.length()) throw malformed();
                int value = 0;
                for (int i = 0; i < 4; i++) {
                    int digit = Character.digit(source.charAt(position++), 16);
                    if (digit < 0) throw malformed();
                    value = (value << 4) | digit;
                }
                return (char) value;
            }

            Object readLiteral(String literal, Object value) throws SyncException {
                if (!source.regionMatches(position, literal, 0, literal.length())) throw malformed();
                position += literal.length();
                return value;
            }

            BigDecimal readNumber() throws SyncException {
                int start = position;
                if (consume('-') && isAtEnd()) throw malformed();
                if (consume('0')) {
                    if (!isAtEnd() && Character.isDigit(source.charAt(position))) throw malformed();
                } else {
                    if (isAtEnd() || !isOneToNine(source.charAt(position))) throw malformed();
                    while (!isAtEnd() && Character.isDigit(source.charAt(position))) position++;
                }
                if (consume('.')) {
                    if (isAtEnd() || !Character.isDigit(source.charAt(position))) throw malformed();
                    while (!isAtEnd() && Character.isDigit(source.charAt(position))) position++;
                }
                if (!isAtEnd() && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
                    position++;
                    if (!isAtEnd() && (source.charAt(position) == '+' || source.charAt(position) == '-')) {
                        position++;
                    }
                    if (isAtEnd() || !Character.isDigit(source.charAt(position))) throw malformed();
                    while (!isAtEnd() && Character.isDigit(source.charAt(position))) position++;
                }
                try {
                    return new BigDecimal(source.substring(start, position));
                } catch (NumberFormatException invalid) {
                    throw malformed();
                }
            }

            void skipWhitespace() {
                while (!isAtEnd()) {
                    char character = source.charAt(position);
                    if (character != ' ' && character != '\n' && character != '\r' && character != '\t') return;
                    position++;
                }
            }

            boolean consume(char expected) {
                if (!isAtEnd() && source.charAt(position) == expected) {
                    position++;
                    return true;
                }
                return false;
            }

            boolean isAtEnd() {
                return position >= source.length();
            }

            boolean isOneToNine(char value) {
                return value >= '1' && value <= '9';
            }

            SyncException malformed() {
                return new SyncException(ErrorCode.MALFORMED_MESSAGE);
            }
        }
    }
}
