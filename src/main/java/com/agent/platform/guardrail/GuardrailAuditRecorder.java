package com.agent.platform.guardrail;

import java.util.List;

public interface GuardrailAuditRecorder {

    /**
     * 往 agent_store_record 中记录审批记录
     */
    void record(GuardrailAuditRecord record);

    List<GuardrailAuditRecord> recent(int limit);

    void clear();
}
