# payment-outbox — Claude Code context

## Project structure
Four Maven modules: shared → payment-service → outbox-relay → consumer-stubs
shared must build first (other modules depend on it).
payment-service uses classifier=exec to produce both a plain jar
(importable by outbox-relay) and a fat executable jar.

## Build commands
mvn clean install -DskipTests     # build all, skip tests
mvn clean install                 # build all + run all tests
mvn test -pl payment-service      # test one module
mvn test -pl outbox-relay
mvn test -pl consumer-stubs
mvn spring-boot:run               # run from inside a module directory

## Running locally
1. docker compose up -d           # starts postgres, redpanda, schema-registry,
                                  # prometheus, grafana
2. cd payment-service && mvn spring-boot:run    # port 8080
3. cd outbox-relay && mvn spring-boot:run       # port 8085
4. cd consumer-stubs && mvn spring-boot:run     # port 8083

Start payment-service first — it runs Flyway and creates the schema.
consumer-stubs runs its own Flyway for processed_events only.
outbox-relay has flyway.enabled=false.

## Key invariants — never break these
- PaymentService.initiatePayment() and transitionPayment() MUST write
  payment + outbox event in the SAME @Transactional method.
  Splitting them breaks the atomic delivery guarantee.
- OutboxPoller MUST call kafkaTemplate.send().get() — never fire-and-forget.
  Without .get(), events can be marked PUBLISHED before Kafka stores them.
- OutboxEvent status moves only: PENDING → PUBLISHED or PENDING → FAILED.
  Never delete outbox rows — they are the audit trail and enable replay.
- Consumer listeners MUST check processed_events BEFORE processing
  and insert AFTER. Swapping the order breaks idempotency.
- PaymentStatus.canTransitionTo() is the single source of truth for
  valid transitions. Never add transition logic elsewhere.

## Common failure modes and fixes
- "Cannot find main class" → create Application.java in module root package
- "Unsupported Database PostgreSQL 16" → add flyway-database-postgresql dep
- "Unknown magic byte" → stale Kafka messages from before schema registry
  was running. Delete and recreate the payments.events topic.
- "relation X already exists" → Flyway history table mismatch.
  Drop flyway_schema_history_* tables and restart.
- Schema registry connection refused → outbox-relay and schema-registry
  both default to port 8081. Set server.port=8085 in outbox-relay.
