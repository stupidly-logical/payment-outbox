package com.paymentpipeline.service;

import java.util.UUID;

public class PaymentNotFoundException extends Exception {
    public PaymentNotFoundException(UUID paymentId) {
    }
}
