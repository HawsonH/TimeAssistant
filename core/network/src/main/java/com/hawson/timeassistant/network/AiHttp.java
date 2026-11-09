package com.hawson.timeassistant.network;

import com.hawson.timeassistant.ai.AiTransportPolicy;
import com.hawson.timeassistant.connections.AiCancellation;
import com.hawson.timeassistant.connections.AiConnectionException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class AiHttp {
    public interface ErrorDecoder { AiConnectionException decode(int status, String body, String requestId); }
    public static final int LIMIT = 2 * 1024 * 1024;
    private final AiTransportPolicy policy;
    public AiHttp(AiTransportPolicy policy) { this.policy = policy; }

    public HttpURLConnection open(String url, String method, String bearer,
                                  String body, String contentType, AiCancellation cancel)
            throws IOException {
        return open(url, method, bearer, body, contentType, cancel,
                (status, ignored, requestId) -> AiConnectionException.http(status, "", requestId));
    }
    public HttpURLConnection open(String url, String method, String bearer,
                                  String body, String contentType, AiCancellation cancel,
                                  ErrorDecoder errors) throws IOException {
        cancel.check();
        HttpURLConnection connection = policy.open(new URL(url));
        cancel.onCancel(connection::disconnect);
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            if (bearer != null && !bearer.isEmpty()) {
                if (bearer.contains("\r") || bearer.contains("\n")) throw new IOException("无效凭据");
                connection.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 24 * 1024 * 1024) throw new IOException("请求过大");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream stream = connection.getOutputStream()) { stream.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String errorBody = "";
                try (InputStream stream = connection.getErrorStream()) {
                    if (stream != null) errorBody = read(stream, 64 * 1024);
                } catch (IOException unreadable) { }
                String requestId = connection.getHeaderField("x-request-id");
                if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,120}")) requestId = "";
                // The decoder retains only recognized codes, never the raw provider message.
                throw errors.decode(status, errorBody, requestId);
            }
            return connection;
        } catch (IOException | RuntimeException failure) {
            connection.disconnect(); throw failure;
        }
    }

    public String request(String url, String method, String bearer, String body,
                          String contentType, AiCancellation cancel) throws IOException {
        HttpURLConnection connection = open(url, method, bearer, body, contentType, cancel);
        try { return read(connection.getInputStream(), LIMIT); }
        finally { connection.disconnect(); }
    }
    public static String read(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("响应超过大小限制");
            output.write(buffer, 0, count);
        }
        return output.toString("UTF-8");
    }
}
