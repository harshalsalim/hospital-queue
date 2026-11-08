package com.hospitalqueue.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A business-rule failure that is returned to the caller as a clean {@link ApiError}.
 *
 * <p>Create it through the static factories ({@link #notFound}, {@link #conflict},
 * {@link #badRequest}). Messages must never contain patient data.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    /**
     * @param status  HTTP status to return
     * @param code    stable machine-readable error code
     * @param message human-readable explanation
     */
    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** 404 for a missing resource, for example {@code notFound("Hospital", id)}. */
    public static ApiException notFound(String what, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found: " + id);
    }

    /** 409 when the request conflicts with the current state, for example a duplicate. */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 400 for a request that is well-formed JSON but breaks a business rule. */
    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
