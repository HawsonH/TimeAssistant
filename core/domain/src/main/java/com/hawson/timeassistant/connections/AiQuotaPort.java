package com.hawson.timeassistant.connections;

/** Read-only quota metadata. Providers resolve their own credentials, never a page's OAuth token. */
public interface AiQuotaPort {
    AiQuotaSnapshot read(String sourceReference, AiCancellation cancellation) throws Exception;
}
