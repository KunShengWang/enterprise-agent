package com.agent.platform.ordercare.incident.application;

public interface IncidentTaskResultCommitter {

    TaskResultCommitResult commit(TaskResultSubmission submission);
}
