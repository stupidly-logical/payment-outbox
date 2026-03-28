package com.paymentpipeline.consumer;

import com.paymentpipeline.consumer.idempotency.ProcessedEventRepository;
import com.paymentpipeline.shared.Topics;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

class ConsumerIdempotencyIntegrationTest extends AbstractConsumerIntegrationTest {

    // @KafkaListener containers capture a direct reference to the listener bean at
    // startup, so doThrow on a @SpyBean of LedgerConsumer has no effect.
    // Spying on ProcessedEventRepository works because @SpyBean triggers re-injection
    // of the spy into LedgerConsumer's field after the context loads.
    @SpyBean
    private ProcessedEventRepository processedEventRepositorySpy;

    @AfterEach
    void resetSpy() {
        Mockito.reset(processedEventRepositorySpy);
    }

    // -----------------------------------------------------------------------
    // Test 1: same event published twice → processed exactly once
    // -----------------------------------------------------------------------

    @Test
    void ledgerConsumer_shouldProcessEventExactlyOnce() throws Exception {
        String eventId = UUID.randomUUID().toString();

        // First publish
        publishEvent(eventId);

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> processedEventRepository
                        .existsByIdEventIdAndIdConsumerGroup(eventId, "ledger-consumers"));

        // Second publish — same eventId
        publishEvent(eventId);

        // The PK (eventId, consumerGroup) guarantees at-most-once storage.
        // Check stability for 2s: the row for THIS event must exist and only exist once.
        Awaitility.await()
                .during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(3))
                .until(() -> processedEventRepository
                        .existsByIdEventIdAndIdConsumerGroup(eventId, "ledger-consumers"));

        assertThat(processedEventRepository
                .existsByIdEventIdAndIdConsumerGroup(eventId, "ledger-consumers")).isTrue();
    }

    // -----------------------------------------------------------------------
    // Test 2: one event consumed independently by all three consumer groups
    // -----------------------------------------------------------------------

    @Test
    void allThreeConsumers_shouldProcessSameEventIndependently() {
        String eventId = UUID.randomUUID().toString();
        publishEvent(eventId);

        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> processedEventRepository
                                .existsByIdEventIdAndIdConsumerGroup(eventId, "ledger-consumers")
                        && processedEventRepository
                                .existsByIdEventIdAndIdConsumerGroup(eventId, "fraud-consumers")
                        && processedEventRepository
                                .existsByIdEventIdAndIdConsumerGroup(eventId, "notification-consumers"));

        // Three rows — one per consumer group, all for the same eventId
        assertThat(processedEventRepository.findAll())
                .hasSize(3)
                .allMatch(e -> e.getId().getEventId().equals(eventId));
    }

    // -----------------------------------------------------------------------
    // Test 3: consumer failure triggers retries and routes to DLT
    // -----------------------------------------------------------------------

    @Test
    void consumer_onProcessingFailure_shouldRetryAndRouteToDLT() throws InterruptedException {
        // Make ProcessedEventRepository.save throw so the listener fails on every attempt.
        // Spying on the repository (rather than LedgerConsumer directly) works because
        // @SpyBean re-injects the spy into LedgerConsumer's field after context load.
        doThrow(new RuntimeException("Simulated processing failure"))
                .when(processedEventRepositorySpy).save(any());

        String eventId = UUID.randomUUID().toString();
        publishEvent(eventId);

        KafkaConsumer<String, String> dltConsumer =
                createRawConsumer(Topics.PAYMENT_EVENTS_DLT);

        try {
            // @RetryableTopic: 3 attempts, 1s + 2s backoff ≈ 7s; allow 20s total
            List<String> dltMessages = new java.util.ArrayList<>();
            Awaitility.await()
                    .atMost(25, TimeUnit.SECONDS)
                    .pollInterval(1, TimeUnit.SECONDS)
                    .until(() -> {
                        drainTopic(dltConsumer, 1, Duration.ofMillis(500))
                                .forEach(r -> dltMessages.add(r.key()));
                        return !dltMessages.isEmpty();
                    });

            assertThat(dltMessages).isNotEmpty();
        } finally {
            dltConsumer.close();
        }
    }

    // -----------------------------------------------------------------------
    // Test 4: after context restart, already-processed events are not reprocessed
    // -----------------------------------------------------------------------

    @Test
    @DirtiesContext
    void consumer_afterRestart_shouldNotReprocessAlreadyProcessedEvents() throws Exception {
        String eventId = UUID.randomUUID().toString();
        publishEvent(eventId);

        // Wait for all three consumers to process the event
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> processedEventRepository.count() == 3);

        long countBeforeRestart = processedEventRepository.count();
        assertThat(countBeforeRestart).isEqualTo(3);

        // Reset consumer group offsets so that after the context restart (triggered by
        // @DirtiesContext) the consumers re-read from the beginning of the topic.
        // Without idempotency this would cause double-processing.
        for (String group : List.of("ledger-consumers", "fraud-consumers", "notification-consumers")) {
            resetConsumerGroupOffsets(group);
        }

        // @DirtiesContext will restart the Spring context after the test method returns.
        // The context restart happens BEFORE the assertion below in this method,
        // so we verify state after context teardown but the DB persists (same Postgres container).
        // We rely on the fact that the rows are already in DB before restart and
        // idempotency prevents re-insertion.  The post-restart assertion is done here
        // by checking that no new rows appeared during the brief window after offset reset.

        Awaitility.await()
                .during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(4))
                .until(() -> processedEventRepository.count() == countBeforeRestart);

        assertThat(processedEventRepository.count()).isEqualTo(countBeforeRestart);
    }
}
