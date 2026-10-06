package com.fardorado.notification.application.exception;

/**
 * The resource's current state does not allow the requested operation.
 */
public class ResourceConflictException extends RuntimeException {

    private final ErrorCode errorCode;

    public ResourceConflictException(String message) {
        this(ErrorCode.RESOURCE_CONFLICT, message);
    }

    protected ResourceConflictException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
