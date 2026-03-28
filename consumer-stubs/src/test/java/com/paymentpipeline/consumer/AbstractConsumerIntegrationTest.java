package com.paymentpipeline.consumer;

import com.paymentpipeline.consumer.idempotency.ProcessedEventRepository;
import com.paymentpipeline.shared.Topics;
import com.paymentpipeline.shared.avro.PaymentEvent;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = TestConsumerApplication.class)
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractConsumerIntegrationTest {

    // ------------------------------------------------------------------
    // Shared containers
    // ------------------------------------------------------------------

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("payments")
                    .withUsername("payments")
                    .withPassword("payments");

    @Container
    static RedpandaContainer redpanda =
            new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.1.1");

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", redpanda::getBootstrapServers);
        registry.add("spring.kafka.consumer.properties.schema.registry.url",
                redpanda::getSchemaRegistryAddress);
        registry.add("spring.kafka.producer.properties.schema.registry.url",
                redpanda::getSchemaRegistryAddress);
    }

    // ------------------------------------------------------------------
    // Injected beans
    // ------------------------------------------------------------------

    @Autowired
    protected ProcessedEventRepository processedEventRepository;

    @Autowired
    protected KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    // ------------------------------------------------------------------
    // Lazily-built test producer (Avro, using Redpanda's schema registry)
    // ------------------------------------------------------------------

    private KafkaTemplate<String, PaymentEvent> testProducer;

    protected KafkaTemplate<String, PaymentEvent> testProducer() {
        if (testProducer == null) {
            Map<String, Object> props = Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class,
                    KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG,
                            redpanda.getSchemaRegistryAddress()
            );
            testProducer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }
        return testProducer;
    }

    // ------------------------------------------------------------------
    // Per-test cleanup
    // ------------------------------------------------------------------

    @BeforeEach
    void cleanDb() {
        processedEventRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    protected PaymentEvent buildPaymentEvent(String eventId) {
        return PaymentEvent.newBuilder()
                .setEventId(eventId)
                .setPaymentId(UUID.randomUUID().toString())
                .setMerchantId(UUID.randomUUID().toString())
                .setEventType("PAYMENT_INITIATED")
                .setStatus("INITIATED")
                .setAmount("100.0000")
                .setCurrency("USD")
                .setOccurredAt(Instant.now().toEpochMilli())
                .setFailureReason(null)
                .build();
    }

    protected void publishEvent(String eventId) {
        PaymentEvent event = buildPaymentEvent(eventId);
        testProducer().send(Topics.PAYMENT_EVENTS, event.getPaymentId().toString(), event);
    }

    /**
     * Creates a raw-string consumer with a unique group ID to read topic from offset 0.
     * Caller is responsible for closing it.
     */
    protected KafkaConsumer<String, String> createRawConsumer(String... topics) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-raw-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()
        ));
        consumer.subscribe(List.of(topics));
        return consumer;
    }

    protected List<ConsumerRecord<String, String>> drainTopic(
            KafkaConsumer<String, String> consumer, int expected, Duration timeout) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(300)).forEach(collected::add);
            if (collected.size() >= expected) break;
        }
        return collected;
    }

    /**
     * Resets the consumer group offsets for all consumers in a given group to the
     * beginning.  This forces a context-restart test to re-read already-processed
     * messages, proving idempotency guards against double processing.
     */
    protected void resetConsumerGroupOffsets(String groupId) throws Exception {
        // Stop all listener containers so the consumer groups become inactive.
        // Kafka/Redpanda forbids deleting committed offsets while members are active.
        kafkaListenerEndpointRegistry.stop();

        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers()
        ))) {
            // Fetch current partition count for the topic
            var descriptions = admin.describeTopics(List.of(Topics.PAYMENT_EVENTS)).all().get();
            var partitions = descriptions.get(Topics.PAYMENT_EVENTS)
                    .partitions().stream()
                    .map(p -> new TopicPartition(Topics.PAYMENT_EVENTS, p.partition()))
                    .toList();

            // deleteConsumerGroupOffsets removes the committed offset so the group
            // resets to auto-offset-reset=earliest on next start
            admin.deleteConsumerGroupOffsets(groupId, new java.util.HashSet<>(partitions)).all().get();
        }
    }
}
