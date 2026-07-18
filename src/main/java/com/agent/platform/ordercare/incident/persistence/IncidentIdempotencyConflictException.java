package com.agent.platform.ordercare.incident.persistence;

public class IncidentIdempotencyConflictException extends RuntimeException {

    public IncidentIdempotencyConflictException(String message) {
        super(message);
    }
}
