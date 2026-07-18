package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.persistence.IncidentTaskResultPersistence;
import org.springframework.stereotype.Service;

@Service
public class DefaultIncidentTaskResultCommitter implements IncidentTaskResultCommitter {

    private final IncidentTaskResultPersistence persistence;

    public DefaultIncidentTaskResultCommitter(IncidentTaskResultPersistence persistence) {
        this.persistence = persistence;
    }

    @Override
    public TaskResultCommitResult commit(TaskResultSubmission submission) {
        return persistence.commitTaskResult(submission);
    }
}
