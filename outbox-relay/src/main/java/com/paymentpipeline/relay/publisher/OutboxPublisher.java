package com.paymentpipeline.relay.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentpipeline.outbox.OutboxEvent;
import com.paymentpipeline.shared.Topics;
import com.paymentpipeline.shared.avro.PaymentEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

@Component
public class OutboxPublisher {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(KafkaTemplate<String, PaymentEvent> kafkaTemplate,
                           ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper  = objectMapper;
    }

    public void publish(OutboxEvent event) {
        PaymentEvent avroEvent = toAvro(event);

        // Partition key = paymentId → ordering guaranteed per payment
        try {
            kafkaTemplate.send(Topics.PAYMENT_EVENTS,
                            event.getPaymentId().toString(),
                            avroEvent)
                    .get(); // block to get Kafka ACK before marking published
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public void publishToDlt(OutboxEvent event) {
        PaymentEvent avroEvent = toAvro(event);
        kafkaTemplate.send(Topics.PAYMENT_EVENTS_DLT,
                event.getPaymentId().toString(),
                avroEvent);
    }

    private PaymentEvent toAvro(OutboxEvent event) {
        try {
            var payload = objectMapper.readTree(event.getPayload());
            return PaymentEvent.newBuilder()
                    .setEventId(UUID.randomUUID().toString())
                    .setPaymentId(event.getPaymentId().toString())
                    .setMerchantId(payload.get("merchantId").asText())
                    .setEventType(event.getEventType())
                    .setStatus(payload.get("status").asText())
                    .setAmount(payload.get("amount").asText())
                    .setCurrency(payload.get("currency").asText())
                    .setOccurredAt(Instant.now().toEpochMilli())
                    .setFailureReason(
                            payload.has("failureReason")
                                    ? payload.get("failureReason").asText(null)
                                    : null)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Avro event", e);
        }
    }
}