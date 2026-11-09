package com.hawson.timeassistant.oauth;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Browser navigation is optional; the validated callback is delivered before sending HTML. */
final class LoopbackAuthorization implements Closeable {
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    static final long WAIT_MILLIS = TimeUnit.MINUTES.toMillis(10);
    interface Callback { void received(URI callback) throws Exception; }
    private final ServerSocket server;
    private final String returnScheme;
    private final long waitMillis;
    final String redirectUri;

    LoopbackAuthorization(String applicationId) throws IOException { this(applicationId, 0, WAIT_MILLIS); }
    LoopbackAuthorization(String applicationId, int port, long waitMillis) throws IOException {
        this.waitMillis = waitMillis;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4);
        server.setSoTimeout((int) Math.max(1, waitMillis));
        redirectUri = "http://127.0.0.1:" + server.getLocalPort() + "/auth/callback";
        returnScheme = applicationId + ".oauth";
    }

    URI await(String expectedState) throws Exception { return await(expectedState, callback -> { }); }
    URI await(String expectedState, Callback accepted) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        while (System.nanoTime() < deadline) {
            server.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            try (Socket socket = server.accept()) {
                socket.setSoTimeout((int) Math.max(1, Math.min(1000,
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
                URI callback;
                try { callback = validCallback(readRequestTarget(socket.getInputStream()), expectedState); }
                catch (IOException malformed) { continue; }
                if (callback == null) {
                    try { writeResponse(socket, "404 Not Found", "Not found.", ""); }
                    catch (IOException disconnected) { }
                    continue;
                }
                accepted.received(callback);
                try { writeReturnPage(socket); } catch (IOException disconnected) { }
                return callback;
            }
        }
        throw new SocketTimeoutException("浏览器回调未收到");
    }
    private URI validCallback(String target, String expectedState) {
        if (target == null || !target.startsWith("/auth/callback")) return null;
        try {
            String origin = redirectUri.substring(0, redirectUri.indexOf("/auth/callback"));
            return OAuthCallbackValidator.validate(origin + target, redirectUri, expectedState);
        } catch (Exception ignored) { return null; }
    }
    private static String readRequestTarget(InputStream input) throws IOException {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int value, matched = 0;
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        while ((value = input.read()) != -1) {
            headers.write(value);
            if (headers.size() > MAX_HEADER_BYTES) throw new IOException("回调过大");
            matched = value == terminator[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
            if (matched == 4) break;
        }
        if (matched != 4) return null;
        String[] lines = new String(headers.toByteArray(), StandardCharsets.US_ASCII).split("\\r\\n", 2);
        String[] fields = lines[0].split(" ");
        if (fields.length != 3 || !"GET".equals(fields[0]) || !fields[2].startsWith("HTTP/")) return null;
        return fields[1];
    }
    private void writeReturnPage(Socket socket) throws IOException {
        String returnUri = returnScheme + "://return";
        String nonce = UUID.randomUUID().toString();
        String body = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>已收到授权</title><style>body{font-family:sans-serif;margin:0;display:grid;"
                + "min-height:100vh;place-items:center;background:#f5f7fb;color:#172033}.box{max-width:320px;"
                + "padding:32px;text-align:center}.button{display:block;padding:14px 20px;border-radius:24px;"
                + "background:#1677ff;color:#fff;text-decoration:none;font-weight:600}</style>"
                + "<main class=\"box\"><h1>已收到授权</h1><p>时屿正在完成登录，请返回查看结果。</p>"
                + "<a class=\"button\" href=\"" + returnUri + "\">返回时屿</a></main>"
                + "<script nonce=\"" + nonce + "\">setTimeout(function(){location.replace('"
                + returnUri + "')},150)</script></html>";
        writeResponse(socket, "200 OK", body, nonce);
    }
    private static void writeResponse(Socket socket, String status, String body, String nonce) throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n"
                + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-"
                + nonce + "'\r\nConnection: close\r\nContent-Length: " + content.length + "\r\n\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.US_ASCII)); output.write(content); output.flush();
    }
    @Override public void close() throws IOException { server.close(); }
    boolean isOpen() { return !server.isClosed(); }
}
