package com.hawson.timeassistant;

import android.content.Context;
import com.hawson.timeassistant.connections.*;
import java.util.*;

final class OAuthModule {
    static AiOAuthExtension create(Context context, AiCredentialsPort credentials) {
        return new AiOAuthExtension() {
            public boolean available() { return false; }
            public List<Account> accounts() { return Collections.emptyList(); }
            public String beginSignIn(String reference, Listener listener) {
                throw new IllegalStateException("此构建未包含 OAuth 扩展");
            }
            public boolean hasPendingSignIn() { return false; }
            public void completePendingSignIn(String callbackUri, Listener listener) {
                throw new IllegalStateException("此构建未包含 OAuth 扩展");
            }
            public void cancelSignIn() { }
            public void clearLocal() { }
            public boolean signOut(String reference) { return false; }
            public String resolve(AiProfile profile) { throw new IllegalStateException("OAuth 扩展不可用"); }
        };
    }
}
