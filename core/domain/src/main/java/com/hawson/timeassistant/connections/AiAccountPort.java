package com.hawson.timeassistant.connections;

import java.util.List;

public interface AiAccountPort {
    final class Account {
        public final String reference, label;
        public final boolean authorized;
        public Account(String reference, String label, boolean authorized) {
            this.reference = reference; this.label = label; this.authorized = authorized;
        }
    }
    interface Listener {
        void finished(Account account);
        void failed(String safeMessage);
    }
    boolean available();
    List<Account> accounts();
    String beginSignIn(String accountReference, Listener listener) throws Exception;
    /** True while an encrypted browser authorization session can still accept its app-link return. */
    boolean hasPendingSignIn();
    default AiSignInStatus signInStatus() {
        return hasPendingSignIn() ? AiSignInStatus.WAITING : AiSignInStatus.IDLE;
    }
    void completePendingSignIn(String callbackUri, Listener listener) throws Exception;
    void cancelSignIn();
    /** True only when remote revocation was confirmed. */
    boolean signOut(String reference) throws Exception;
}
