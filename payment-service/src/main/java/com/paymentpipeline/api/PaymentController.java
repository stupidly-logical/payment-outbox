package com.paymentpipeline.api;

import com.paymentpipeline.domain.Payment;
import com.paymentpipeline.service.PaymentService;
import com.paymentpipeline.shared.PaymentStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> initiatePayment(
            @Valid @RequestBody PaymentRequest request) {

        Payment payment = paymentService.initiatePayment(
                Payment.builder()
                        .idempotencyKey(request.idempotencyKey())
                        .merchantId(request.merchantId())
                        .amount(request.amount())
                        .currency(request.currency())
                        .build()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(PaymentResponse.from(payment));
    }

    @PatchMapping("/{id}/authorize")
    public ResponseEntity<PaymentResponse> authorize(@PathVariable UUID id) {
        return ResponseEntity.ok(PaymentResponse.from(
                paymentService.transitionPayment(id, PaymentStatus.AUTHORIZED)
        ));
    }

    @PatchMapping("/{id}/settle")
    public ResponseEntity<PaymentResponse> settle(@PathVariable UUID id) {
        return ResponseEntity.ok(PaymentResponse.from(
                paymentService.transitionPayment(id, PaymentStatus.SETTLEMENT_PENDING)
        ));
    }

    @PatchMapping("/{id}/fail")
    public ResponseEntity<PaymentResponse> fail(
            @PathVariable UUID id,
            @RequestParam String reason) {
        return ResponseEntity.ok(PaymentResponse.from(
                paymentService.failPayment(id, reason)
        ));
    }
}