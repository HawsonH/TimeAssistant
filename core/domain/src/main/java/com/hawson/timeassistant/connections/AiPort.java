package com.hawson.timeassistant.connections;

import java.util.List;

/** Blocking boundary, called only from an executor; completion means the full response succeeded. */
public interface AiPort {
    String complete(AiProfile profile, AiRequest request, AiCancellation cancellation) throws Exception;
    List<String> models(AiProfile profile, AiCancellation cancellation) throws Exception;
    default List<AiModelOption> modelOptions(AiProfile profile, AiCancellation cancellation) throws Exception {
        java.util.ArrayList<AiModelOption> result = new java.util.ArrayList<>();
        for (String id : models(profile, cancellation)) result.add(new AiModelOption(id, id));
        return result;
    }
}
