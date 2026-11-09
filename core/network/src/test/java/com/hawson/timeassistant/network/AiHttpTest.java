package com.hawson.timeassistant.network;

import com.hawson.timeassistant.ai.AiTransportPolicy;
import com.hawson.timeassistant.connections.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class AiHttpTest {
    @Test public void responseDecoderReceivesBoundedErrorAndPublicRequestId() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/failure", exchange -> {
            byte[] body = "fixture provider body".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("x-request-id", "req_fixture");
            exchange.sendResponseHeaders(401, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            new AiHttp(new AiTransportPolicy(origin -> true)).open(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/failure", "GET", "fixture-only",
                    null, null, new AiCancellation(), (status, raw, requestId) -> {
                        assertEquals("fixture provider body", raw); assertEquals("req_fixture", requestId);
                        return AiConnectionException.http(status, "token_expired", requestId);
                    }); fail();
        } catch (AiConnectionException expected) {
            assertTrue(expected.refreshable); assertFalse(expected.getMessage().contains("fixture provider body"));
        } finally { server.stop(0); }
    }
    @Test public void redirectNeverForwardsBearer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger forwarded = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/target", exchange -> { forwarded.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            new AiHttp(new AiTransportPolicy(origin -> true)).request(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect", "GET", "fixture-only",
                    null, null, new AiCancellation()); fail();
        } catch (AiConnectionException expected) { assertEquals(302, expected.httpStatus); }
        finally { server.stop(0); }
        assertEquals(0, forwarded.get());
    }
}
