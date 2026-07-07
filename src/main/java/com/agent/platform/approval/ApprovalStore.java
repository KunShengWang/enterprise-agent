package com.agent.platform.approval;

import java.util.List;
import java.util.Optional;

public interface ApprovalStore {

    void save(ApprovalRecord record);

    Optional<ApprovalRecord> find(String approvalId);

    List<ApprovalRecord> recent(int limit);
}
