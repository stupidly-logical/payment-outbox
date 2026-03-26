package com.paymentpipeline.relay.poller;

import com.paymentpipeline.outbox.OutboxEvent;
import com.paymentpipeline.outbox.OutboxEventRepository;
import com.paymentpipeline.relay.publisher.OutboxPublisher;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRIES = 3;

    private final OutboxEventRepository outboxRepository;
    private final OutboxPublisher       publisher;

    public OutboxPoller(OutboxEventRepository outboxRepository,
                        OutboxPublisher publisher,
                        MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.publisher        = publisher;

        // Register outbox lag as a live gauge
        Gauge.builder("outbox.pending.count", outboxRepository,
                        repo -> repo.countByStatus("PENDING"))
                .description("Number of unpublished outbox events")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 500)   // runs 500ms after previous execution completes
    @Transactional
    public void poll() {
        List<OutboxEvent> batch =
                outboxRepository.findPendingEventsForUpdate(BATCH_SIZE);

        if (batch.isEmpty()) return;

        log.debug("Polling outbox: {} events to process", batch.size());

        for (OutboxEvent event : batch) {
            try {
                publisher.publish(event);
                event.markPublished();
            } catch (Exception ex) {
                log.warn("Failed to publish outbox event {}: {}",
                        event.getId(), ex.getMessage());

                event.incrementRetry(ex.getMessage());

                if (event.getRetryCount() >= MAX_RETRIES) {
                    log.error("Outbox event {} exceeded max retries, routing to DLT",
                            event.getId());
                    publisher.publishToDlt(event);
                    event.markFailed(ex.getMessage());
                }
            }
            outboxRepository.save(event);
        }
    }
}