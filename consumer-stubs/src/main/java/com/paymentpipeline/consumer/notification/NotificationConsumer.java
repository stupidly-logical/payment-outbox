package com.paymentpipeline.consumer.notification;

import com.paymentpipeline.consumer.idempotency.ProcessedEvent;
import com.paymentpipeline.consumer.idempotency.ProcessedEventRepository;
import com.paymentpipeline.shared.Topics;
import com.paymentpipeline.shared.avro.PaymentEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);
    private static final String GROUP = "notification-consumers";

    private final ProcessedEventRepository processedEventRepository;

    public NotificationConsumer(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltTopicSuffix = ".DLT"
    )
    @KafkaListener(
            topics = Topics.PAYMENT_EVENTS,
            groupId = GROUP
    )
    @Transactional
    public void onPaymentEvent(ConsumerRecord<String, PaymentEvent> record) {
        PaymentEvent event = record.value();
        String eventId = event.getEventId().toString();

        if (processedEventRepository
                .existsByIdEventIdAndIdConsumerGroup(eventId, GROUP)) {
            log.debug("Skipping duplicate event {} for group {}", eventId, GROUP);
            return;
        }

        log.info("NOTIFICATION | type={} payment={} merchant={}",
                event.getEventType(),
                event.getPaymentId(),
                event.getMerchantId());

        // Stub: real implementation would send email/SMS/webhook
        dispatchNotification(event);

        processedEventRepository.save(new ProcessedEvent(eventId, GROUP));
    }

    private void dispatchNotification(PaymentEvent event) {
        String message = switch (event.getEventType().toString()) {
            case "PAYMENT_INITIATED"          -> "Payment initiated for %s".formatted(event.getAmount());
            case "PAYMENT_AUTHORIZED"         -> "Payment authorised successfully";
            case "PAYMENT_SETTLEMENT_PENDING" -> "Payment settlement in progress";
            case "PAYMENT_SETTLED"            -> "Payment settled — funds transferred";
            case "PAYMENT_FAILED"             -> "Payment failed: %s".formatted(event.getFailureReason());
            default                           -> "Payment status updated: %s".formatted(event.getEventType());
        };
        log.info("NOTIFICATION | dispatch to merchant={} message='{}'",
                event.getMerchantId(), message);
    }
}