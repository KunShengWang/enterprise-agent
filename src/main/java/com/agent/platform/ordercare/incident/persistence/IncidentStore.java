package com.agent.platform.ordercare.incident.persistence;

import com.agent.platform.ordercare.incident.model.IncidentAggregate;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.model.TaskEventActorType;

import java.util.Optional;
import java.util.Map;

public interface IncidentStore {

    IncidentRecord create(IncidentRecord incident);

    /**
     * 幂等创建事件
     */
    IncidentRecord createForDispatch(String dispatchRequestId, IncidentRecord incident);

    Optional<IncidentRecord> findByDispatchRequestId(String dispatchRequestId);

    Optional<IncidentRecord> find(String incidentId);

    Optional<IncidentSnapshot> findSnapshot(String snapshotId);

    Optional<IncidentAggregate> findAggregate(String incidentId, int eventLimit);

    IncidentRecord transitionStatus(String incidentId,
                                    long expectedVersion,
                                    IncidentStatus targetStatus,
                                    TaskEventActorType actorType,
                                    String actorId,
                                    String idempotencyKey);

    IncidentRecord updateDetails(String incidentId,
                                 long expectedVersion,
                                 String commanderRunId,
                                 String reviewerRunId,
                                 Map<String, Object> delegationPlan,
                                 Map<String, Object> assessment,
                                 boolean incrementClarification);
}
