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

# Build single module
mvn clean install -DskipTests -pl payment-service -am

# Run tests (requires Docker for Testcontainers)
mvn test
```

## Run Services

```bash
# Requires infra running first

java -jar payment-service/target/payment-service-*-exec.jar
java -jar outbox-relay/target/outbox-relay-*-exec.jar
java -jar consumer-stubs/target/consumer-stubs-*-exec.jar
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
