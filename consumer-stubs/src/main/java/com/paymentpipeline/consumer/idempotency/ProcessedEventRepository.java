package com.paymentpipeline.consumer.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository
        extends JpaRepository<ProcessedEvent, ProcessedEventId> {

    boolean existsByIdEventIdAndIdConsumerGroup(
            String eventId, String consumerGroup);

    long countByIdConsumerGroup(String consumerGroup);
}