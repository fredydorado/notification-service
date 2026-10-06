package com.fardorado.notification.application.exception;

/**
 * A requested resource does not exist, or is not accessible to the calling
 * client. Both cases are deliberately indistinguishable so that the API never
 * reveals the existence of another client's resource.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
