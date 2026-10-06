package com.fardorado.notification.application.exception;

/**
 * The request is syntactically well formed but its values are not acceptable
 * (for example an inverted date range or a page size above the configured
 * maximum).
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
