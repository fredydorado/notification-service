package com.fardorado.notification.application.exception;

/**
 * Stable, machine-readable API error codes. Clients branch on these rather
 * than parsing the human-readable message, so the constants must not be
 * renamed once published.
 */
public enum ErrorCode {
    RESOURCE_NOT_FOUND,
    INVALID_REQUEST,
    VALIDATION_ERROR,
    RESOURCE_CONFLICT,
    NOTIFICATION_EVENT_NOT_REPLAYABLE,
    UNAUTHORIZED,
    INTERNAL_ERROR
}
