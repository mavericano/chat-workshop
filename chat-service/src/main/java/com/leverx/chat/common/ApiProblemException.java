package com.leverx.chat.common;

import org.springframework.http.HttpStatus;

/**
 * Error that is returned to the client as a problem detail with the given status and message.
 */
public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;

    public ApiProblemException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiProblemException badRequest(String message) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiProblemException notFound(String message) {
        return new ApiProblemException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiProblemException conflict(String message) {
        return new ApiProblemException(HttpStatus.CONFLICT, message);
    }
}
