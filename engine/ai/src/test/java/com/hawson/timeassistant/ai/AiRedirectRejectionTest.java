package com.hawson.timeassistant.ai;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real loopback transport test: a redirect must never forward credentials or request content. */
public class AiRedirectRejectionTest {
    @Test public void redirectResponseNeverReachesDestination() throws Exception {
        AtomicInteger forwardedRequests = new AtomicInteger();
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        String origin = "http://127.0.0.1:" + server.getLocalPort();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        Thread fixture = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8));
                    String requestLine = input.readLine();
                    int length = 0;
                    String header;
                    while ((header = input.readLine()) != null && !header.isEmpty()) {
                        if (header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                            length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
                        }
                    }
                    // The connection-test payload is ASCII; consume it before closing the socket.
                    for (int index = 0; index < length; index++) {
                        if (input.read() == -1) throw new IOException("Truncated request");
                    }
                    boolean destination = requestLine != null && requestLine.contains(" /leak ");
                    if (destination) forwardedRequests.incrementAndGet();
                    String response = destination ? "HTTP/1.1 200 OK\r\n"
                            : "HTTP/1.1 307 Temporary Redirect\r\nLocation: " + origin + "/leak\r\n";
                    socket.getOutputStream().write((response
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (SocketException closed) {
                    if (!server.isClosed()) serverFailure.set(closed);
                } catch (IOException failure) {
                    serverFailure.set(failure);
                    return;
                }
            }
        }, "AiRedirectFixture");
        fixture.setDaemon(true);
        fixture.start();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<AiClient.AiError> error = new AtomicReference<>();
        AtomicReference<AiClient.ConnectionResult> success = new AtomicReference<>();
        try (AiClient client = new AiClient(origin + "/v1", "test-secret", "test-model",
                Executors.newSingleThreadExecutor(), Runnable::run,
                new AiTransportPolicy(origin::equals))) {
            client.testConnection(new AiClient.Callback<AiClient.ConnectionResult>() {
                @Override public void onSuccess(AiClient.ConnectionResult result) {
                    success.set(result);
                    finished.countDown();
                }
                @Override public void onError(AiClient.AiError failure) {
                    error.set(failure);
                    finished.countDown();
                }
            });
            assertTrue("Request did not complete", finished.await(10, TimeUnit.SECONDS));
            assertNull(success.get());
            assertNotNull(error.get());
            assertTrue(error.get().getMessage().contains("重定向"));
            assertEquals(0, forwardedRequests.get());
            assertNull(serverFailure.get());
            assertFalse(error.get().getMessage().contains("test-secret"));
        } finally {
            server.close();
            fixture.join(2_000);
        }
    }
}
