package com.hawson.timeassistant.quota.sub2api;

import com.hawson.timeassistant.ai.AiTransportPolicy;
import com.hawson.timeassistant.connections.AiCancellation;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class Sub2ApiQuotaPortTest {
    @Test public void readOnlyRequestUsesDedicatedSourceCredential() throws Exception {
        try (Fixture server = new Fixture(200, "{\"code\":0,\"data\":{\"account_id\":\"fixture\",\"rate_limit\":{}}}", "")) {
            assertEquals("fixture", client(server, false).read("fixture-account", new AiCancellation()).accountId);
            String request = server.request.get(3, TimeUnit.SECONDS).toLowerCase(java.util.Locale.ROOT);
            assertTrue(request.startsWith("get /api/v1/admin/openai/accounts/42/quota "));
            assertTrue(request.contains("x-api-key: independent-quota-fixture"));
            assertFalse(request.contains("authorization:")); assertFalse(request.contains("oauth"));
        }
    }
    @Test public void bearerIsOnlyTheDedicatedQuotaCredential() throws Exception {
        try (Fixture server = new Fixture(200, "{\"credits\":{\"balance\":\"12.34\"}}", "")) {
            assertEquals("12.34", client(server, true).read("fixture-account", new AiCancellation()).credits.balance);
            assertTrue(server.request.get(3, TimeUnit.SECONDS).contains("Authorization: Bearer independent-quota-fixture"));
        }
    }
    @Test public void publicHttpAndUnapprovedLocalOriginFailBeforeCredentialRead() throws Exception {
        AtomicInteger read = new AtomicInteger();
        for (String root : new String[]{"http://8.8.8.8", "http://127.0.0.1:12345"}) {
            Sub2ApiQuotaPort port = new Sub2ApiQuotaPort(AiTransportPolicy.httpsOnly(),
                    ref -> new Sub2ApiQuotaPort.Source(root, "42", false), ref -> { read.incrementAndGet(); return "fixture"; });
            try { port.read("fixture", new AiCancellation()); fail(); } catch (IllegalArgumentException expected) { }
        }
        assertEquals(0, read.get());
    }
    @Test public void redirectIsRejectedWithoutForwardingKey() throws Exception {
        try (Fixture target = new Fixture(200, "{}", "");
             Fixture source = new Fixture(307, "", "Location: " + target.root() + "/stolen\r\n")) {
            try { client(source, false).read("fixture", new AiCancellation()); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("重定向")); }
            source.request.get(3, TimeUnit.SECONDS);
            assertFalse(target.request.isDone());
        }
    }
    @Test public void providerFailureNeverSurfacesArbitraryResponseText() throws Exception {
        try (Fixture server = new Fixture(200, "{\"code\":403,\"message\":\"SECRET\",\"data\":{\"plan_type\":\"plus\"}}", "")) {
            try { client(server, false).read("fixture", new AiCancellation()); fail(); }
            catch (IOException expected) { assertFalse(expected.getMessage().contains("SECRET")); }
        }
    }
    @Test public void permissionFailureDoesNotRequestReauthorization() throws Exception {
        try (Fixture server = new Fixture(403, "SECRET", "")) {
            try { client(server, false).read("fixture", new AiCancellation()); fail(); }
            catch (IOException expected) {
                assertTrue(expected.getMessage().contains("不影响官方登录"));
                assertFalse(expected.getMessage().contains("SECRET"));
            }
        }
    }
    @Test public void cancellationAndInvalidAccountStopBeforeSecrets() throws Exception {
        AtomicInteger read = new AtomicInteger();
        Sub2ApiQuotaPort port = new Sub2ApiQuotaPort(AiTransportPolicy.httpsOnly(),
                ref -> new Sub2ApiQuotaPort.Source("https://example.com", "42/../1", false),
                ref -> { read.incrementAndGet(); return "fixture"; });
        try { port.read("fixture", new AiCancellation()); fail(); } catch (IllegalArgumentException expected) { }
        AiCancellation cancel = new AiCancellation(); cancel.cancel();
        try { port.read("fixture", cancel); fail(); } catch (InterruptedIOException expected) { }
        assertEquals(0, read.get());
    }
    @Test public void apiRootIsNotDuplicated() {
        assertEquals("https://example.com/api/v1/admin/openai/accounts/42/quota",
                Sub2ApiQuotaPort.endpoint(new Sub2ApiQuotaPort.Source("https://example.com/api/v1/", "42", false)));
    }
    @Test public void credentialLookupUsesTheResolvedSourceVersion() throws Exception {
        try (Fixture server = new Fixture(200, "{\"plan_type\":\"plus\"}", "")) {
            Sub2ApiQuotaPort port = new Sub2ApiQuotaPort(new AiTransportPolicy(origin -> origin.equals(server.root())),
                    ref -> new Sub2ApiQuotaPort.Source(server.root(), "42", false, "quota:version-1"),
                    ref -> { assertEquals("quota:version-1", ref); return "version-1-key"; });
            port.read("account", new AiCancellation());
            assertTrue(server.request.get(3, TimeUnit.SECONDS).contains("x-api-key: version-1-key"));
        }
    }
    @Test public void fractionalStatusIsNotSuccess() throws Exception {
        try (Fixture server = new Fixture(200, "{\"code\":0.1,\"data\":{\"plan_type\":\"plus\"}}", "")) {
            try { client(server, false).read("fixture", new AiCancellation()); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("未更新快照")); }
        }
    }
    private Sub2ApiQuotaPort client(Fixture fixture, boolean bearer) {
        return new Sub2ApiQuotaPort(new AiTransportPolicy(origin -> origin.equals(fixture.root())),
                ref -> new Sub2ApiQuotaPort.Source(fixture.root(), "42", bearer),
                ref -> "independent-quota-fixture");
    }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final CompletableFuture<String> request = new CompletableFuture<>();
        final Thread thread;
        Fixture(int status, String body, String headers) throws IOException {
            server.setSoTimeout(5000);
            thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder raw = new StringBuilder(); String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) raw.append(line).append('\n');
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 " + status + " Fixture\r\n" + headers
                            + "Content-Type: application/json\r\nConnection: close\r\nContent-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
                    request.complete(raw.toString());
                } catch (Exception failure) { request.completeExceptionally(failure); }
            }, "quota-loopback-fixture");
            thread.setDaemon(true); thread.start();
        }
        String root() { return "http://127.0.0.1:" + server.getLocalPort(); }
        public void close() throws Exception { server.close(); thread.join(3000); }
    }
}
