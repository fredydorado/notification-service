package com.fardorado.notification.domain.exception;

/**
 * Thrown when a domain object is asked to move to a status that its current
 * lifecycle state does not allow.
 */
public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(String message) {
        super(message);
    }
}
