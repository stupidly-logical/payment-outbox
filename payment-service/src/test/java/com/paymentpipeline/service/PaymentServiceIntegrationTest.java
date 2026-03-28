package com.paymentpipeline.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentpipeline.AbstractIntegrationTest;
import com.paymentpipeline.domain.Payment;
import com.paymentpipeline.outbox.OutboxEvent;
import com.paymentpipeline.shared.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ObjectMapper objectMapper;

    // -----------------------------------------------------------------------
    // Test 1: atomic write of payment + outbox event
    // -----------------------------------------------------------------------

    @Test
    void initiatePayment_shouldWritePaymentAndOutboxEventAtomically() throws Exception {
        Payment saved = paymentService.initiatePayment(buildPayment("key-1"));

        assertThat(paymentRepository.findAll()).hasSize(1);
        Payment inDb = paymentRepository.findById(saved.getId()).orElseThrow();
        assertThat(inDb.getStatus()).isEqualTo(PaymentStatus.INITIATED);

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);

        OutboxEvent event = events.get(0);
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getPaymentId()).isEqualTo(saved.getId());

        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.get("paymentId").asText()).isEqualTo(saved.getId().toString());
        // PaymentService serialises amount via BigDecimal.toPlainString() which
        // preserves the scale of the Java object (scale 2 from "100.00"), not the
        // NUMERIC(19,4) DB column scale.
        assertThat(payload.get("amount").asText()).isEqualTo("100.00");
        assertThat(payload.get("currency").asText()).isEqualTo("USD");
    }

    // -----------------------------------------------------------------------
    // Test 2: idempotency — duplicate idempotencyKey returns existing payment
    // -----------------------------------------------------------------------

    @Test
    void initiatePayment_withDuplicateIdempotencyKey_shouldReturnExistingPayment() {
        Payment first  = paymentService.initiatePayment(buildPayment("key-idem"));
        Payment second = paymentService.initiatePayment(buildPayment("key-idem"));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Test 3: each state transition writes its own outbox event
    // -----------------------------------------------------------------------

    @Test
    void transitionPayment_shouldWriteNewOutboxEventForEachTransition() {
        Payment payment = paymentService.initiatePayment(buildPayment("key-3"));
        UUID id = payment.getId();

        paymentService.transitionPayment(id, PaymentStatus.AUTHORIZED);
        paymentService.transitionPayment(id, PaymentStatus.SETTLEMENT_PENDING);

        List<OutboxEvent> events = outboxEventRepository.findAll()
                .stream()
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();

        assertThat(events).hasSize(3);
        assertThat(events.get(0).getEventType()).isEqualTo("PAYMENT_INITIATED");
        assertThat(events.get(1).getEventType()).isEqualTo("PAYMENT_AUTHORIZED");
        assertThat(events.get(2).getEventType()).isEqualTo("PAYMENT_SETTLEMENT_PENDING");
    }

    // -----------------------------------------------------------------------
    // Test 4: illegal transition throws and does not write outbox event
    // -----------------------------------------------------------------------

    @Test
    void transitionPayment_illegalTransition_shouldThrowAndNotWriteOutbox() {
        Payment payment = paymentService.initiatePayment(buildPayment("key-4"));
        UUID id = payment.getId();

        paymentService.transitionPayment(id, PaymentStatus.AUTHORIZED);
        paymentService.transitionPayment(id, PaymentStatus.SETTLEMENT_PENDING);
        paymentService.transitionPayment(id, PaymentStatus.SETTLED);

        // Attempt illegal transition: SETTLED → AUTHORIZED
        assertThatThrownBy(() -> paymentService.transitionPayment(id, PaymentStatus.AUTHORIZED))
                .isInstanceOf(RuntimeException.class);

        // Still only 3 outbox events (one per valid transition above + initial)
        assertThat(outboxEventRepository.count()).isEqualTo(4);

        Payment inDb = paymentRepository.findById(id).orElseThrow();
        assertThat(inDb.getStatus()).isEqualTo(PaymentStatus.SETTLED);
    }

    // -----------------------------------------------------------------------
    // Test 5: failPayment sets reason and writes PAYMENT_FAILED outbox event
    // -----------------------------------------------------------------------

    @Test
    void failPayment_shouldSetFailureReasonAndWriteOutboxEvent() {
        Payment payment = paymentService.initiatePayment(buildPayment("key-5"));
        UUID id = payment.getId();

        paymentService.failPayment(id, "insufficient_funds");

        Payment inDb = paymentRepository.findById(id).orElseThrow();
        assertThat(inDb.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(inDb.getFailureReason()).isEqualTo("insufficient_funds");

        List<OutboxEvent> events = outboxEventRepository.findAll()
                .stream()
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();
        assertThat(events).hasSize(2);
        assertThat(events.get(1).getEventType()).isEqualTo("PAYMENT_FAILED");
    }

    // -----------------------------------------------------------------------
    // Test 6: optimistic locking — concurrent updates, only one wins
    // -----------------------------------------------------------------------

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void optimisticLocking_concurrentUpdates_shouldPreventLostUpdates()
            throws InterruptedException {

        // Commit payment outside test — needs to be visible to concurrent threads
        Payment payment = paymentService.initiatePayment(buildPayment("key-lock"));
        UUID id = payment.getId();

        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures  = new AtomicInteger(0);

        Runnable authorize = () -> {
            try {
                startGate.await();
                paymentService.transitionPayment(id, PaymentStatus.AUTHORIZED);
                successes.incrementAndGet();
            } catch (RuntimeException e) {
                // Covers ObjectOptimisticLockingFailureException (subclass) and any
                // other runtime exception thrown by the losing concurrent update
                failures.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(authorize);
        pool.submit(authorize);
        startGate.countDown();
        pool.shutdown();
        pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);

        Payment inDb = paymentRepository.findById(id).orElseThrow();
        assertThat(inDb.getVersion()).isEqualTo(1L);

        // Cleanup (NOT_SUPPORTED means no automatic rollback)
        outboxEventRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Payment buildPayment(String idempotencyKey) {
        return Payment.builder()
                .idempotencyKey(idempotencyKey)
                .merchantId(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .build();
    }
}
