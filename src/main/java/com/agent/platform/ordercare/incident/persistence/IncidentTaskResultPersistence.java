package com.agent.platform.ordercare.incident.persistence;

import com.agent.platform.ordercare.incident.application.TaskResultCommitResult;
import com.agent.platform.ordercare.incident.application.TaskResultSubmission;

public interface IncidentTaskResultPersistence {

    TaskResultCommitResult commitTaskResult(TaskResultSubmission submission);
}
