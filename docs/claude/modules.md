# Modules

## shared
Jar only — no Spring Boot app. Consumed by all other modules.

| File | Purpose |
|---|---|
| `PaymentStatus.java` | Enum with `canTransitionTo()` state machine |
| `Topics.java` | `PAYMENT_EVENTS = "payments.events"`, `PAYMENT_EVENTS_DLT` |
| `PaymentEvent.avsc` | Avro schema → generates `PaymentEvent.java` at build time |

---

## payment-service (port 8080)

Entry point: `PaymentServiceApplication`

### Main Classes

| Class | Role |
|---|---|
| `PaymentController` | REST: POST initiate, PATCH authorize/settle/fail |
| `PaymentService` | Business logic + outbox write (single transaction) |
| `Payment` | JPA entity, `@Version` optimistic lock, `transitionTo()` / `fail()` |
| `OutboxEvent` | JPA entity for outbox table, `markPublished()` / `markFailed()` |
| `OutboxEventRepository` | `findPendingEventsForUpdate(batchSize)` — native SQL, `FOR UPDATE SKIP LOCKED` |
| `PaymentRepository` | `findByIdempotencyKey()` for idempotent payment creation |

Schema migrations: `V1__create_payments.sql`, `V2__create_outbox_events.sql`

### Test Classes

| File | Type | What it covers |
|---|---|---|
| `AbstractIntegrationTest` | Base | Static `PostgreSQLContainer`, `@DynamicPropertySource`, `@BeforeEach` table cleanup |
| `domain/PaymentStateMachineTest` | Unit | `canTransitionTo()` valid/invalid; terminal states via `@ParameterizedTest`; `Payment.transitionTo()` and `fail()` |
| `service/PaymentServiceIntegrationTest` | Integration | Atomic payment+outbox write; idempotency key; per-transition events; illegal transition rollback; `failPayment`; optimistic locking with `CountDownLatch` + `NOT_SUPPORTED` propagation |

No Kafka in this module — payment-service has no `spring-kafka` dependency.

---

## outbox-relay (port 8085)

Entry point: `OutboxRelayApplication` (`@EnableScheduling`, custom `scanBasePackages` to pick up `OutboxEvent`/`OutboxEventRepository` from payment-service jar)

### Main Classes

| Class | Role |
|---|---|
| `OutboxPoller` | `@Scheduled(fixedDelay=500)` — polls, delegates to publisher, saves result |
| `OutboxPublisher` | Converts `OutboxEvent` JSON payload → `PaymentEvent` Avro, sends to Kafka, blocks on `.get()` for ACK |
| `KafkaConfig` | Producer factory: `acks=all`, `enable.idempotence=true`, `retries=3` |

Metric registered: `outbox.pending.count` (Gauge) — watch this in Grafana.

### Test Classes

| File | Type | What it covers |
|---|---|---|
| `AbstractRelayIntegrationTest` | Base | Static `PostgreSQLContainer` + `RedpandaContainer`; `@DynamicPropertySource`; JDBC helpers for inserting payments/outbox rows; raw Kafka consumer helper |
| `TestOutboxRelayApplication` | Test bootstrap | Mirrors `OutboxRelayApplication` scan config but **omits `@EnableScheduling`** — prevents scheduler from running during tests |
| `OutboxRelayIntegrationTest` | Integration | See table below |
| `application-test.yml` | Config | Enables Flyway (`classpath:db/migration`) so payment-service schema is created in the test DB |

`OutboxRelayIntegrationTest` tests (ordered):

| # | Test | Technique |
|---|---|---|
| 1 | Publish PENDING event → PUBLISHED + Kafka message | Real Kafka consumer drain |
| 2 | Status flips only after ACK | Verify PUBLISHED synchronously after `poll()` |
| 3 | Kafka failure → retry_count increments, stays PENDING | `@SpyBean` + `doThrow` |
| 4 | retry_count=2 + failure → FAILED + DLT message | `@SpyBean` + real DLT consumer |
| 5 | Two concurrent `poll()` calls → no duplicates on 10 events | `ExecutorService`, count Kafka messages |
| 6 | Partial batch crash → second `poll()` completes remainder | `Mockito.doAnswer` call count gate, `reset(spy)` |

---

## consumer-stubs (port 8083)

Entry point: `ConsumerApplication`

Three `@KafkaListener` beans — all follow identical pattern:

```
1. Deserialize ConsumerRecord<String, PaymentEvent>
2. Check processed_events for (eventId, consumerGroup) — skip if found
3. Do work (log / evaluate fraud rules / dispatch notification)
4. INSERT processed_events (eventId, consumerGroup)
```

### Main Classes

| Consumer | groupId | Extra logic |
|---|---|---|
| `LedgerConsumer` | `ledger-consumers` | Log only |
| `FraudConsumer` | `fraud-consumers` | Flag amount > 10,000 |
| `NotificationConsumer` | `notification-consumers` | Switch on eventType for message |
| `ProcessedEvent` | — | JPA entity with `@EmbeddedId ProcessedEventId(eventId, consumerGroup)` |
| `ProcessedEventRepository` | — | `existsByIdEventIdAndIdConsumerGroup()`, `countByIdConsumerGroup()` |
| `KafkaConsumerConfig` | — | `ErrorHandlingDeserializer` wrapping `KafkaAvroDeserializer`, `AckMode.RECORD` |

All consumers use `@RetryableTopic(attempts=3, backoff exponential)` with auto-created retry and DLT topics.

Schema migration: `V1__create_processed_events.sql` (runs via its own `flyway_schema_history_consumers` table).

### Test Classes

| File | Type | What it covers |
|---|---|---|
| `AbstractConsumerIntegrationTest` | Base | Static `PostgreSQLContainer` + `RedpandaContainer`; lazily-built Avro `KafkaTemplate` (test producer); `resetConsumerGroupOffsets()` helper; `@BeforeEach` table cleanup |
| `TestConsumerApplication` | Test bootstrap | Standard `@SpringBootApplication` for the consumer package |
| `ConsumerIdempotencyIntegrationTest` | Integration | See table below |
| `application-test.yml` | Config | Shorter Kafka session/poll timeouts for faster test feedback |

`ConsumerIdempotencyIntegrationTest` tests:

| # | Test | Technique |
|---|---|---|
| 1 | Same eventId published twice → one `processed_events` row | Awaitility + `during()` stability check |
| 2 | One event → three rows, one per consumer group | Awaitility waits for all 3 groups |
| 3 | Processing throws → `@RetryableTopic` exhausts → DLT | `@SpyBean LedgerConsumer` + `doThrow`, raw DLT consumer |
| 4 | Context restart + offset reset → no double processing | `@DirtiesContext` + `resetConsumerGroupOffsets()` |
