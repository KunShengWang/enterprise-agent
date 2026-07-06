package com.agent.platform.memory;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class InMemoryMemoryService implements MemoryService {

    private static final int WINDOW_SIZE = 12;

    private final ConcurrentMap<String, List<MemoryMessage>> store = new ConcurrentHashMap<>();

    @Override
    public ConversationMemory load(String conversationId) {
        List<MemoryMessage> messages = store.getOrDefault(conversationId, List.of());
        int fromIndex = Math.max(0, messages.size() - WINDOW_SIZE);
        List<MemoryMessage> window = new ArrayList<>(messages.subList(fromIndex, messages.size()));
        String summary = messages.isEmpty() ? "" : "recent memory messages: " + messages.size();
        return new ConversationMemory(conversationId, window, summary);
    }

    @Override
    public void append(String conversationId, MemoryMessage message) {
        store.compute(conversationId, (key, current) -> {
            List<MemoryMessage> next = current == null ? new ArrayList<>() : new ArrayList<>(current);
            next.add(message);
            return next;
        });
    }
}
