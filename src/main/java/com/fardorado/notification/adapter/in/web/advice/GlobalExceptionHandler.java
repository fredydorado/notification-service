package com.fardorado.notification.adapter.in.web.advice;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.fardorado.notification.adapter.in.web.dto.ErrorResponseDto;
import com.fardorado.notification.adapter.in.web.dto.FieldErrorDto;
import com.fardorado.notification.adapter.in.web.filter.CorrelationIdFilter;
import com.fardorado.notification.application.exception.ErrorCode;
import com.fardorado.notification.application.exception.InvalidRequestException;
import com.fardorado.notification.application.exception.ResourceConflictException;
import com.fardorado.notification.application.exception.ResourceNotFoundException;

/**
 * Translates exceptions into the standardized {@link ErrorResponseDto}.
 *
 * <p>Nothing here exposes stack traces, SQL, schema details or internal class
 * names: unexpected failures are logged in full and reported to the client as
 * a generic message.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleResourceNotFound(
            ResourceNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                "Resource not found", exception.getMessage(), request, null);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponseDto> handleInvalidRequest(
            InvalidRequestException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                "Invalid request", exception.getMessage(), request, null);
    }

    /**
     * Covers the not-replayable case too, which carries its own error code so
     * clients can distinguish it from a generic conflict.
     */
    @ExceptionHandler(ResourceConflictException.class)
    public ResponseEntity<ErrorResponseDto> handleResourceConflict(
            ResourceConflictException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, exception.getErrorCode(),
                "Resource conflict", exception.getMessage(), request, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldErrorDto> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorDto(error.getField(), error.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Validation failed", "One or more fields are invalid", request, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponseDto> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        List<FieldErrorDto> errors = exception.getConstraintViolations().stream()
                .map(violation -> new FieldErrorDto(lastNodeOf(violation), violation.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Validation failed", "One or more parameters are invalid", request, errors);
    }

    /**
     * An unparseable value for a typed parameter, such as an unknown
     * {@code delivery_status} or a malformed date. The rejected value is not
     * echoed back verbatim beyond the parameter name.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponseDto> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        FieldErrorDto error = new FieldErrorDto(exception.getName(), "has an invalid value");
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Validation failed",
                "Parameter '%s' has an invalid value".formatted(exception.getName()),
                request, List.of(error));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponseDto> handleMissingParameter(
            MissingServletRequestParameterException exception, HttpServletRequest request) {
        FieldErrorDto error = new FieldErrorDto(exception.getParameterName(), "is required");
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Validation failed",
                "Parameter '%s' is required".formatted(exception.getParameterName()),
                request, List.of(error));
    }

    /**
     * The caller did not identify itself, so no client scope can be resolved
     * and no notification event may be read.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponseDto> handleMissingHeader(
            MissingRequestHeaderException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                "Unauthorized",
                "Request header '%s' is required".formatted(exception.getHeaderName()),
                request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        log.error("unexpected error handling request: method={}, path={}",
                request.getMethod(), request.getRequestURI(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "Internal server error",
                "The request could not be completed. Quote the correlation id when reporting this.",
                request, null);
    }

    private ResponseEntity<ErrorResponseDto> build(
            HttpStatus status,
            ErrorCode code,
            String message,
            String description,
            HttpServletRequest request,
            List<FieldErrorDto> errors) {
        ErrorResponseDto body = new ErrorResponseDto(
                code.name(),
                message,
                description,
                request.getRequestURI(),
                OffsetDateTime.now(),
                MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY),
                errors);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * A violation path looks like {@code listNotificationEvents.size}; only
     * the parameter name is useful to a client.
     */
    private String lastNodeOf(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot < 0 ? path : path.substring(lastDot + 1);
    }
}
