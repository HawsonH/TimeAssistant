package com.hawson.timeassistant.oauth;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LoopbackAuthorizationTest {
    @Test public void receivesOnlyMatchingCallbackAndServesReturnPage() throws Exception {
        LoopbackAuthorization listener = new LoopbackAuthorization("com.example.timeassistant.qa");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<URI> returned = executor.submit(() -> listener.await("expected-state"));
            assertEquals(404, response(listener.redirectUri + "?code=one&state=wrong").code);

            Response response = response(listener.redirectUri + "?code=one&state=expected-state");
            assertEquals(200, response.code);
            assertTrue(response.body.contains("已收到授权"));
            assertTrue(response.body.contains("返回时屿"));
            assertTrue(response.body.contains("com.example.timeassistant.qa.oauth://return"));
            assertFalse(response.body.contains("code=one"));
            assertFalse(response.body.contains("expected-state"));
            assertFalse(response.body.contains("?callback="));

            URI callback = returned.get(5, TimeUnit.SECONDS);
            assertEquals("http", callback.getScheme());
            assertEquals("127.0.0.1", callback.getHost());
            assertEquals("/auth/callback", callback.getPath());
            assertEquals("expected-state", callback.getQuery().replaceFirst(".*state=", ""));
        } finally {
            listener.close();
            executor.shutdownNow();
        }
    }
    @Test public void idlePreconnectionDoesNotTerminateListener() throws Exception {
        LoopbackAuthorization listener = new LoopbackAuthorization("com.example.qa");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Socket idle = new Socket("127.0.0.1", URI.create(listener.redirectUri).getPort())) {
            Future<URI> returned = executor.submit(() -> listener.await("state"));
            assertEquals(200, response(listener.redirectUri + "?code=fixture&state=state").code);
            assertNotNull(returned.get(4, TimeUnit.SECONDS));
        } finally { listener.close(); executor.shutdownNow(); }
    }
    @Test public void deliversBeforeWritingBrowserPage() throws Exception {
        LoopbackAuthorization listener = new LoopbackAuthorization("com.example.qa");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicBoolean saved = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            Future<URI> returned = executor.submit(() -> listener.await("state", callback -> saved.set(true)));
            Response result = response(listener.redirectUri + "?code=fixture&state=state");
            assertTrue(saved.get()); assertEquals(200, result.code); assertNotNull(returned.get(2, TimeUnit.SECONDS));
        } finally { listener.close(); executor.shutdownNow(); }
    }
    @Test public void browserDisconnectDoesNotDiscardValidatedCallback() throws Exception {
        LoopbackAuthorization listener = new LoopbackAuthorization("com.example.qa");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch delivered = new CountDownLatch(1);
        try {
            Future<URI> returned = executor.submit(() -> listener.await("state", callback -> delivered.countDown()));
            try (Socket socket = new Socket("127.0.0.1", URI.create(listener.redirectUri).getPort())) {
                socket.setSoLinger(true, 0);
                socket.getOutputStream().write("GET /auth/callback?code=fixture&state=state HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                assertTrue(delivered.await(2, TimeUnit.SECONDS));
            }
            assertNotNull(returned.get(2, TimeUnit.SECONDS));
        } finally { listener.close(); executor.shutdownNow(); }
    }

    @Test public void listenerBindsToIpv4Loopback() throws Exception {
        try (LoopbackAuthorization listener = new LoopbackAuthorization("com.example.timeassistant.qa")) {
            URI redirect = URI.create(listener.redirectUri);
            assertEquals("127.0.0.1", redirect.getHost());
            assertEquals("/auth/callback", redirect.getPath());
            assertTrue(redirect.getPort() > 0);
        }
    }

    private static Response response(String value) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        int code = connection.getResponseCode();
        InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        byte[] bytes = input == null ? new byte[0] : input.readAllBytes();
        if (input != null) input.close();
        connection.disconnect();
        return new Response(code, new String(bytes, StandardCharsets.UTF_8));
    }

    private static final class Response {
        final int code;
        final String body;
        Response(int code, String body) { this.code = code; this.body = body; }
    }
}
