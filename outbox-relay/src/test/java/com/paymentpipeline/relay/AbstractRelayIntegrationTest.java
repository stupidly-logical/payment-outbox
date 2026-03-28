package com.paymentpipeline.relay;

import com.paymentpipeline.outbox.OutboxEventRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = TestOutboxRelayApplication.class   // avoids ambiguity with OutboxRelayApplication
)
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractRelayIntegrationTest {

    // ------------------------------------------------------------------
    // Shared containers (started once per JVM, reused across test classes)
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
        registry.add("spring.kafka.producer.properties.schema.registry.url",
                redpanda::getSchemaRegistryAddress);
    }

    // ------------------------------------------------------------------
    // Injected beans
    // ------------------------------------------------------------------

    @Autowired
    protected OutboxEventRepository outboxEventRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // Per-test lifecycle
    // ------------------------------------------------------------------

    @BeforeEach
    void cleanDb() {
        jdbcTemplate.execute("DELETE FROM outbox_events");
        jdbcTemplate.execute("DELETE FROM payments");
    }

    @AfterEach
    void closeConsumers() {
        // Subclasses may override to close test consumers
    }

    // ------------------------------------------------------------------
    // Helpers — insert test data bypassing JPA (relay doesn't own payments)
    // ------------------------------------------------------------------

    protected UUID insertPayment() {
        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payments
                    (id, idempotency_key, merchant_id, amount, currency, status, version,
                     created_at, updated_at)
                VALUES (?, ?, ?, 100.00, 'USD', 'INITIATED', 0, NOW(), NOW())
                """,
                paymentId,
                UUID.randomUUID().toString(),
                UUID.randomUUID());
        return paymentId;
    }

    protected UUID insertPendingOutboxEvent(UUID paymentId) {
        return insertOutboxEvent(paymentId, 0);
    }

    protected UUID insertOutboxEvent(UUID paymentId, int retryCount) {
        UUID eventId = UUID.randomUUID();
        String payload = """
                {"paymentId":"%s","merchantId":"%s","status":"INITIATED",
                 "amount":"100.0000","currency":"USD","failureReason":null}
                """.formatted(paymentId, UUID.randomUUID());

        jdbcTemplate.update("""
                INSERT INTO outbox_events
                    (id, payment_id, event_type, payload, status, retry_count,
                     created_at, updated_at)
                VALUES (?, ?, 'PAYMENT_INITIATED', ?::jsonb, 'PENDING', ?, NOW(), NOW())
                """,
                eventId, paymentId, payload, retryCount);
        return eventId;
    }

    // ------------------------------------------------------------------
    // Helper — create a raw-string Kafka consumer for topic assertion
    // ------------------------------------------------------------------

    /**
     * Creates a KafkaConsumer that reads raw string key/value pairs.
     * Uses a unique group ID per call so every test reads from offset 0.
     */
    protected KafkaConsumer<String, String> createTestConsumer(String... topics) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()
        ));
        consumer.subscribe(List.of(topics));
        return consumer;
    }

    /**
     * Polls the given consumer until no more records arrive within the timeout,
     * and returns all collected records.
     */
    protected List<ConsumerRecord<String, String>> drainTopic(
            KafkaConsumer<String, String> consumer, int expectedAtLeast, Duration timeout) {

        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            var records = consumer.poll(Duration.ofMillis(200));
            records.forEach(collected::add);
            if (collected.size() >= expectedAtLeast) break;
        }
        return collected;
    }
}
