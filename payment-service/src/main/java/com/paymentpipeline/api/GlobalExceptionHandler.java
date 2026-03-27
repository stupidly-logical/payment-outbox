package com.paymentpipeline.api;

import com.paymentpipeline.service.PaymentNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 409 — illegal state transition
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(
            IllegalStateException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(errorBody("INVALID_TRANSITION", ex.getMessage()));
    }

    // 404 — payment not found
    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            PaymentNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(errorBody("PAYMENT_NOT_FOUND", ex.getMessage()));
    }

    // 409 — duplicate idempotency key race condition
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateKey(
            DataIntegrityViolationException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(errorBody("DUPLICATE_REQUEST",
                        "A payment with this idempotency key already exists"));
    }

    // 400 — validation failures (@NotNull, @NotBlank etc.)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(errorBody("VALIDATION_FAILED", message));
    }

    // 500 — catch-all for anything unexpected
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("INTERNAL_ERROR",
                        "An unexpected error occurred"));
    }

    private Map<String, Object> errorBody(String code, String message) {
        return Map.of(
                "error",     code,
                "message",   message,
                "timestamp", Instant.now().toString()
        );
    }
}