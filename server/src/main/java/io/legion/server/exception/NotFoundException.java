package io.legion.server.exception;

/** 资源不存在 → 404。 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}