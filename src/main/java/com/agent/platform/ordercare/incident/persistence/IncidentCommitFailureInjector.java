package com.agent.platform.ordercare.incident.persistence;

@FunctionalInterface
public interface IncidentCommitFailureInjector {

    IncidentCommitFailureInjector NOOP = stage -> { };

    void after(IncidentCommitStage stage);
}
