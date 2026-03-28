package com.paymentpipeline.relay;

import com.paymentpipeline.outbox.OutboxEvent;
import com.paymentpipeline.relay.poller.OutboxPoller;
import com.paymentpipeline.relay.publisher.OutboxPublisher;
import com.paymentpipeline.shared.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxRelayIntegrationTest extends AbstractRelayIntegrationTest {

    @Autowired
    private OutboxPoller outboxPoller;

    @SpyBean
    private OutboxPublisher publisherSpy;

    private final List<KafkaConsumer<String, String>> openConsumers = new ArrayList<>();

    @AfterEach
    @Override
    void closeConsumers() {
        openConsumers.forEach(KafkaConsumer::close);
        openConsumers.clear();
        reset(publisherSpy);
    }

    // -----------------------------------------------------------------------
    // Test 1: poll publishes event to Kafka and marks it PUBLISHED
    // -----------------------------------------------------------------------

    @Test
    @org.junit.jupiter.api.Order(1)
    void poll_shouldPublishPendingEventsToKafka() {
        UUID paymentId = insertPayment();
        UUID eventId   = insertPendingOutboxEvent(paymentId);

        KafkaConsumer<String, String> consumer =
                createTestConsumer(Topics.PAYMENT_EVENTS);
        openConsumers.add(consumer);

        outboxPoller.poll();

        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo("PUBLISHED");

        List<ConsumerRecord<String, String>> messages =
                drainTopic(consumer, 1, Duration.ofSeconds(5));
        assertThat(messages).hasSize(1);
        // Partition key must equal the paymentId (ordering guarantee per payment)
        assertThat(messages.get(0).key()).isEqualTo(paymentId.toString());
    }

    // -----------------------------------------------------------------------
    // Test 2: status only becomes PUBLISHED after Kafka ACK (.get() semantics)
    // -----------------------------------------------------------------------

    @Test
    @org.junit.jupiter.api.Order(2)
    void poll_shouldMarkEventPublished_onlyAfterKafkaAck() {
        UUID paymentId = insertPayment();
        UUID eventId   = insertPendingOutboxEvent(paymentId);

        outboxPoller.poll();

        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
        // If .get() was not blocking, the event might still be PENDING at this point
        assertThat(event.getStatus()).isEqualTo("PUBLISHED");
    }

    // -----------------------------------------------------------------------
    // Test 3: Kafka failure increments retry count; status stays PENDING
    // -----------------------------------------------------------------------

    @Test
    @org.junit.jupiter.api.Order(3)
    void poll_withKafkaFailure_shouldIncrementRetryCountAndStayPending() {
        doThrow(new RuntimeException("Kafka unavailable"))
                .when(publisherSpy).publish(any());

        UUID paymentId = insertPayment();
        UUID eventId   = insertPendingOutboxEvent(paymentId);

        // Run two poll cycles — each increments retry once
        outboxPoller.poll();
        outboxPoller.poll();

        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getRetryCount()).isEqualTo(2);
        assertThat(event.getStatus()).isEqualTo("PENDING");
    }

    // -----------------------------------------------------------------------
    // Test 4: event at retry_count=2 routes to DLT on next failure
    // -----------------------------------------------------------------------

    @Test
    @org.junit.jupiter.api.Order(4)
    void poll_afterMaxRetries_shouldRouteToDeadLetterTopic() {
        doThrow(new RuntimeException("Kafka unavailable"))
                .when(publisherSpy).publish(any());

        UUID paymentId = insertPayment();
        // Insert with retry_count = 2 (one away from MAX_RETRIES=3)
        UUID eventId = insertOutboxEvent(paymentId, 2);

        KafkaConsumer<String, String> dltConsumer =
                createTestConsumer(Topics.PAYMENT_EVENTS_DLT);
        openConsumers.add(dltConsumer);

        outboxPoller.poll();

        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo("FAILED");

        List<ConsumerRecord<String, String>> dltMessages =
                drainTopic(dltConsumer, 1, Duration.ofSeconds(5));
        assertThat(dltMessages).hasSize(1);
        assertThat(dltMessages.get(0).key()).isEqualTo(paymentId.toString());
    }

    // -----------------------------------------------------------------------
    // Test 5: SKIP LOCKED — two concurrent pollers do not duplicate-publish
    // -----------------------------------------------------------------------

    @Test
    @org.junit.jupiter.api.Order(5)
    void poll_skipLocked_concurrentPollers_shouldNotDuplicatePublish()
            throws InterruptedException {

        // Insert 10 events across 10 distinct payments
        for (int i = 0; i < 10; i++) {
            UUID pid = insertPayment();
            insertPendingOutboxEvent(pid);
        }

        KafkaConsumer<String, String> consumer =
                createTestConsumer(Topics.PAYMENT_EVENTS);
        openConsumers.add(consumer);

        // Wait for partition assignment then seek to end so messages published
        // by earlier tests on this topic are not counted.
        // Call position() to force eager resolution before pollers run.
        while (consumer.assignment().isEmpty()) {
            consumer.poll(Duration.ofMillis(100));
        }
        consumer.seekToEnd(consumer.assignment());
        consumer.assignment().forEach(consumer::position);

        // Run two pollers concurrently — SKIP LOCKED ensures each event is
        // claimed by exactly one poller
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> outboxPoller.poll());
        pool.submit(() -> outboxPoller.poll());
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        // All 10 events must be PUBLISHED
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).allMatch(e -> "PUBLISHED".equals(e.getStatus()));

        // Exactly 10 messages on the topic — no duplicates
        List<ConsumerRecord<String, String>> messages =
                drainTopic(consumer, 10, Duration.ofSeconds(10));
        assertThat(messages).hasSize(10);
        long distinctKeys = messages.stream().map(ConsumerRecord::key).distinct().count();
        assertThat(distinctKeys).isEqualTo(10);
    }

    // -----------------------------------------------------------------------
    // Test 6: crash recovery — unpublished events are picked up on next poll
    // -----------------------------------------------------------------------

    @Test
    @org.junit.jupiter.api.Order(6)
    void relay_restartAfterCrash_shouldResumeFromUnpublishedEvents() {
        // Insert 5 events
        for (int i = 0; i < 5; i++) {
            UUID pid = insertPayment();
            insertPendingOutboxEvent(pid);
        }

        // Simulate partial processing: publish 2, then fail the rest
        // We do this by counting calls — first 2 delegate to real publisher,
        // subsequent calls throw.
        int[] callCount = {0};
        Mockito.doAnswer(inv -> {
            callCount[0]++;
            if (callCount[0] > 2) throw new RuntimeException("crash");
            return inv.callRealMethod();
        }).when(publisherSpy).publish(any());

        outboxPoller.poll(); // 2 succeed, 3 fail → retryCount++

        // Verify 2 PUBLISHED, 3 still PENDING (retry count 1)
        long published = outboxEventRepository.findAll().stream()
                .filter(e -> "PUBLISHED".equals(e.getStatus())).count();
        long pending = outboxEventRepository.findAll().stream()
                .filter(e -> "PENDING".equals(e.getStatus())).count();
        assertThat(published).isEqualTo(2);
        assertThat(pending).isEqualTo(3);

        // "Restart": reset the spy so the publisher works normally again
        reset(publisherSpy);

        // Second poll processes the remaining 3
        outboxPoller.poll();

        assertThat(outboxEventRepository.findAll())
                .allMatch(e -> "PUBLISHED".equals(e.getStatus()));
    }
}
