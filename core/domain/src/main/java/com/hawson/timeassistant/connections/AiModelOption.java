package com.hawson.timeassistant.connections;

public final class AiModelOption {
    public final String id, name;
    public AiModelOption(String id, String name) {
        this.id = id;
        this.name = name == null || name.trim().isEmpty() ? id : name;
    }
}
