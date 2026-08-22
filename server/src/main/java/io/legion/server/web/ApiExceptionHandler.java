package io.legion.server.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import io.legion.server.exception.BadRequestException;
import io.legion.server.exception.NotFoundException;

/** M0 统一错误面：{error: message} + 语义化状态码。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return ResponseEntity.status(404).body(error(e.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<Map<String, String>> badRequest(BadRequestException e) {
        return ResponseEntity.status(400).body(error(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, String>> badPathParam(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(400).body(error("invalid " + e.getName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.status(400).body(error("invalid request body"));
    }

    private static Map<String, String> error(String message) {
        return Map.of("error", message);
    }
}