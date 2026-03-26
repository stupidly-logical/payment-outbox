package com.paymentpipeline.repository;

import com.paymentpipeline.domain.Payment;
import com.paymentpipeline.shared.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    long countByStatus(PaymentStatus status);
}