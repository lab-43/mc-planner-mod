package dev.lab43.mcplanner.core;

/** A plan file / response that can't be read; the message is meant for people. */
public final class PlanFormatException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PlanFormatException(String message) {
        super(message);
    }

    public PlanFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
