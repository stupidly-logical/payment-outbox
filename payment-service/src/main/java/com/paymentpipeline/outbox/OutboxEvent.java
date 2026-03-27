package com.paymentpipeline.outbox;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID paymentId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String payload;

    @Column(nullable = false)
    private String status;    // PENDING / PUBLISHED / FAILED

    @Column(nullable = false)
    private int retryCount;

    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
        status    = "PENDING";
        retryCount = 0;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public void markPublished() {
        this.status = "PUBLISHED";
    }

    public void markFailed(String error) {
        this.status = "FAILED";
        this.errorMessage = error;
    }

    public void incrementRetry(String error) {
        this.retryCount++;
        this.errorMessage = error;
    }

    // Getters
    public UUID getId()            { return id; }
    public UUID getPaymentId()     { return paymentId; }
    public String getEventType()   { return eventType; }
    public String getPayload()     { return payload; }
    public String getStatus()      { return status; }
    public int getRetryCount()     { return retryCount; }
    public String getErrorMessage(){ return errorMessage; }
    public Instant getCreatedAt()  { return createdAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final OutboxEvent e = new OutboxEvent();
        public Builder paymentId(UUID id)    { e.paymentId = id; return this; }
        public Builder eventType(String t)   { e.eventType = t; return this; }
        public Builder payload(String p)     { e.payload = p; return this; }
        public OutboxEvent build()           { return e; }
    }
}