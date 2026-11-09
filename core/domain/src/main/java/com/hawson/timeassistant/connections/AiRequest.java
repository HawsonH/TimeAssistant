package com.hawson.timeassistant.connections;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AiRequest {
    public final String instructions;
    public final List<Message> messages;
    public AiRequest(String instructions, List<Message> messages) {
        this.instructions = instructions;
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
    }
    public static final class Message {
        public final String role, text;
        public final List<Part> parts;
        public Message(String role, String text, List<Part> parts) {
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new IllegalArgumentException("无效消息角色");
            }
            this.role = role; this.text = text;
            this.parts = Collections.unmodifiableList(new ArrayList<>(parts));
        }
    }
    public static final class Part {
        public final String name, mime, base64;
        public Part(String name, String mime, String base64) {
            this.name = name; this.mime = mime; this.base64 = base64;
        }
        public String dataUrl() { return "data:" + mime + ";base64," + base64; }
    }
}
