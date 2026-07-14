package com.agent.platform.approval;

import java.util.List;
import java.util.Optional;

public interface ApprovalStore {

    void save(ApprovalRecord record);

    boolean transition(String approvalId, ApprovalStatus expectedStatus, ApprovalRecord nextRecord);

    Optional<ApprovalRecord> find(String approvalId);

    List<ApprovalRecord> recent(int limit);
}
