package io.legion.server.exception;

/** 入参不合法 → 400。 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}