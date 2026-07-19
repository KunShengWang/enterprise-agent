package com.agent.platform.ordercare.incident.recovery.persistence;

import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanItem;
import com.agent.platform.ordercare.incident.recovery.model.RecoveryItemLeaseClaim;

public interface IncidentRecoveryPlanStore {

    IncidentRecoveryPlanRecord create(IncidentRecoveryPlanRecord plan);

    Optional<IncidentRecoveryPlanRecord> find(String planId);

    Optional<IncidentRecoveryPlanRecord> findByRequestKey(String incidentId, String requestKey);

    List<IncidentRecoveryPlanRecord> listByIncident(String incidentId);

    IncidentRecoveryPlanRecord update(IncidentRecoveryPlanRecord next, long expectedVersion);

    default RecoveryItemLeaseClaim claimItem(String planId,
                                     String itemId,
                                     String owner,
                                     Instant leaseUntil,
                                     boolean allowExpiredTakeover) {
        throw new UnsupportedOperationException("recovery item leases are not supported");
    }

    default IncidentRecoveryPlanRecord renewItemLease(String planId,
                                              String itemId,
                                              String owner,
                                              long fencingToken,
                                              Instant leaseUntil) {
        throw new UnsupportedOperationException("recovery item leases are not supported");
    }

    default IncidentRecoveryPlanRecord updateItemFenced(String planId,
                                                IncidentRecoveryPlanItem replacement,
                                                String owner,
                                                long fencingToken) {
        throw new UnsupportedOperationException("recovery item leases are not supported");
    }

    default List<IncidentRecoveryPlanRecord> listStaleExecuting(Instant now, int limit) {
        return List.of();
    }
}
