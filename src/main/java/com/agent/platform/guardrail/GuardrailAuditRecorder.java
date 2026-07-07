package com.agent.platform.guardrail;

import java.util.List;

public interface GuardrailAuditRecorder {

    void record(GuardrailAuditRecord record);

    List<GuardrailAuditRecord> recent(int limit);

    void clear();
}
