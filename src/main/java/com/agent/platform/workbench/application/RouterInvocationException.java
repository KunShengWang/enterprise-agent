package com.agent.platform.workbench.application;

public class RouterInvocationException extends RuntimeException {

    private final String failureCode;
    private final RouterFailureObservation observation;

    public RouterInvocationException(String failureCode,
                                     String message,
                                     RouterFailureObservation observation,
                                     Throwable cause) {
        super(message, cause);
        this.failureCode = failureCode;
        this.observation = observation == null ? RouterFailureObservation.empty() : observation;
    }

    public String failureCode() { return failureCode; }
    public RouterFailureObservation observation() { return observation; }
}
