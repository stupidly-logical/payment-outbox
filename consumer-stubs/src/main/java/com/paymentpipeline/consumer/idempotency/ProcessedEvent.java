package com.paymentpipeline.consumer.idempotency;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @EmbeddedId
    private ProcessedEventId id;

    @Column(nullable = false, updatable = false)
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        processedAt = Instant.now();
    }

    public ProcessedEvent() {}

    public ProcessedEvent(String eventId, String consumerGroup) {
        this.id = new ProcessedEventId(eventId, consumerGroup);
    }

    public ProcessedEventId getId()      { return id; }
    public Instant getProcessedAt()      { return processedAt; }
}