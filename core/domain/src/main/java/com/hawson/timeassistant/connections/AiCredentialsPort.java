package com.hawson.timeassistant.connections;

import java.util.Set;

/** Execution/storage boundary only. Never injected into a page. */
public interface AiCredentialsPort {
    String read(String reference) throws Exception;
    void write(String reference, String value) throws Exception;
    void remove(String reference) throws Exception;
    Set<String> references();
}
