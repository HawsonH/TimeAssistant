/**
 * AI 对话客户端
 *
 * 通过 HttpURLConnection 调用 OpenAI 兼容接口，返回经校验的任务操作提案（不直接改数据）。
 */
package com.hawson.timeassistant.ai;

import android.os.Handler;
import android.os.Looper;
import com.hawson.timeassistant.connections.*;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLException;

/**
 * Minimal OpenAI-compatible chat client backed by {@link HttpURLConnection}.
 *
 * <p>Requests run on a private executor and callbacks are delivered on Android's main thread.
 * Returned actions are validated proposals only; this client never writes tasks or schedules.</p>
 */
public final class AiClient implements Closeable {
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 60_000;
    private static final int MAX_RESPONSE_CHARACTERS = 1_048_576;
    private static final int MAX_ERROR_DETAIL_CHARACTERS = 500;

    /**
     * Append-only response contract added to the caller's system prompt.
     * The action schemas intentionally match {@link AiPlan}'s strict validation.
     */
    public static final String PLAN_RESPONSE_INSTRUCTION =
            "Return exactly one JSON object and no prose. Required fields: "
                    + "message (non-empty string), needsClarification (boolean), actions (array). "
                    + "Optional field readRequests is an array of {requestId,type,args}. "
                    + "readRequests and actions are mutually exclusive. When clarification is "
                    + "needed, both must be empty. Use readRequests before guessing app data. "
                     + "Allowed action objects: "
                     + "create_task {type,title[,description,priority,estimatedMinutes,dueAt,reminderAt,countdownMinutes,repeatCountdown,"
                     + "project,tags,scheduledStartAt,scheduledEndAt,locked,subtasks,reason,summary]}, "
                     + "where subtasks is a non-empty array of title strings or objects with title; "
                     + "update_task {type,taskId,changes[,reason,summary]} where changes may use "
                     + "title,description,priority,estimatedMinutes,dueAt,reminderAt,countdownMinutes,repeatCountdown,project,tags,"
                     + "scheduledStartAt,scheduledEndAt,locked, but never status; "
                     + "delete_task/cancel_task {type,taskId[,reason,summary]}; "
                     + "complete_task {type,taskId[,actualDurationMinutes,reason,summary]}; "
                     + "schedule_task {type,taskId,startAt,durationMinutes[,locked,reason,summary]}; "
                     + "split_task {type,taskId,subtasks[,reason,summary]}; "
                     + "start_focus {type,taskId[,durationMinutes]}; "
                     + "set_greeting {type,text[,reason,summary]} where text is at most 32 characters. "
                     + "create_recurring_group {type,title,startAt,durationMinutes,unit,interval"
                     + "[,description,workdaysOnly,weekdays,monthlyOrdinal,monthlyWeekday,endsAt,maxOccurrences]}; "
                     + "update_recurring_group {type,groupId,changes}; "
                     + "stop_recurring_group {type,groupId}; "
                      + "set_recurring_override {type,groupId,occurrenceAt,operation"
                      + "[,title,description,startAt,endAt]}; "
                      + "create_preference {type,preferenceType,scopeType,startMinute,endMinute"
                      + "[,scopeValue,daysOfWeek,reason]}; update_preference "
                      + "{type,preferenceId,changes}; delete_preference/activate_preference "
                      + "{type,preferenceId}; "
                      + "Use countdownMinutes for relative reminders, starting only when the user confirms. "
                      + "Use reminderAt for an absolute reminder; dueAt is a deadline, not a reminder request. "
                      + "Do not combine reminderAt with countdownMinutes or repeating reminders. "
                      + "Every absolute time field (including reminderAt, dueAt, scheduledStartAt, "
                      + "scheduledEndAt, startAt, endAt, occurrenceAt, and endsAt) must be an "
                      + "integer epoch-millisecond value. Never return a timezone-less date/time "
                      + "string. If a string is unavoidable, it must be ISO-8601 with Z or an "
                      + "explicit offset such as 2026-08-12T17:30:00+08:00. "
                      + "When creating and splitting a new task in one proposal, embed subtasks "
                     + "inside create_task; never emit split_task for a task that does not exist yet. "
                     + "Use complete_task or cancel_task for terminal status changes. "
                     + "Actions are proposals and will not run until the user confirms them.";

