package com.paymentpipeline.domain;

import com.paymentpipeline.shared.PaymentStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private UUID merchantId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.INITIATED;

    private String failureReason;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
        if (status == null) status = PaymentStatus.INITIATED;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void transitionTo(PaymentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Cannot transition payment %s from %s to %s"
                            .formatted(id, status, next)
            );
        }
        this.status = next;
    }

    public void fail(String reason) {
        transitionTo(PaymentStatus.FAILED);
        this.failureReason = reason;
    }

    // Getters — no setters except via domain methods above
    public UUID getId()                  { return id; }
    public String getIdempotencyKey()    { return idempotencyKey; }
    public UUID getMerchantId()          { return merchantId; }
    public BigDecimal getAmount()        { return amount; }
    public String getCurrency()          { return currency; }
    public PaymentStatus getStatus()     { return status; }
    public String getFailureReason()     { return failureReason; }
    public Long getVersion()             { return version; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getUpdatedAt()        { return updatedAt; }

    // Builder
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final Payment p = new Payment();
        public Builder idempotencyKey(String k) { p.idempotencyKey = k; return this; }
        public Builder merchantId(UUID m)        { p.merchantId = m; return this; }
        public Builder amount(BigDecimal a)      { p.amount = a; return this; }
        public Builder currency(String c)        { p.currency = c; return this; }
        public Payment build()                   { return p; }
    }
}