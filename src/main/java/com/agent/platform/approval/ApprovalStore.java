package com.agent.platform.approval;

import java.util.List;
import java.util.Optional;

public interface ApprovalStore {

    void save(ApprovalRecord record);

    boolean decideIfRequestedAndNotExpired(String approvalId,
                                           ApprovalRecord nextRecord,
                                           java.time.Instant decisionTime);

    boolean expireIfRequested(String approvalId,
                              ApprovalRecord expiredRecord,
                              java.time.Instant expirationCheckTime);

    Optional<ApprovalRecord> find(String approvalId);

    List<ApprovalRecord> recent(int limit);
}
