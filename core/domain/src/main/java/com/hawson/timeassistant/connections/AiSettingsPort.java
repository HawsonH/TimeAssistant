package com.hawson.timeassistant.connections;

import java.util.List;

/** Draft probes neither persist nor activate configurations and contain no task context. */
public interface AiSettingsPort {
    String test(AiProfile draft, String draftKey, AiCancellation cancellation) throws Exception;
    List<String> models(AiProfile draft, String draftKey, AiCancellation cancellation) throws Exception;
    default List<AiModelOption> modelOptions(AiProfile draft, String draftKey, AiCancellation cancellation) throws Exception {
        java.util.ArrayList<AiModelOption> result = new java.util.ArrayList<>();
        for (String id : models(draft, draftKey, cancellation)) result.add(new AiModelOption(id, id));
        return result;
    }
    void requireAllowed(String url);
    void approvePrivateHttp(String url);
    void revokePrivateHttp();
    default String connectionNotice() { return ""; }
    default void recoverAccount(String reference) { }
    default void addConnectionListener(Runnable listener) { }
    default void removeConnectionListener(Runnable listener) { }
}
