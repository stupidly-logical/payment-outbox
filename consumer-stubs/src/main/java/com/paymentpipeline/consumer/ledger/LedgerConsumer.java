package com.paymentpipeline.consumer.ledger;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LedgerConsumer {

    private static final Logger log = LoggerFactory.getLogger(LedgerConsumer.class);
    private static final String GROUP = "ledger-consumers";

    // Field injection is required so that @SpyBean on ProcessedEventRepository
    // is re-injected into this bean after the Spring test context is loaded.
    @Autowired
    ProcessedEventRepository processedEventRepository;

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

        log.info("LEDGER | type={} payment={} amount={} {}",
                event.getEventType(),
                event.getPaymentId(),
                event.getAmount(),
                event.getCurrency());

        processedEventRepository.save(new ProcessedEvent(eventId, GROUP));
    }
}