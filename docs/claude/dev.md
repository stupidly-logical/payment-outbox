# Dev Commands

## Infrastructure

```bash
# Start all infra (Postgres, Redpanda, Schema Registry, Prometheus, Grafana)
docker compose up -d

# Stop
docker compose down

# Reset (wipe volumes)
docker compose down -v
```

## Build

```bash
# Full build (all modules, skip tests)
mvn clean install -DskipTests

# Build single module (with its dependencies)
mvn clean install -DskipTests -pl payment-service -am
```

## Run Services

```bash
# Requires infra running first (docker compose up -d)

java -jar payment-service/target/payment-service-*-exec.jar
java -jar outbox-relay/target/outbox-relay-*-exec.jar
java -jar consumer-stubs/target/consumer-stubs-*-exec.jar
```

## Testing

Tests are fully self-contained via Testcontainers — **no `docker compose up` required.**
Docker must be running so Testcontainers can pull and start containers.

```bash
# Run full test suite (all modules)
mvn test

# Run per module
mvn test -pl payment-service    # 10 tests: state machine + service layer
mvn test -pl outbox-relay       #  6 tests: outbox polling and Kafka publish
mvn test -pl consumer-stubs     #  4 tests: consumer idempotency

# Run a single test class
mvn test -pl payment-service -Dtest=PaymentServiceIntegrationTest

# Run a single test method
mvn test -pl outbox-relay -Dtest="OutboxRelayIntegrationTest#poll_afterMaxRetries_shouldRouteToDeadLetterTopic"

# Skip tests during install (use when building for deployment)
mvn clean install -DskipTests
```

### What Each Suite Tests

| Module | Test Class | Guarantees proven |
|---|---|---|
| `payment-service` | `PaymentStateMachineTest` | All valid/invalid transitions; terminal states block everything |
| `payment-service` | `PaymentServiceIntegrationTest` | Atomic DB write; idempotencyKey deduplication; per-transition outbox events; illegal transition rollback; `failPayment`; optimistic locking |
| `outbox-relay` | `OutboxRelayIntegrationTest` | PENDING→PUBLISHED flow; ACK-gated status flip; retry count increment; DLT routing at max retries; SKIP LOCKED prevents duplicates; crash-resume |
| `consumer-stubs` | `ConsumerIdempotencyIntegrationTest` | Exactly-once processing per group; 3 groups independent; retry→DLT on persistent failure; context restart doesn't reprocess |

### Test Infrastructure Layout

```
payment-service/src/test/java/com/paymentpipeline/
├── AbstractIntegrationTest.java           ← PostgreSQL container + @BeforeEach cleanup
├── domain/
│   └── PaymentStateMachineTest.java       ← pure unit, no Spring context
└── service/
    └── PaymentServiceIntegrationTest.java

outbox-relay/src/test/
├── resources/
│   └── application-test.yml               ← enables Flyway for test DB schema creation
└── java/com/paymentpipeline/relay/
    ├── AbstractRelayIntegrationTest.java   ← Postgres + Redpanda containers, JDBC helpers
    ├── TestOutboxRelayApplication.java     ← no @EnableScheduling (tests call poll() directly)
    └── OutboxRelayIntegrationTest.java

consumer-stubs/src/test/
├── resources/
│   └── application-test.yml               ← shorter Kafka timeouts for test speed
└── java/com/paymentpipeline/consumer/
    ├── AbstractConsumerIntegrationTest.java ← Postgres + Redpanda, Avro test producer
    ├── TestConsumerApplication.java
    └── ConsumerIdempotencyIntegrationTest.java
```

### Common Test Patterns

```java
// Await async DB change (relay/consumer tests)
await().atMost(5, SECONDS)
       .pollInterval(500, MILLISECONDS)
       .until(() -> outboxRepository.findById(id)
           .map(e -> "PUBLISHED".equals(e.getStatus()))
           .orElse(false));

// Simulate Kafka failure without stopping the container
@SpyBean OutboxPublisher publisherSpy;
doThrow(new RuntimeException("Kafka unavailable")).when(publisherSpy).publish(any());

// Concurrent test — must run outside test transaction
@Test
@Transactional(propagation = Propagation.NOT_SUPPORTED)
void optimisticLocking_test() { ... }
```

## Quick API Test

```bash
# Create payment
curl -s -X POST localhost:8080/api/v1/payments \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"test-1","merchantId":"550e8400-e29b-41d4-a716-446655440000","amount":50.00,"currency":"USD"}' | jq

# Authorize (replace {id})
curl -s -X PATCH localhost:8080/api/v1/payments/{id}/authorize | jq

# Settle
curl -s -X PATCH localhost:8080/api/v1/payments/{id}/settle | jq

# Fail
curl -s -X PATCH "localhost:8080/api/v1/payments/{id}/fail?reason=fraud_detected" | jq
```

## Health Checks

```bash
curl localhost:8080/actuator/health
curl localhost:8085/actuator/health
curl localhost:8083/actuator/health
```

## Database

```bash
# Connect to Postgres
psql postgresql://payments:payments@localhost:5432/payments

# Check outbox backlog
SELECT status, count(*) FROM outbox_events GROUP BY status;

# Check processed events per consumer
SELECT consumer_group, count(*) FROM processed_events GROUP BY consumer_group;
```

## Kafka / Redpanda

```bash
# List topics (via Redpanda REST proxy)
curl -s localhost:8082/topics | jq

# Consume events (requires rpk or kcat)
kcat -b localhost:9092 -t payments.events -C -e
```

## Monitoring

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin / admin)
- Key metric: `outbox_pending_count` — should be near zero in steady state

## Avro Code Generation

Avro Java classes are generated from `shared/src/main/avro/PaymentEvent.avsc` during build by `avro-maven-plugin`. Generated output: `shared/target/generated-sources/avro/`.
