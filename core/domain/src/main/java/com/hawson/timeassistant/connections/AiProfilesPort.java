package com.hawson.timeassistant.connections;

import java.util.List;

public interface AiProfilesPort {
    List<AiProfile> list();
    AiProfile active();
    AiProfile save(AiProfile profile, String replacementKey) throws Exception;
    void activate(String id) throws Exception;
    void delete(String id) throws Exception;
    AiProfile duplicate(String id) throws Exception;
    String contextBoundary();
    long contextStartedAt();
}
