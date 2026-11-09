package com.hawson.timeassistant.compatible;

import com.hawson.timeassistant.connections.*;
import java.io.StringReader;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AiUsageCodecTest {
    @Test public void responsesUsagePreservesCachedSubset() throws Exception {
        AiCallObserver.Usage result = AiUsageCodec.read(new JSONObject("{\"input_tokens\":12,\"output_tokens\":3,\"input_tokens_details\":{\"cached_tokens\":5}}"));
        assertEquals(Long.valueOf(12), result.input); assertEquals(Long.valueOf(3), result.output); assertEquals(Long.valueOf(5), result.cached);
    }
    @Test public void chatUsageKeepsMissingValuesUnknown() throws Exception {
        AiCallObserver.Usage result = AiUsageCodec.read(new JSONObject("{\"prompt_tokens\":0}"));
        assertEquals(Long.valueOf(0), result.input); assertNull(result.output); assertNull(result.cached);
        assertNull(AiUsageCodec.read(null).input);
    }
    @Test public void malformedAndNegativeUsageIsNotEstimated() throws Exception {
        AiCallObserver.Usage result = AiUsageCodec.read(new JSONObject("{\"input_tokens\":-1,\"output_tokens\":\"100\"}"));
        assertNull(result.input); assertNull(result.output);
    }
    @Test public void streamingTerminalUsageDeliveredOnce() throws Exception {
        int[] calls = {0};
        String events = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":4,\"output_tokens\":1}}}\n\n";
        assertEquals("OK", ResponsesStream.read(new StringReader(events), new AiCancellation(), value -> { calls[0]++; assertEquals(Long.valueOf(4), value.input); }));
        assertEquals(1, calls[0]);
    }
}
