package com.hawson.timeassistant.connections;

public interface AiCallObserver {
    final class Usage {
        public final Long input, output, cached;
        public Usage(Long input, Long output, Long cached) { this.input = input; this.output = output; this.cached = cached; }
    }
    interface Call {
        void usage(Usage usage);
        void finish(String outcome);
    }
    Call begin(AiProfile profile, String kind);
}
