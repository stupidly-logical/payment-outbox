package com.paymentpipeline.shared;

public enum PaymentStatus {
    INITIATED,
    AUTHORIZED,
    SETTLEMENT_PENDING,
    SETTLED,
    FAILED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case INITIATED          -> next == AUTHORIZED || next == FAILED;
            case AUTHORIZED         -> next == SETTLEMENT_PENDING || next == FAILED;
            case SETTLEMENT_PENDING -> next == SETTLED || next == FAILED;
            case SETTLED, FAILED    -> false;
        };
    }
}