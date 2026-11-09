package com.hawson.timeassistant.oauth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class OpenAiIdentityVerifierTest {
    private RSAKey key() throws Exception { return new RSAKeyGenerator(2048).keyID("test").generate(); }
    private String token(RSAKey key, String nonce, String audience, long expiry) throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),
                new JWTClaimsSet.Builder().issuer("https://auth.openai.com").audience(audience)
                        .subject("user").claim("nonce", nonce).expirationTime(new Date(expiry)).build());
        token.sign(new RSASSASigner(key)); return token.serialize();
    }
    private String jwks(RSAKey key) { return "{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}"; }
    @Test public void validIdentityAccepted() throws Exception {
        RSAKey key = key();
        assertEquals("user", OpenAiIdentityVerifier.verify(token(key, "nonce", "client",
                System.currentTimeMillis() + 60_000), jwks(key), "client", "nonce", null).getSubject());
    }
    @Test public void wrongNonceRejected() throws Exception { reject("wrong", "client", "user", false); }
    @Test public void wrongClientRejected() throws Exception { reject("nonce", "other", "user", false); }
    @Test public void wrongAccountRejected() throws Exception { reject("nonce", "client", "other", false); }
    @Test public void expiredRejected() throws Exception { reject("nonce", "client", "user", true); }
    private void reject(String nonce, String client, String subject, boolean expired) throws Exception {
        RSAKey key = key();
        try {
            OpenAiIdentityVerifier.verify(token(key, "nonce", "client",
                    System.currentTimeMillis() + (expired ? -60_000 : 60_000)), jwks(key), client, nonce, subject);
            fail();
        } catch (SecurityException expected) { }
    }
    @Test public void wrongSignatureRejected() throws Exception {
        RSAKey signed = key(), other = key();
        try {
            OpenAiIdentityVerifier.verify(token(signed, "nonce", "client",
                    System.currentTimeMillis() + 60_000), jwks(other), "client", "nonce", null);
            fail();
        } catch (SecurityException expected) { }
    }
}
