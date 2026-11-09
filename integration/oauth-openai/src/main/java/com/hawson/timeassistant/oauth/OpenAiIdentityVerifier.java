package com.hawson.timeassistant.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import java.util.Date;

/** Signature verification precedes all use of account identity or login hints. */
public final class OpenAiIdentityVerifier {
    private OpenAiIdentityVerifier() { }
    public static JWTClaimsSet verify(String idToken, String jwks, String clientId,
                                     String nonce, String expectedSubject) throws Exception {
        SignedJWT jwt = SignedJWT.parse(idToken);
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
                || jwt.getHeader().getKeyID() == null
                || jwt.getHeader().getCriticalParams() != null) {
            throw new SecurityException("不支持的身份令牌签名");
        }
        JWK key = JWKSet.parse(jwks).getKeyByKeyId(jwt.getHeader().getKeyID());
        if (!(key instanceof RSAKey) || !jwt.verify(new RSASSAVerifier(((RSAKey) key).toRSAPublicKey()))) {
            throw new SecurityException("身份令牌签名无效");
        }
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        if (!"https://auth.openai.com".equals(claims.getIssuer())
                || !claims.getAudience().contains(clientId)
                || claims.getExpirationTime() == null || !claims.getExpirationTime().after(new Date())
                || claims.getSubject() == null
                || (nonce != null && !nonce.equals(claims.getStringClaim("nonce")))
                || (expectedSubject != null && !expectedSubject.equals(claims.getSubject()))) {
            throw new SecurityException("身份令牌校验失败");
        }
        return claims;
    }
}
