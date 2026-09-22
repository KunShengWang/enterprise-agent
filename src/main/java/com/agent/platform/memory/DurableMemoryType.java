package com.agent.platform.memory;

import java.util.Optional;

/**
 * 自动提取允许写入长期记忆的最小类型集合。
 */
public enum DurableMemoryType {
    PREFERENCE("PREFERENCE"),
    STABLE_INSTRUCTION("STABLE_INSTRUCTION");

    private final String persistedValue;

    DurableMemoryType(String persistedValue) {
        this.persistedValue = persistedValue;
    }

    public String persistedValue() {
        return persistedValue;
    }

    /**
     * 只接受 LLM 协议中的精确枚举名，不接受 legacy 持久化分类值。
     */
    public static Optional<DurableMemoryType> fromProtocolValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim();
        for (DurableMemoryType type : values()) {
            if (type.name().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /**
     * 只接受当前 typed durable memory 的持久化值，与协议解析保持分离。
     */
    public static Optional<DurableMemoryType> fromPersistedValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (DurableMemoryType type : values()) {
            if (type.persistedValue.equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
