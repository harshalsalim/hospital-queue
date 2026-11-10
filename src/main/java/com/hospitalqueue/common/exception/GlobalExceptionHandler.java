package com.hospitalqueue.common.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns every exception into the standard {@link ApiError} JSON.
 *
 * <p>Rules: never put patient data or SQL text in a message or a log line, and never leak
 * stack traces to the caller. Unexpected errors are logged with their stack trace
 * (the log line carries the request id) and answered with a generic 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Business-rule failures raised by our own code. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiError.of(ex.getCode(), ex.getMessage()));
    }

    /** Bean-validation failures on a request body: 400 with one message per field. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            String message = fieldError.getDefaultMessage() == null ? "invalid value" : fieldError.getDefaultMessage();
            details.putIfAbsent(fieldError.getField(), message);
        }
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_FAILED", "Request validation failed", details));
    }

    /** Missing body or malformed JSON. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("MALFORMED_REQUEST", "Request body is missing or is not valid JSON"));
    }

    /** A path or query parameter of the wrong type, for example a bad UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("BAD_PARAMETER", "Invalid value for parameter: " + ex.getName()));
    }

    /** A database constraint rejected the write, typically a concurrent duplicate. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        // The exception text can contain column values, so it is deliberately not logged.
        log.warn("Data integrity violation (constraint conflict)");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("DATA_CONFLICT", "The request conflicts with existing data"));
    }

    /**
     * Catch-all. Spring's own web exceptions (404 for unknown URLs, 405 for wrong method, ...)
     * keep their status; anything else is an unexpected failure and becomes a generic 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAny(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode statusCode = errorResponse.getStatusCode();
            HttpStatus resolved = HttpStatus.resolve(statusCode.value());
            String reason = resolved != null ? resolved.getReasonPhrase() : "Request failed";
            String detail = errorResponse.getBody().getDetail();
            return ResponseEntity.status(statusCode)
                    .headers(errorResponse.getHeaders())
                    .body(ApiError.of("HTTP_" + statusCode.value(), detail != null ? detail : reason));
        }
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
