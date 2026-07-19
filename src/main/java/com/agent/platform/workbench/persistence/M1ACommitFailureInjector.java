package com.agent.platform.workbench.persistence;

@FunctionalInterface
public interface M1ACommitFailureInjector {

    M1ACommitFailureInjector NOOP = stage -> { };

    void after(M1ACommitStage stage);
}
