# Modules

## shared
Jar only — no Spring Boot app. Consumed by all other modules.

| File | Purpose |
|---|---|
| `PaymentStatus.java` | Enum with `canTransitionTo()` state machine |
| `Topics.java` | `PAYMENT_EVENTS = "payments.events"`, `PAYMENT_EVENTS_DLT` |
| `PaymentEvent.avsc` | Avro schema → generates `PaymentEvent.java` at build time |

## payment-service (port 8080)

Entry point: `PaymentServiceApplication`

| Class | Role |
|---|---|
| `PaymentController` | REST: POST initiate, PATCH authorize/settle/fail |
| `PaymentService` | Business logic + outbox write (single transaction) |
| `Payment` | JPA entity, `@Version` optimistic lock, `transitionTo()` / `fail()` |
| `OutboxEvent` | JPA entity for outbox table, `markPublished()` / `markFailed()` |
| `OutboxEventRepository` | `findPendingEventsForUpdate(batchSize)` — native SQL, `FOR UPDATE SKIP LOCKED` |
| `PaymentRepository` | `findByIdempotencyKey()` for idempotent payment creation |

**Schema migrations:** `V2__create_outbox_events.sql`, `V3__create_processed_events.sql`
(Note: `V1__create_payments.sql` is in `consumer-stubs` — Flyway applies in version order across whichever service runs it first against the shared DB.)

## outbox-relay (port 8085)

Entry point: `OutboxRelayApplication` (`@EnableScheduling`)

| Class | Role |
|---|---|
| `OutboxPoller` | `@Scheduled(fixedDelay=500)` — polls, delegates to publisher, saves result |
| `OutboxPublisher` | Converts `OutboxEvent` JSON payload → `PaymentEvent` Avro, sends to Kafka |

Key config: `spring.kafka.producer.enable.idempotence=true`, `acks=all`, `retries=3`

Metric registered: `outbox.pending.count` (Gauge) — watch this in Grafana.

## consumer-stubs (port 8083)

Entry point: `ConsumerApplication`

Three `@KafkaListener` beans — all follow identical pattern:

```
1. Deserialize ConsumerRecord<String, PaymentEvent>
2. Check processed_events for (eventId, consumerGroup) — skip if found
3. Do work (log / evaluate fraud rules / dispatch notification)
4. INSERT processed_events (eventId, consumerGroup)
```

| Consumer | groupId | Extra logic |
|---|---|---|
| `LedgerConsumer` | `ledger-consumers` | Log only |
| `FraudConsumer` | `fraud-consumers` | Flag amount > 10,000 |
| `NotificationConsumer` | `notification-consumers` | Switch on eventType for message |

All use `@RetryableTopic(attempts=3, backoff exponential)` with auto-created retry and DLT topics.
