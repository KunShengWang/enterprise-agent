package com.agent.platform.guardrail;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class InMemoryGuardrailAuditRecorder implements GuardrailAuditRecorder {

    private static final int MAX_RECORDS = 500;

    private final ConcurrentLinkedDeque<GuardrailAuditRecord> records = new ConcurrentLinkedDeque<>();

    @Override
    public void record(GuardrailAuditRecord record) {
        if (record == null) {
            return;
        }
        records.addFirst(record);
        while (records.size() > MAX_RECORDS) {
            records.pollLast();
        }
    }

    @Override
    public List<GuardrailAuditRecord> recent(int limit) {
        List<GuardrailAuditRecord> result = new ArrayList<>();
        for (GuardrailAuditRecord record : records) {
            result.add(record);
            if (result.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return result;
    }

    @Override
    public void clear() {
        records.clear();
    }
}
