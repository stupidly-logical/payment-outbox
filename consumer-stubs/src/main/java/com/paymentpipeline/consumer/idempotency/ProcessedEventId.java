package com.paymentpipeline.consumer.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ProcessedEventId implements Serializable {

    @Column(name = "event_id", length = 255)
    private String eventId;

    @Column(name = "consumer_group", length = 100)
    private String consumerGroup;

    public ProcessedEventId() {}

    public ProcessedEventId(String eventId, String consumerGroup) {
        this.eventId       = eventId;
        this.consumerGroup = consumerGroup;
    }

    public String getEventId()       { return eventId; }
    public String getConsumerGroup() { return consumerGroup; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProcessedEventId that)) return false;
        return Objects.equals(eventId, that.eventId) &&
                Objects.equals(consumerGroup, that.consumerGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, consumerGroup);
    }
}