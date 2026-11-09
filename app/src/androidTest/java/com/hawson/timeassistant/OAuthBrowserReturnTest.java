package com.hawson.timeassistant;

import android.net.Uri;
import android.content.Intent;
import com.hawson.timeassistant.auth.OAuthBrowser;
import org.junit.Test;
import static org.junit.Assert.*;

public class OAuthBrowserReturnTest {
    @Test public void officialAuthorizationUsesSeparateExternalBrowserTask() {
        Uri url = Uri.parse("https://auth.openai.com/api/accounts/authorize?state=fixture-only");
        Intent browser = OAuthBrowser.externalAuthorizationIntent(url);
        assertNotNull(browser);
        assertEquals(Intent.ACTION_VIEW, browser.getAction());
        assertEquals(url, browser.getData());
        assertTrue(browser.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertTrue((browser.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertNull(browser.getExtras());
        assertNull(browser.getComponent());
        assertNull(browser.getPackage());
    }

    @Test public void externalBrowserRejectsNonOfficialAuthorizationTargets() {
        for (String url : new String[]{
                "http://auth.openai.com/api/accounts/authorize", "https://relay.example/api/accounts/authorize",
                "https://auth.openai.com@relay.example/api/accounts/authorize",
                "https://user@auth.openai.com/api/accounts/authorize",
                "https://auth.openai.com:443/api/accounts/authorize",
                "https://auth.openai.com/other", "https://auth.openai.com/api/accounts/authorize#fixture"}) {
            assertNull(OAuthBrowser.externalAuthorizationIntent(Uri.parse(url)));
        }
        assertNull(OAuthBrowser.externalAuthorizationIntent(null));
    }

    @Test public void acceptsOnlyOwnNavigationWithoutCallbackData() {
        String pkg = "com.hawson.timeassistant.qa";
        assertTrue(OAuthBrowser.isReturnNavigation(Uri.parse(pkg + ".oauth://return"), pkg));
        for (String invalid : new String[]{
                "other.oauth://return", pkg + ".oauth://wrong", pkg + ".oauth://return/path",
                pkg + ".oauth://return?code=fixture", pkg + ".oauth://return#fixture",
                pkg + ".oauth://return:80", pkg + ".oauth://user@return"}) {
            assertFalse(invalid, OAuthBrowser.isReturnNavigation(Uri.parse(invalid), pkg));
        }
        assertFalse(OAuthBrowser.isReturnNavigation(null, pkg));
    }
}
