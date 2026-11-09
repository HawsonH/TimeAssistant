package com.hawson.timeassistant.oauth;

import java.net.URI;
import com.hawson.timeassistant.connections.AiSignInException;
import org.junit.Test;
import static org.junit.Assert.*;

public class OAuthCallbackValidatorTest {
    private static final String REDIRECT = "http://127.0.0.1:48123/auth/callback";
    private static final String STATE = "expected-state";

    @Test public void acceptsMatchingCallback() throws Exception {
        URI callback = OAuthCallbackValidator.validate(
                REDIRECT + "?code=one&state=" + STATE + "&client_id=client", REDIRECT, STATE);
        assertEquals(REDIRECT, callback.getScheme() + "://" + callback.getAuthority() + callback.getRawPath());
    }

    @Test public void acceptsMatchingAppLinkCallback() throws Exception {
        String redirect = "com.hawson.timeassistant.qa.oauth://return";
        URI callback = OAuthCallbackValidator.validate(
                redirect + "?code=one&state=" + STATE, redirect, STATE);
        assertEquals("com.hawson.timeassistant.qa.oauth", callback.getScheme());
        assertEquals("return", callback.getHost());
    }

    @Test public void rejectsDifferentOriginOrPath() {
        reject("http://127.0.0.1:48124/auth/callback?code=one&state=" + STATE);
        reject("http://localhost:48123/auth/callback?code=one&state=" + STATE);
        reject("http://127.0.0.1:48123/other?code=one&state=" + STATE);
    }

    @Test public void rejectsWrongStateAndRepeatedFields() {
        reject(REDIRECT + "?code=one&state=other");
        reject(REDIRECT + "?code=one&state=" + STATE + "&state=" + STATE);
        reject(REDIRECT + "?code=one&state=" + STATE + "&code=two");
    }

    @Test public void rejectsFragmentAndUserInfo() {
        reject("http://user@127.0.0.1:48123/auth/callback?code=one&state=" + STATE);
        reject(REDIRECT + "?code=one&state=" + STATE + "#fragment");
    }

    @Test public void rejectsBareCodeAndAuthorizationLink() {
        reject("one");
        reject("https://auth.example.test/authorize?state=" + STATE);
    }

    @Test public void rejectsEncodedDuplicateAndMalformedQuery() {
        reject(REDIRECT + "?code=one&state=" + STATE + "&%73tate=" + STATE);
        reject(REDIRECT + "?code=one&state=" + STATE + "&broken");
        reject(REDIRECT + "?code=one&state=%");
    }

    @Test public void reportsSafeSpecificReasonsWithoutCallbackContents() throws Exception {
        assertReason("not-a-callback", AiSignInException.Reason.ORIGIN);
        assertReason(REDIRECT + "?code=secret-example&state=wrong", AiSignInException.Reason.STATE);
        assertReason(REDIRECT + "?code=secret-example&state=" + STATE + "&code=two",
                AiSignInException.Reason.PARAMETERS);
        assertReason(REDIRECT + "?state=%", AiSignInException.Reason.INVALID);
    }

    private void assertReason(String callback, AiSignInException.Reason reason) throws Exception {
        try {
            OAuthCallbackValidator.validate(callback, REDIRECT, STATE);
            fail("Expected callback rejection");
        } catch (AiSignInException expected) {
            assertEquals(reason, expected.reason);
            assertFalse(expected.getMessage().contains("secret-example"));
            assertFalse(expected.getMessage().contains(callback));
        }
    }

    private void reject(String callback) {
        try {
            OAuthCallbackValidator.validate(callback, REDIRECT, STATE);
            fail();
        } catch (SecurityException expected) { }
        catch (Exception unexpected) { throw new AssertionError(unexpected); }
    }
}
