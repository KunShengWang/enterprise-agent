package com.agent.platform.ordercare.incident.recovery.persistence;

import com.agent.platform.ordercare.incident.recovery.model.IncidentRecoveryPlanRecord;

import java.util.List;
import java.util.Optional;

public interface IncidentRecoveryPlanStore {

    IncidentRecoveryPlanRecord create(IncidentRecoveryPlanRecord plan);

    Optional<IncidentRecoveryPlanRecord> find(String planId);

    Optional<IncidentRecoveryPlanRecord> findByRequestKey(String incidentId, String requestKey);

    List<IncidentRecoveryPlanRecord> listByIncident(String incidentId);

    IncidentRecoveryPlanRecord update(IncidentRecoveryPlanRecord next, long expectedVersion);
}
