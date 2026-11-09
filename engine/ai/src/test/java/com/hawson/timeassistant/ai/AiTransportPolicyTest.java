package com.hawson.timeassistant.ai;

import org.junit.Test;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class AiTransportPolicyTest {
    @Test public void publicHttpsDoesNotNeedException() {
        AiTransportPolicy.httpsOnly().requireAllowed("https://api.example.com/v1");
    }

    @Test public void publicHttpIsRejectedEvenWithUnrestrictedApproval() {
        AiTransportPolicy policy = new AiTransportPolicy(origin -> true);
        reject(policy, "http://8.8.8.8/v1");
        reject(policy, "http://example.com/v1");
        reject(policy, "http://localhost/v1");
        reject(policy, "http://169.254.169.254/v1");
        reject(policy, "http://[2001:4860:4860::8888]/v1");
    }

    @Test public void privateHttpRequiresExactIpAndPortConsent() {
        AiTransportPolicy policy = new AiTransportPolicy(
                origin -> origin.equals("http://192.168.1.162:18080"));
        policy.requireAllowed("http://192.168.1.162:18080/v1/chat/completions");
        reject(policy, "http://192.168.1.162:80/v1");
        reject(policy, "http://192.168.1.163:18080/v1");
        reject(AiTransportPolicy.httpsOnly(), "http://192.168.1.162:18080/v1");
    }

    @Test public void ambiguousIpLiteralsAndCredentialsAreRejected() {
        AiTransportPolicy policy = new AiTransportPolicy(origin -> true);
        for (String url : new String[]{"http://127.1/v1", "http://2130706433/v1",
                "http://0177.0.0.1/v1", "http://127.0.0.1.example.com/v1",
                "http://user:secret@127.0.0.1/v1", "http://127.0.0.1:65536/v1",
                "http://[::ffff:127.0.0.1]/v1", "http://[fe80::1%25eth0]/v1",
                "https://user:secret@example.com/v1", "ftp://127.0.0.1/v1"}) {
            reject(policy, url);
        }
    }

    @Test public void loopbackRfc1918AndUniqueLocalIpv6AreAllowedOnlyAfterConsent() {
        AiTransportPolicy policy = new AiTransportPolicy(origin -> true);
        for (String url : new String[]{"http://127.0.0.1/v1", "http://10.0.0.1/v1",
                "http://172.16.0.1/v1", "http://172.31.255.254/v1",
                "http://[::1]/v1", "http://[fd12:3456::1]:8080/v1"}) {
            policy.requireAllowed(url);
        }
        reject(policy, "http://172.32.0.1/v1");
    }

    @Test public void revocationIsCheckedOnEveryRequest() throws Exception {
        Set<String> grants = new HashSet<>();
        grants.add("http://127.0.0.1:80");
        AiTransportPolicy policy = new AiTransportPolicy(grants::contains);
        HttpURLConnection connection = policy.open(new URL("http://127.0.0.1/v1"));
        assertFalse(connection.getInstanceFollowRedirects());
        assertFalse(connection.usingProxy());
        connection.disconnect();
        grants.clear();
        reject(policy, "http://127.0.0.1/v1");
    }

    @Test public void httpsConnectionsAlsoDisableRedirectsAndProxy() throws Exception {
        HttpURLConnection connection = AiTransportPolicy.httpsOnly()
                .open(new URL("https://example.com/v1"));
        assertFalse(connection.getInstanceFollowRedirects());
        assertFalse(connection.usingProxy());
        connection.disconnect();
    }

    private static void reject(AiTransportPolicy policy, String url) {
        try {
            policy.requireAllowed(url);
            fail("Unsafe endpoint accepted: " + url);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
