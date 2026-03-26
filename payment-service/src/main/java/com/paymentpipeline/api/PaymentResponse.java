package com.paymentpipeline.api;

import com.paymentpipeline.domain.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID      id,
        String    status,
        BigDecimal amount,
        String    currency,
        String    idempotencyKey,
        Instant   createdAt
) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(
                p.getId(), p.getStatus().name(),
                p.getAmount(), p.getCurrency(),
                p.getIdempotencyKey(), p.getCreatedAt()
        );
    }
}