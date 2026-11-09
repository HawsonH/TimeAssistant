package com.hawson.timeassistant.connections;

/** Only transport implementations may resolve bearer credentials. */
public interface AiBearerPort {
    String resolve(AiProfile profile) throws Exception;
    default String resolve(AiProfile profile, AiCancellation cancellation) throws Exception {
        cancellation.check();
        return resolve(profile);
    }
    default String refreshAfterRejection(AiProfile profile, String rejectedBearer,
                                         AiCancellation cancellation) throws Exception { return null; }
}
