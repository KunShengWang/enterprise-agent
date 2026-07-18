package com.agent.platform.ordercare.incident.persistence;

import com.agent.platform.ordercare.incident.model.AgentTaskRecord;
import com.agent.platform.ordercare.incident.model.AgentTaskStatus;
import com.agent.platform.ordercare.incident.model.TaskEventActorType;

import java.util.List;
import java.util.Optional;

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
}
