package com.agent.platform.ordercare.incident.persistence;

import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.AgentTaskStatus;
import com.agent.platform.ordercare.incident.model.TaskEventActorType;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import com.agent.platform.ordercare.incident.model.TaskLeaseClaim;

public interface AgentTaskStore {

    AgentTaskRecord create(AgentTaskRecord task);

    Optional<AgentTaskRecord> findTask(String taskId);

    List<AgentTaskRecord> listTasks(String incidentId);

    AgentTaskRecord transitionTask(String taskId,
                                   long expectedVersion,
                                   AgentTaskStatus targetStatus,
                                   String childRunId,
                                   String lastError,
                                   TaskEventActorType actorType,
                                   String actorId,
                                   String idempotencyKey);

    AgentTaskRecord bindChildRun(String taskId,
                                 long expectedVersion,
                                 String childRunId,
                                 String idempotencyKey);

    TaskLeaseClaim claimTask(String taskId,
                             long expectedVersion,
                             String owner,
                             Instant leaseUntil,
                             boolean allowExpiredTakeover);

    AgentTaskRecord renewTaskLease(String taskId,
                                   String owner,
                                   long fencingToken,
                                   Instant leaseUntil);

    AgentTaskRecord transitionLeasedTask(String taskId,
                                         long expectedVersion,
                                         AgentTaskStatus targetStatus,
                                         String childRunId,
                                         String lastError,
                                         String owner,
                                         long fencingToken,
                                         TaskEventActorType actorType,
                                         String actorId,
                                         String idempotencyKey);

    List<AgentTaskRecord> listStaleTasks(Instant now, int limit);
}
