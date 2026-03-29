# Payment Pipeline

[![CI](https://github.com/stupidly-logical/payment-outbox/actions/workflows/ci.yml/badge.svg)](https://github.com/stupidly-logical/payment-outbox/actions/workflows/ci.yml)

A distributed, event-driven payment processing system built on the **Transactional Outbox Pattern**. Guarantees exactly-once event delivery and idempotent consumer processing using Kafka (Redpanda), PostgreSQL, and Spring Boot 3.

---

## Architecture Overview

```
                           ┌─────────────────────────────────────────────────────┐
                           │                   payment-service                    │
  Client ──── REST ──────► │  PaymentController → PaymentService                 │
                           │       ↓ (same @Transactional)                       │
                           │  payments table  +  outbox_events table (PENDING)   │
                           └──────────────────────────┬──────────────────────────┘
                                                      │ polls every 500ms
                           ┌──────────────────────────▼──────────────────────────┐
                           │                   outbox-relay                       │
                           │  OutboxPoller ──► OutboxPublisher                   │
                           │       FOR UPDATE SKIP LOCKED (batch 100)            │
                           │       marks PUBLISHED / FAILED (→ DLT after 3×)    │
                           └──────────────────────────┬──────────────────────────┘
                                                      │ Avro over Kafka
                                          ┌───────────▼────────────┐
                                          │   payments.events       │
                                          │   (Redpanda topic)      │
                                          └─────┬─────┬──────┬─────┘
                                                │     │      │
                           ┌────────────────────▼─┐ ┌─▼──┐ ┌▼──────────────────┐
                           │   ledger-consumers    │ │    │ │notification-       │
                           │   fraud-consumers     │ │DLT │ │consumers           │
                           │   (idempotent)        │ │    │ │(idempotent)        │
                           └───────────────────────┘ └────┘ └────────────────────┘
```

---

## Payment State Machine

```
                    ┌──────────┐
          POST ──►  │ INITIATED│
                    └────┬─────┘
              PATCH /authorize │
                    ┌────▼──────┐
                    │AUTHORIZED │
                    └────┬──────┘
               PATCH /settle │
                    ┌────▼───────────────┐
                    │ SETTLEMENT_PENDING  │
                    └────┬───────────────┘
                         │
                    ┌────▼──────┐
                    │  SETTLED  │  ← terminal
                    └───────────┘

   PATCH /fail ──► FAILED (from any non-terminal state)
```

Transitions are enforced at the domain layer via `PaymentStatus.canTransitionTo()`. Any illegal transition throws `IllegalStateException`.

---

## Modules

| Module | Port | Role |
|---|---|---|
| `shared` | — | Avro schema, Kafka topic constants, `PaymentStatus` enum |
| `payment-service` | 8080 | REST API, domain logic, outbox writes |
| `outbox-relay` | 8085 | Polls outbox table, publishes to Kafka |
| `consumer-stubs` | 8083 | Ledger, Fraud, Notification Kafka consumers |

---

## Key Design Patterns

### 1. Transactional Outbox
Payment state change and outbox event write happen in **one atomic transaction**. The relay service polls and publishes independently, ensuring events are never lost even on service crash.

```
BEGIN TRANSACTION
  UPDATE payments SET status = 'AUTHORIZED'
  INSERT INTO outbox_events (status = 'PENDING')  -- always both or neither
COMMIT
```

### 2. `FOR UPDATE SKIP LOCKED`
The outbox poller acquires row-level locks on pending events and skips rows locked by other relay instances. Safe for horizontal scaling of the relay.

### 3. Consumer-Side Idempotency
Each consumer group writes `(eventId, consumerGroup)` to `processed_events` before processing completes. Duplicate deliveries are silently skipped.

### 4. DLT (Dead Letter Topic)
Events that fail publishing 3 times are routed to `payments.events.DLT`. Consumers also have per-topic retry queues (suffix `-0`, `-1`) with a final DLT.

---

## Infrastructure

```
┌──────────────────────────────────────────────────────┐
│                   docker-compose                      │
│                                                       │
│  PostgreSQL 16      ← shared by all three services   │
│  Redpanda           ← Kafka-compatible broker        │
│  Schema Registry    ← Avro schema management         │
│  Prometheus         ← scrapes /actuator/prometheus   │
│  Grafana            ← dashboards (admin/admin)       │
└──────────────────────────────────────────────────────┘
```

| Service | Port |
|---|---|
| PostgreSQL | 5432 |
| Kafka (Redpanda) | 9092 |
| Schema Registry | 8081 |
| Prometheus | 9090 |
| Grafana | 3000 |

---

## Database Schema

```
payments
├── id               UUID PK
├── idempotency_key  VARCHAR UNIQUE     ← prevents duplicate payments
├── merchant_id      UUID
├── amount           NUMERIC(19,4)
├── currency         CHAR(3)
├── status           VARCHAR            ← enforced by CHECK constraint
├── failure_reason   TEXT
└── version          BIGINT             ← optimistic locking (@Version)

outbox_events
├── id            UUID PK
├── payment_id    UUID FK → payments.id
├── event_type    VARCHAR              ← e.g. PAYMENT_AUTHORIZED
├── payload       JSONB
├── status        VARCHAR              ← PENDING | PUBLISHED | FAILED
└── retry_count   INT

processed_events  (consumer idempotency)
├── event_id      UUID      ┐ composite PK
└── consumer_group VARCHAR  ┘
```

---

## Avro Event Schema

```json
PaymentEvent {
  eventId:       string
  paymentId:     string
  merchantId:    string
  eventType:     string        // PAYMENT_INITIATED, PAYMENT_AUTHORIZED, ...
  status:        string
  amount:        string        // string preserves NUMERIC precision
  currency:      string
  occurredAt:    long (timestamp-millis)
  failureReason: string | null
}
```

Kafka topic: `payments.events`  |  DLT: `payments.events.DLT`

---

## Getting Started

### Prerequisites

- Java 21
- Maven 3.6+
- Docker + Docker Compose

### Start Infrastructure

```bash
docker compose up -d
```

### Build All Modules

```bash
mvn clean install -DskipTests
```

### Run Services (separate terminals)

```bash
# 1. Payment Service
java -jar payment-service/target/payment-service-*-exec.jar

# 2. Outbox Relay
java -jar outbox-relay/target/outbox-relay-*-exec.jar

# 3. Consumer Stubs
java -jar consumer-stubs/target/consumer-stubs-*-exec.jar
```

---

## Testing

The test suite is self-contained — no running Docker Compose infrastructure required. All containers (PostgreSQL, Redpanda) are managed by **Testcontainers** and started automatically.

### Run All Tests

```bash
mvn test
```

### Run Per Module

```bash
mvn test -pl payment-service    # 10 tests — domain + service layer
mvn test -pl outbox-relay       # 6 tests  — outbox polling and Kafka publish
mvn test -pl consumer-stubs     # 4 tests  — consumer idempotency
```

### Test Architecture

Each module has a shared abstract base class that owns the Testcontainers lifecycle:

| Module | Base Class | Containers |
|---|---|---|
| `payment-service` | `AbstractIntegrationTest` | PostgreSQL 16 |
| `outbox-relay` | `AbstractRelayIntegrationTest` | PostgreSQL 16, Redpanda |
| `consumer-stubs` | `AbstractConsumerIntegrationTest` | PostgreSQL 16, Redpanda |

Redpanda's **built-in Schema Registry** is used instead of a separate `cp-schema-registry` container — simpler setup, no Docker networking complexity.

### Test Suites

#### `payment-service` — 10 tests

| Test Class | Tests |
|---|---|
| `PaymentStateMachineTest` | Pure unit — all valid/invalid transitions, terminal states (`@ParameterizedTest`) |
| `PaymentServiceIntegrationTest` | Atomic outbox write, idempotency key, per-transition events, illegal transition rejection, `failPayment`, optimistic locking with `CountDownLatch` |

#### `outbox-relay` — 6 tests (`OutboxRelayIntegrationTest`)

> `@EnableScheduling` is **disabled** in the test context (`TestOutboxRelayApplication`). Tests call `OutboxPoller.poll()` directly for full determinism — no timing races.

| Test | What it proves |
|---|---|
| `poll_shouldPublishPendingEventsToKafka` | Happy path: PENDING → PUBLISHED, message on topic, partition key = paymentId |
| `poll_shouldMarkEventPublished_onlyAfterKafkaAck` | `.get()` semantics: status only flips after broker ACK |
| `poll_withKafkaFailure_shouldIncrementRetryCountAndStayPending` | `@SpyBean` forces publish failure; retry_count increments, status stays PENDING |
| `poll_afterMaxRetries_shouldRouteToDeadLetterTopic` | event at retry_count=2 → one more failure → FAILED + message on DLT |
| `poll_skipLocked_concurrentPollers_shouldNotDuplicatePublish` | Two concurrent `poll()` calls on 10 events → exactly 10 Kafka messages, no duplicates |
| `relay_restartAfterCrash_shouldResumeFromUnpublishedEvents` | Partial batch publish, spy reset ("restart"), second poll completes all 5 events |

#### `consumer-stubs` — 4 tests (`ConsumerIdempotencyIntegrationTest`)

| Test | What it proves |
|---|---|
| `ledgerConsumer_shouldProcessEventExactlyOnce` | Same eventId published twice → exactly one `processed_events` row |
| `allThreeConsumers_shouldProcessSameEventIndependently` | One event → three rows, one per consumer group |
| `consumer_onProcessingFailure_shouldRetryAndRouteToDLT` | `@SpyBean` forces consumer throws → `@RetryableTopic` exhausts retries → message on DLT |
| `consumer_afterRestart_shouldNotReprocessAlreadyProcessedEvents` | `@DirtiesContext` + offset reset → idempotency prevents double insertion |

### Key Testing Decisions

- **`@SpyBean OutboxPublisher`** simulates Kafka failures in relay tests without stopping the shared Redpanda container.
- **`Propagation.NOT_SUPPORTED`** on the optimistic-locking test allows concurrent threads to hold independent DB transactions.
- **Awaitility** is used for all async assertions — `Thread.sleep()` is never used.
- **`@BeforeEach` table cleanup** ensures test isolation without `@Transactional` rollback, which would hide cross-thread visibility issues.

---

### Load test

Run: `./load-test.sh`

Sends 100 RPS for 30 seconds (3000 total requests).

Verifies: `outbox_pending_count` spikes and recovers to 0, all 3000 payments created with status `INITIATED`, no events lost. Results vary by machine — architecture supports horizontal scaling for higher throughput.

---

## API Reference

All endpoints on `localhost:8080`.

### Initiate Payment
```
POST /api/v1/payments
Content-Type: application/json

{
  "idempotencyKey": "order-abc-123",
  "merchantId": "550e8400-e29b-41d4-a716-446655440000",
  "amount": 99.99,
  "currency": "USD"
}
```

Response `201 Created`:
```json
{
  "id": "...",
  "status": "INITIATED",
  "amount": 99.99,
  "currency": "USD",
  "idempotencyKey": "order-abc-123",
  "createdAt": "2026-03-27T10:00:00Z"
}
```

### Transition Payment

```
PATCH /api/v1/payments/{id}/authorize
PATCH /api/v1/payments/{id}/settle
PATCH /api/v1/payments/{id}/fail?reason=insufficient_funds
```

Sending the same `idempotencyKey` twice returns the existing payment (no duplicate creation).

---

## Monitoring

| URL | Purpose |
|---|---|
| `localhost:9090` | Prometheus |
| `localhost:3000` | Grafana (admin / admin) |
| `localhost:8080/actuator/health` | Payment service health |
| `localhost:8085/actuator/health` | Outbox relay health |
| `localhost:8083/actuator/health` | Consumer stubs health |

Key metric: **`outbox.pending.count`** — tracks unpublished event backlog. Spike indicates relay is behind or failing.

---

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 21 (virtual threads via Project Loom) |
| Framework | Spring Boot 3.3.4 |
| Database | PostgreSQL 16 + Flyway migrations |
| Messaging | Redpanda (Kafka-compatible) + Confluent Schema Registry |
| Serialization | Apache Avro 1.11 |
| Metrics | Micrometer + Prometheus + Grafana |
| Testing | JUnit 5, Testcontainers 1.19.8, Awaitility, Mockito (`@SpyBean`) |
| Build | Maven (multi-module) |

---

### Extension ideas

- **Debezium CDC:** replace the `@Scheduled` poller with a Debezium connector reading PostgreSQL WAL — eliminates polling latency entirely
- **Schema evolution:** add an optional `correlationId` field to `PaymentEvent.avsc` and verify backward compatibility with existing consumers
- **Horizontal relay scaling:** run 3 `outbox-relay` instances simultaneously and verify `SKIP LOCKED` prevents duplicate publishing
- **Kafka Streams:** add a topology that aggregates payment counts by merchant and status in real time
