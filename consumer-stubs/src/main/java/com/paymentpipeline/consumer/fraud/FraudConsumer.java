package com.paymentpipeline.consumer.fraud;

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
public class FraudConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudConsumer.class);
    private static final String GROUP = "fraud-consumers";

    private final ProcessedEventRepository processedEventRepository;

    public FraudConsumer(ProcessedEventRepository processedEventRepository) {
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

        log.info("FRAUD | type={} payment={} amount={} {}",
                event.getEventType(),
                event.getPaymentId(),
                event.getAmount(),
                event.getCurrency());

        // Stub: real implementation would run fraud rules engine
        evaluateFraudRules(event);

        processedEventRepository.save(new ProcessedEvent(eventId, GROUP));
    }

    private void evaluateFraudRules(PaymentEvent event) {
        // Stub: flag payments over 10000 as high risk
        try {
            var amount = new java.math.BigDecimal(event.getAmount().toString());
            if (amount.compareTo(new java.math.BigDecimal("10000")) > 0) {
                log.warn("FRAUD | HIGH RISK payment={} amount={}",
                        event.getPaymentId(), event.getAmount());
            }
        } catch (NumberFormatException e) {
            log.error("FRAUD | Could not parse amount {}", event.getAmount());
        }
    }
}