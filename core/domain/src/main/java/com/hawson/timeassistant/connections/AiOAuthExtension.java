package com.hawson.timeassistant.connections;

public interface AiOAuthExtension extends AiAccountPort, AiBearerPort {
    default void setAccountListener(java.util.function.Consumer<Account> listener) { }
    default void resumeSignIn() { }
    /** Cancel login and drain credential writers before deleting local account records. */
    void clearLocal() throws Exception;
}
