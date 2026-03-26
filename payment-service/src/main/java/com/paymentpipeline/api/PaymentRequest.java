package com.paymentpipeline.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record PaymentRequest(
        @NotBlank  String     idempotencyKey,
        @NotNull   UUID       merchantId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank @Size(min=3, max=3) String currency
) {}