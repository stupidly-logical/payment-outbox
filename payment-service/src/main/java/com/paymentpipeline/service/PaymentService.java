package com.paymentpipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentpipeline.domain.Payment;
import com.paymentpipeline.outbox.OutboxEvent;
import com.paymentpipeline.outbox.OutboxEventRepository;
import com.paymentpipeline.repository.PaymentRepository;
import com.paymentpipeline.shared.PaymentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository    paymentRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper         objectMapper;

    public PaymentService(PaymentRepository paymentRepository,
                          OutboxEventRepository outboxRepository,
                          ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.outboxRepository  = outboxRepository;
        this.objectMapper      = objectMapper;
    }

    @Transactional
    public Payment initiatePayment(Payment payment) {
        // Idempotency check — return existing if key already seen
        return paymentRepository
                .findByIdempotencyKey(payment.getIdempotencyKey())
                .orElseGet(() -> createNew(payment));
    }

    @Transactional
    public Payment transitionPayment(UUID paymentId, PaymentStatus targetStatus) {
        Payment payment = null;
        try {
            payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        } catch (PaymentNotFoundException e) {
            throw new RuntimeException(e);
        }

        payment.transitionTo(targetStatus);
        paymentRepository.save(payment);

        // Outbox write in same transaction — atomicity guaranteed
        outboxRepository.save(buildOutboxEvent(payment));

        return payment;
    }

    @Transactional
    public Payment failPayment(UUID paymentId, String reason) {
        Payment payment = null;
        try {
            payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        } catch (PaymentNotFoundException e) {
            throw new RuntimeException(e);
        }

        payment.fail(reason);
        paymentRepository.save(payment);
        outboxRepository.save(buildOutboxEvent(payment));

        return payment;
    }

    private Payment createNew(Payment payment) {
        Payment saved = paymentRepository.save(payment);
        outboxRepository.save(buildOutboxEvent(saved));
        return saved;
    }

    private OutboxEvent buildOutboxEvent(Payment payment) {
        try {
            String payload = objectMapper.writeValueAsString(new PaymentEventPayload(
                    payment.getId().toString(),
                    payment.getMerchantId().toString(),
                    payment.getStatus().name(),
                    payment.getAmount().toPlainString(),
                    payment.getCurrency(),
                    payment.getFailureReason()
            ));

            return OutboxEvent.builder()
                    .paymentId(payment.getId())
                    .eventType("PAYMENT_" + payment.getStatus().name())
                    .payload(payload)
                    .build();

        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize outbox payload", e);
        }
    }

    public record PaymentEventPayload(
            String paymentId,
            String merchantId,
            String status,
            String amount,
            String currency,
            String failureReason
    ) {}
}