    private volatile Configuration configuration;
    private final ExecutorService executor;
    private final CallbackDispatcher callbackDispatcher;
    private final AiTransportPolicy transportPolicy;
    private final Set<RequestHandle> activeRequests = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private AiPort port;
    private AiProfile profile;

    /** Production facade: callers receive no credentials and cannot replace authentication. */
    public AiClient(AiPort port, AiProfile profile) {
        this.configuration = new Configuration(profile.baseUrl, "", profile.model);
        this.executor = Executors.newSingleThreadExecutor();
        this.callbackDispatcher = new MainThreadDispatcher();
        this.transportPolicy = AiTransportPolicy.httpsOnly();
        this.port = Objects.requireNonNull(port);
        this.profile = Objects.requireNonNull(profile);
    }

    public AiClient(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, Executors.newSingleThreadExecutor(), new MainThreadDispatcher());
    }

    public AiClient(String baseUrl, String apiKey, String model, AiTransportPolicy policy) {
        this(baseUrl, apiKey, model, Executors.newSingleThreadExecutor(),
                new MainThreadDispatcher(), policy);
    }

    /**
     * Injectable constructor for deterministic tests. The client owns and shuts down the supplied
     * executor when {@link #close()} is called.
     */
    public AiClient(String baseUrl, String apiKey, String model, ExecutorService executor,
                    CallbackDispatcher callbackDispatcher) {
        this(baseUrl, apiKey, model, executor, callbackDispatcher, AiTransportPolicy.httpsOnly());
    }

    public AiClient(String baseUrl, String apiKey, String model, ExecutorService executor,
                    CallbackDispatcher callbackDispatcher, AiTransportPolicy policy) {
        this.configuration = Configuration.create(baseUrl, apiKey, model);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.callbackDispatcher = Objects.requireNonNull(callbackDispatcher, "callbackDispatcher");
        this.transportPolicy = Objects.requireNonNull(policy, "policy");
    }

    /** New requests immediately use this configuration; already-running requests keep a snapshot. */
    public void updateConfiguration(String baseUrl, String apiKey, String model) {
        ensureOpen();
        configuration = Configuration.create(baseUrl, apiKey, model);
    }

    public String getBaseUrl() {
        return configuration.baseUrl;
    }

    public String getModel() {
        return configuration.model;
    }

    /** The API key is intentionally not exposed through a getter. */
    public String getChatCompletionsUrl() {
        return buildChatCompletionsUrl(configuration.baseUrl);
    }

    /**
     * Trims whitespace/trailing slashes, accepts a host without a scheme as HTTPS, and accepts a
     * full /chat/completions or /models URL by reducing it to the API base URL.
     */
    public static String normalizeBaseUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Base URL 不能为空");
        }
        String candidate = value.trim();
        if (!candidate.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*$")) {
            candidate = "https://" + candidate;
        }

        final URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Base URL 格式无效", error);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Base URL 只支持 http 或 https");
        }
        if (uri.getHost() == null || uri.getHost().trim().isEmpty()) {
            throw new IllegalArgumentException("Base URL 缺少有效主机名");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Base URL 不能包含用户名或密码");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Base URL 不能包含查询参数或片段");
        }

        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/") && !path.isEmpty()) path = path.substring(0, path.length() - 1);
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith("/chat/completions")) {
            path = path.substring(0, path.length() - "/chat/completions".length());
        } else if (lowerPath.endsWith("/models")) {
            path = path.substring(0, path.length() - "/models".length());
        }
        while (path.endsWith("/") && !path.isEmpty()) path = path.substring(0, path.length() - 1);

        String authority = uri.getRawAuthority();
        return scheme + "://" + authority + path;
    }

    public static String buildChatCompletionsUrl(String baseUrl) {
        return normalizeBaseUrl(baseUrl) + "/chat/completions";
    }

    /** Performs a real minimal chat request so Base URL, API key, and model are all exercised. */
    public Request testConnection(Callback<ConnectionResult> callback) {
        Objects.requireNonNull(callback, "callback");
        if (port != null) {
            return submit(handle -> {
                long start = System.nanoTime();
                String text = callPort(new AiRequest("This is a connection test. Reply with exactly OK.",
                        Collections.singletonList(new AiRequest.Message("user", "OK", Collections.emptyList()))), handle);
                if (text.trim().isEmpty()) throw AiError.parse("模型返回空内容", null);
                return new ConnectionResult(200, profile.model, (System.nanoTime() - start) / 1_000_000,
                        "连接成功，模型已完整响应");
            }, callback);
        }
        Configuration snapshot = configuration;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", snapshot.model);
        List<Object> messages = new ArrayList<>();
        messages.add(messageObject("system", "This is a connection test. Reply with exactly OK."));
        messages.add(messageObject("user", "OK"));
        request.put("messages", messages);
        String requestBody = MiniJson.stringify(request);

        return submit(handle -> {
            long startedAtNanos = System.nanoTime();
            HttpResponse response = post(snapshot, requestBody, handle);
            String assistantText;
            try {
                assistantText = extractAssistantContent(response.body);
            } catch (AiPlan.ParseException error) {
                throw AiError.parse("连接已建立，但响应格式无法解析：" + error.getMessage(), error);
            }
            if (assistantText.trim().isEmpty()) {
                throw AiError.parse("连接已建立，但模型返回了空内容", null);
            }
            long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;
            return new ConnectionResult(response.statusCode, snapshot.model, elapsedMillis,
                    "连接成功，模型已正常响应");
        }, callback);
    }

    /**
     * Sends one system message followed by user/assistant history and parses a validated plan.
     */
    public Request chat(String systemPrompt, List<Message> history, Callback<AiPlan> callback) {
        Objects.requireNonNull(callback, "callback");
        if (systemPrompt == null || systemPrompt.trim().isEmpty()) {
            throw new IllegalArgumentException("systemPrompt 不能为空");
        }
        if (history == null || history.isEmpty()) {
            throw new IllegalArgumentException("history 不能为空");
        }
        List<Message> historySnapshot = Collections.unmodifiableList(new ArrayList<>(history));
        boolean hasUserMessage = false;
        for (Message message : historySnapshot) {
            if (message == null) throw new IllegalArgumentException("history 不能包含 null");
            if (Message.ROLE_USER.equals(message.role)) hasUserMessage = true;
        }
        if (!hasUserMessage) throw new IllegalArgumentException("history 至少需要一条 user 消息");

        if (port != null) {
            List<AiRequest.Message> messages = new ArrayList<>();
            for (Message message : historySnapshot) {
                List<AiRequest.Part> parts = new ArrayList<>();
                for (Attachment attachment : message.attachments) {
                    parts.add(new AiRequest.Part(attachment.fileName, attachment.mimeType, attachment.base64Data));
                }
                messages.add(new AiRequest.Message(message.role, message.content, parts));
            }
            AiRequest request = new AiRequest(systemPrompt.trim() + "\n\n" + PLAN_RESPONSE_INSTRUCTION, messages);
            return submit(handle -> {
                try { return AiPlan.parse(callPort(request, handle)); }
                catch (AiPlan.ParseException error) { throw AiError.parse("AI 提案格式无效，未执行任何操作", error); }
            }, callback);
        }

        Configuration snapshot = configuration;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", snapshot.model);
        List<Object> messages = new ArrayList<>(historySnapshot.size() + 1);
        String effectiveSystemPrompt = systemPrompt.trim() + "\n\n" + PLAN_RESPONSE_INSTRUCTION;
        messages.add(messageObject("system", effectiveSystemPrompt));
        for (Message message : historySnapshot) {
            messages.add(messageObject(message));
        }
        request.put("messages", messages);
        String requestBody = MiniJson.stringify(request);

        return submit(handle -> {
            HttpResponse response = post(snapshot, requestBody, handle);
            try {
                return parseChatResponse(response.body);
            } catch (AiPlan.ParseException error) {
                throw AiError.parse("AI 响应解析失败：" + error.getMessage(), error);
            }
        }, callback);
    }

    /** Parses an OpenAI-compatible choices[0].message.content response into a validated plan. */
    public static AiPlan parseChatResponse(String responseJson) throws AiPlan.ParseException {
        return AiPlan.parse(extractAssistantContent(responseJson));
    }

    static String extractAssistantContent(String responseJson) throws AiPlan.ParseException {
        final MiniJson.Document document;
        try {
            document = MiniJson.parse(responseJson);
        } catch (MiniJson.JsonException error) {
            throw new AiPlan.ParseException("响应不是有效 JSON：" + error.getMessage(), error);
        }
        Map<String, MiniJson.Node> root = asObject(document.root, "响应");
        MiniJson.Node choicesNode = root.get("choices");
        List<MiniJson.Node> choices = asArray(choicesNode, "响应.choices");
        if (choices.isEmpty()) throw new AiPlan.ParseException("响应.choices 不能为空");
        Map<String, MiniJson.Node> firstChoice = asObject(choices.get(0), "响应.choices[0]");
        Map<String, MiniJson.Node> message = asObject(firstChoice.get("message"),
                "响应.choices[0].message");
        MiniJson.Node contentNode = message.get("content");
        if (contentNode == null) throw new AiPlan.ParseException("响应缺少 choices[0].message.content");

        if (contentNode.value instanceof String) {
            String content = (String) contentNode.value;
            if (content.trim().isEmpty()) throw new AiPlan.ParseException("message.content 为空");
            return content;
        }
        if (!(contentNode.value instanceof List)) {
            throw new AiPlan.ParseException("message.content 必须是字符串或内容数组");
        }

        @SuppressWarnings("unchecked")
        List<MiniJson.Node> parts = (List<MiniJson.Node>) contentNode.value;
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < parts.size(); index++) {
            MiniJson.Node part = parts.get(index);
            String text = textFromContentPart(part, index);
            if (text == null || text.isEmpty()) continue;
            if (content.length() > 0) content.append('\n');
            content.append(text);
        }
        if (content.toString().trim().isEmpty()) {
            throw new AiPlan.ParseException("message.content 数组不包含文本");
        }
        return content.toString();
    }

    private static String textFromContentPart(MiniJson.Node part, int index)
            throws AiPlan.ParseException {
        if (part.value instanceof String) return (String) part.value;
        if (!(part.value instanceof Map)) return null;
        Map<String, MiniJson.Node> object = asObject(part, "message.content[" + index + "]");
        MiniJson.Node text = object.get("text");
        if (text == null) return null;
        if (text.value instanceof String) return (String) text.value;
        if (text.value instanceof Map) {
            Map<String, MiniJson.Node> textObject = asObject(text,
                    "message.content[" + index + "].text");
            MiniJson.Node value = textObject.get("value");
            if (value != null && value.value instanceof String) return (String) value.value;
        }
        throw new AiPlan.ParseException("message.content[" + index + "].text 格式无效");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MiniJson.Node> asObject(MiniJson.Node node, String context)
            throws AiPlan.ParseException {
        if (node == null || !(node.value instanceof Map)) {
            throw new AiPlan.ParseException(context + " 必须是 JSON 对象");
        }
        return (Map<String, MiniJson.Node>) node.value;
    }

    @SuppressWarnings("unchecked")
    private static List<MiniJson.Node> asArray(MiniJson.Node node, String context)
            throws AiPlan.ParseException {
        if (node == null || !(node.value instanceof List)) {
            throw new AiPlan.ParseException(context + " 必须是 JSON 数组");
        }
        return (List<MiniJson.Node>) node.value;
    }

    private static Map<String, Object> messageObject(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    static Map<String, Object> messageObject(Message source) {
        if (source.attachments.isEmpty()) return messageObject(source.role, source.content);

        List<Object> content = new ArrayList<>();
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", source.content.trim().isEmpty()
                ? "请阅读随消息发送的附件。" : source.content);
        content.add(text);

        for (Attachment attachment : source.attachments) {
            if (attachment.mimeType.startsWith("image/")) {
                Map<String, Object> imageUrl = new LinkedHashMap<>();
                imageUrl.put("url", attachment.dataUrl());
                imageUrl.put("detail", "auto");
                Map<String, Object> imagePart = new LinkedHashMap<>();
                imagePart.put("type", "image_url");
                imagePart.put("image_url", imageUrl);
                content.add(imagePart);
            } else {
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("filename", attachment.fileName);
                file.put("file_data", attachment.dataUrl());
                Map<String, Object> filePart = new LinkedHashMap<>();
                filePart.put("type", "file");
                filePart.put("file", file);
                content.add(filePart);
            }
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", source.role);
        message.put("content", content);
        return message;
    }

    private HttpResponse post(Configuration config, String body, RequestHandle handle) throws AiError {
        HttpURLConnection connection = null;
        try {
            URL endpoint = new URL(buildChatCompletionsUrl(config.baseUrl));
            connection = transportPolicy.open(endpoint);
            handle.attach(connection);
            if (handle.isCancelled()) throw AiError.cancelled();

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }

            int statusCode = connection.getResponseCode();
            if (statusCode >= 300 && statusCode < 400) {
                throw AiError.network("AI 服务返回重定向，已拒绝向其他地址转发请求", null);
            }
            InputStream stream = statusCode >= 200 && statusCode < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String responseBody = readBody(stream);
            if (statusCode < 200 || statusCode >= 300) {
                throw httpError(statusCode, responseBody, config);
            }
            if (responseBody.trim().isEmpty()) {
                throw AiError.parse("HTTP " + statusCode + " 成功，但响应内容为空", null);
            }
            return new HttpResponse(statusCode, responseBody);
        } catch (AiError error) {
            throw error;
        } catch (UnknownHostException error) {
            throw AiError.network("无法解析服务地址，请检查 Base URL 或网络连接", error);
        } catch (ConnectException error) {
            throw AiError.network("无法连接 AI 服务，请检查地址、端口或网络", error);
        } catch (java.net.SocketTimeoutException error) {
            throw AiError.network("连接或响应超时，请稍后重试", error);
        } catch (SSLException error) {
            throw AiError.network("TLS/证书连接失败，请检查服务地址与证书", error);
        } catch (IOException error) {
            if (handle.isCancelled()) throw AiError.cancelled();
            throw AiError.network("网络请求失败，请检查网络后重试", error);
        } catch (IllegalArgumentException error) {
            throw AiError.network("AI 地址安全校验失败：" + sanitize(error.getMessage(), config.apiKey), error);
        } catch (RuntimeException error) {
            if (handle.isCancelled()) throw AiError.cancelled();
            throw AiError.network("建立网络请求失败，请检查 Base URL", error);
        } finally {
            handle.detach(connection);
            if (connection != null) connection.disconnect();
        }
    }

    private String callPort(AiRequest request, RequestHandle handle) throws AiError {
        try { return port.complete(profile, request, handle.portCancellation); }
        catch (Exception error) {
            if (handle.isCancelled()) throw AiError.cancelled();
            if (error instanceof com.hawson.timeassistant.connections.AiConnectionException) {
                throw AiError.connection((com.hawson.timeassistant.connections.AiConnectionException) error);
            }
            if (error instanceof java.net.SocketTimeoutException) {
                throw AiError.connection(new com.hawson.timeassistant.connections.AiConnectionException(
                        com.hawson.timeassistant.connections.AiConnectionException.Status.TIMEOUT,
                        "连接或响应超时，登录信息已保留，请稍后重试"));
            }
            if (error instanceof IOException) throw AiError.network("暂时无法连接 AI 服务，请检查网络后重试", null);
            throw AiError.parse("AI 连接或响应格式未准备好，请检查模型设置后重试", null);
        }
    }

    private static String readBody(InputStream stream) throws IOException, AiError {
        if (stream == null) return "";
        StringBuilder value = new StringBuilder();
        char[] buffer = new char[4096];
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (value.length() + count > MAX_RESPONSE_CHARACTERS) {
                    throw AiError.parse("AI 响应超过 1 MiB，已拒绝解析", null);
                }
                value.append(buffer, 0, count);
            }
        }
        return value.toString();
    }

    private static AiError httpError(int statusCode, String responseBody, Configuration config) {
        String reason;
        switch (statusCode) {
            case 400:
                reason = "请求参数或 Model 不兼容";
                break;
            case 401:
            case 403:
                reason = "认证失败，请检查 API Key 与访问权限";
                break;
            case 404:
                reason = "接口不存在，请检查 Base URL（通常应指向 /v1）";
                break;
            case 408:
                reason = "服务端等待请求超时";
                break;
            case 429:
                reason = "请求过于频繁或额度不足";
                break;
            default:
                reason = statusCode >= 500 ? "AI 服务暂时不可用" : "AI 服务拒绝了请求";
        }
        String providerDetail = extractProviderError(responseBody);
        providerDetail = sanitize(providerDetail, config.apiKey);
        String message = "HTTP " + statusCode + "：" + reason;
        if (!providerDetail.isEmpty()) message += "（" + providerDetail + "）";
        return AiError.http(statusCode, message);
    }

    private static String extractProviderError(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) return "";
        try {
            MiniJson.Document document = MiniJson.parse(responseBody);
            if (document.root.value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, MiniJson.Node> root = (Map<String, MiniJson.Node>) document.root.value;
                MiniJson.Node errorNode = root.get("error");
                if (errorNode != null && errorNode.value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, MiniJson.Node> error = (Map<String, MiniJson.Node>) errorNode.value;
                    MiniJson.Node message = error.get("message");
                    if (message != null && message.value instanceof String) return (String) message.value;
                }
                MiniJson.Node message = root.get("message");
                if (message != null && message.value instanceof String) return (String) message.value;
            }
        } catch (MiniJson.JsonException ignored) {
            // A short sanitized raw excerpt is still more useful than hiding the provider's error.
        }
        return responseBody.replace('\n', ' ').replace('\r', ' ').trim();
    }

    static String sanitize(String value, String apiKey) {
        if (value == null) return "";
        String sanitized = value;
        if (apiKey != null && !apiKey.isEmpty()) sanitized = sanitized.replace(apiKey, "[REDACTED]");
        sanitized = sanitized.replaceAll("(?i)Bearer\\s+[^\\s,;\"']+", "Bearer [REDACTED]");
        if (sanitized.length() > MAX_ERROR_DETAIL_CHARACTERS) {
            sanitized = sanitized.substring(0, MAX_ERROR_DETAIL_CHARACTERS) + "…";
        }
        return sanitized;
    }

    private <T> Request submit(RequestWork<T> work, Callback<T> callback) {
        ensureOpen();
        RequestHandle handle = new RequestHandle();
        activeRequests.add(handle);
        try {
            Future<?> future = executor.submit(() -> {
                try {
                    if (handle.isCancelled() || closed.get()) return;
                    T result = work.run(handle);
                    dispatchSuccess(handle, callback, result);
                } catch (AiError error) {
                    if (!handle.isCancelled() && error.kind != AiError.Kind.CANCELLED) {
                        dispatchError(handle, callback, error);
                    }
                } catch (RuntimeException error) {
                    if (!handle.isCancelled()) {
                        dispatchError(handle, callback,
                                AiError.parse("处理 AI 响应时发生内部错误", error));
                    }
                } finally {
                    activeRequests.remove(handle);
                    handle.markFinished();
                }
            });
            handle.attach(future);
        } catch (RejectedExecutionException error) {
            activeRequests.remove(handle);
            handle.markFinished();
            dispatchError(handle, callback, AiError.closed());
        }
        return handle;
    }

    private <T> void dispatchSuccess(RequestHandle handle, Callback<T> callback, T result) {
        callbackDispatcher.dispatch(() -> {
            if (!handle.isCancelled() && !closed.get()) callback.onSuccess(result);
        });
    }

    private <T> void dispatchError(RequestHandle handle, Callback<T> callback, AiError error) {
        callbackDispatcher.dispatch(() -> {
            if (!handle.isCancelled() && !closed.get()) callback.onError(error);
        });
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("AiClient 已关闭");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (RequestHandle request : activeRequests) request.cancel();
        activeRequests.clear();
        executor.shutdownNow();
    }

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(AiError error);
    }

    /** Abstraction used to deliver callbacks; the default implementation posts to the main Looper. */
    public interface CallbackDispatcher {
        void dispatch(Runnable callback);
    }

    public interface Request extends AutoCloseable {
        void cancel();
        boolean isCancelled();

        @Override
        default void close() {
            cancel();
        }
    }

    public static final class Message {
        public static final String ROLE_USER = "user";
        public static final String ROLE_ASSISTANT = "assistant";

        public final String role;
        public final String content;
        public final List<Attachment> attachments;

        public Message(String role, String content) {
            this(role, content, Collections.emptyList());
        }

        public Message(String role, String content, List<Attachment> attachments) {
            if (!ROLE_USER.equals(role) && !ROLE_ASSISTANT.equals(role)) {
                throw new IllegalArgumentException("history role 只支持 user 或 assistant");
            }
            List<Attachment> safeAttachments = attachments == null
                    ? Collections.emptyList() : new ArrayList<>(attachments);
            if ((content == null || content.trim().isEmpty()) && safeAttachments.isEmpty()) {
                throw new IllegalArgumentException("消息内容不能为空");
            }
            if (ROLE_ASSISTANT.equals(role) && !safeAttachments.isEmpty()) {
                throw new IllegalArgumentException("assistant 消息不能包含本地附件");
            }
            for (Attachment attachment : safeAttachments) {
                if (attachment == null) throw new IllegalArgumentException("附件不能为 null");
            }
            this.role = role;
            this.content = content == null ? "" : content;
            this.attachments = Collections.unmodifiableList(safeAttachments);
        }

        public static Message user(String content) {
            return new Message(ROLE_USER, content);
        }

        public static Message assistant(String content) {
            return new Message(ROLE_ASSISTANT, content);
        }

        public static Message userWithAttachments(String content, List<Attachment> attachments) {
            return new Message(ROLE_USER, content, attachments);
        }
    }

    /** Base64-backed attachment for OpenAI-compatible multimodal chat message content. */
    public static final class Attachment {
        public final String fileName;
        public final String mimeType;
        public final String base64Data;

        public Attachment(String fileName, String mimeType, String base64Data) {
            String safeName = fileName == null ? "attachment" : fileName.trim();
            String safeMime = mimeType == null ? "application/octet-stream" : mimeType.trim();
            String safeData = base64Data == null ? "" : base64Data.trim();
            if (safeName.isEmpty()) safeName = "attachment";
            if (safeName.indexOf('\r') >= 0 || safeName.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("附件名称包含非法换行");
            }
            if (safeMime.isEmpty() || safeMime.indexOf('/') <= 0) {
                safeMime = "application/octet-stream";
            }
            if (safeData.isEmpty()) throw new IllegalArgumentException("附件内容不能为空");
            this.fileName = safeName;
            this.mimeType = safeMime;
            this.base64Data = safeData;
        }

        String dataUrl() {
            return "data:" + mimeType + ";base64," + base64Data;
        }
    }

    public static final class ConnectionResult {
        public final int httpStatus;
        public final String model;
        public final long elapsedMillis;
        public final String message;

        ConnectionResult(int httpStatus, String model, long elapsedMillis, String message) {
            this.httpStatus = httpStatus;
            this.model = model;
            this.elapsedMillis = elapsedMillis;
            this.message = message;
        }
    }

    public static final class AiError extends Exception {
        public enum Kind { CONFIGURATION, HTTP, NETWORK, PARSE, CANCELLED }

        public final Kind kind;
        public final int httpStatus;
        public final com.hawson.timeassistant.connections.AiConnectionException.Status connectionStatus;

        private AiError(Kind kind, int httpStatus, String message, Throwable cause) {
            this(kind, httpStatus, message, cause, null);
        }
        private AiError(Kind kind, int httpStatus, String message, Throwable cause,
                        com.hawson.timeassistant.connections.AiConnectionException.Status connectionStatus) {
            super(message, cause);
            this.kind = kind;
            this.httpStatus = httpStatus;
            this.connectionStatus = connectionStatus;
        }
        static AiError connection(com.hawson.timeassistant.connections.AiConnectionException error) {
            return new AiError(error.httpStatus > 0 ? Kind.HTTP : Kind.CONFIGURATION, error.httpStatus,
                    error.getMessage(), null, error.status);
        }

        static AiError http(int statusCode, String message) {
            return new AiError(Kind.HTTP, statusCode, message, null);
        }

        static AiError network(String message, Throwable cause) {
            return new AiError(Kind.NETWORK, -1, message, cause);
        }

        public static AiError parse(String message, Throwable cause) {
            return new AiError(Kind.PARSE, -1, message, cause);
        }

        static AiError cancelled() {
            return new AiError(Kind.CANCELLED, -1, "请求已取消", null);
        }

        static AiError closed() {
            return new AiError(Kind.CONFIGURATION, -1, "AI 客户端已关闭", null);
        }

        public Kind getKind() {
            return kind;
        }

        public int getHttpStatus() {
            return httpStatus;
        }
    }

    private interface RequestWork<T> {
        T run(RequestHandle handle) throws AiError;
    }

    private static final class Configuration {
        final String baseUrl;
        final String apiKey;
        final String model;

        private Configuration(String baseUrl, String apiKey, String model) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
        }

        static Configuration create(String baseUrl, String apiKey, String model) {
            String normalizedUrl = normalizeBaseUrl(baseUrl);
            if (apiKey == null || apiKey.trim().isEmpty()) {
                throw new IllegalArgumentException("API Key 不能为空");
            }
            if (apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("API Key 包含非法换行");
            }
            if (model == null || model.trim().isEmpty()) {
                throw new IllegalArgumentException("Model 不能为空");
            }
            if (model.indexOf('\r') >= 0 || model.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Model 包含非法换行");
            }
            return new Configuration(normalizedUrl, apiKey.trim(), model.trim());
        }
    }

    private static final class HttpResponse {
        final int statusCode;
        final String body;

        HttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private final class RequestHandle implements Request {
        private final AiCancellation portCancellation = new AiCancellation();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile Future<?> future;
        private volatile HttpURLConnection connection;

        void attach(Future<?> future) {
            this.future = future;
            if (cancelled.get()) future.cancel(true);
        }

        void attach(HttpURLConnection connection) {
            this.connection = connection;
            if (cancelled.get()) connection.disconnect();
        }

        void detach(HttpURLConnection connection) {
            if (this.connection == connection) this.connection = null;
        }

        void markFinished() {
            finished.set(true);
            connection = null;
        }

        @Override
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            portCancellation.cancel();
            activeRequests.remove(this);
            HttpURLConnection currentConnection = connection;
            if (currentConnection != null) currentConnection.disconnect();
            Future<?> currentFuture = future;
            if (currentFuture != null) currentFuture.cancel(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @SuppressWarnings("unused")
        boolean isFinished() {
            return finished.get();
        }
    }

    private static final class MainThreadDispatcher implements CallbackDispatcher {
        private final Looper mainLooper = Looper.getMainLooper();
        private final Handler handler = new Handler(mainLooper);

        @Override
        public void dispatch(Runnable callback) {
            if (Looper.myLooper() == mainLooper) callback.run();
            else handler.post(callback);
        }
    }
}
