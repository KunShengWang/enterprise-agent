package com.agent.platform.ordercare.incident.client;

public class RabbitMqObservationException extends RuntimeException {

    private final boolean timeout;

    public RabbitMqObservationException(String message, boolean timeout, Throwable cause) {
        super(message, cause);
        this.timeout = timeout;
    }

    public boolean timeout() {
        return timeout;
    }
}
