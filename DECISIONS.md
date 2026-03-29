# Architecture Decision Records — Payment Pipeline

Ten key decisions made in this codebase, with alternatives considered and tradeoffs explained.

---

## 1. Transactional Outbox Pattern

**Decision:** Payment state changes and outbox event writes happen in the **same `@Transactional` method** in `PaymentService.java`. Both `paymentRepository.save()` and `outboxRepository.save()` execute inside a single database transaction. Either both commit or neither does.

**Alternatives considered:**
- *Dual write:* Call `paymentRepository.save()`, then publish directly to Kafka in the same method, without an outbox table.
- *CDC only:* Skip the outbox table and use Debezium to capture row changes from the PostgreSQL WAL and forward them to Kafka.

**Tradeoff:**
The dual-write alternative is broken by design. If the service crashes after writing to the database but before publishing to Kafka, the event is lost. If it crashes after publishing but before the DB commit rolls back, a phantom event is emitted. There is no way to make two independent I/O operations atomic without a coordinator — and distributed transaction coordinators (XA/2PC) are expensive, fragile, and unsupported by most modern brokers.

The outbox pattern sidesteps this entirely: the outbox row *is* the event, stored in the same ACID transaction as the payment row. The relay (`OutboxPoller`) reads and publishes it afterward. The cost is up to 500 ms of relay latency (the `@Scheduled(fixedDelay = 500)` interval in `OutboxPoller`), which is acceptable for payment workflows where the originating HTTP response does not need to wait for Kafka delivery.

CDC via Debezium would also provide atomicity (WAL is the source of truth) but introduces a heavier operational dependency — a Debezium connector process, connector configuration, and direct access to `pg_wal` — with no meaningful benefit at this scale.

**Key code:** `PaymentService.transitionPayment()` and `PaymentService.createNew()` — both call `paymentRepository.save()` and `outboxRepository.save()` inside the same `@Transactional` scope.

---

## 2. PostgreSQL `FOR UPDATE SKIP LOCKED`

**Decision:** `OutboxEventRepository.findPendingEventsForUpdate()` uses a native SQL query with `FOR UPDATE SKIP LOCKED` to claim a batch of outbox rows:

```sql
SELECT * FROM outbox_events
WHERE status = 'PENDING'
ORDER BY created_at ASC
LIMIT :batchSize
FOR UPDATE SKIP LOCKED
```

This is declared as a `@Query(nativeQuery = true)` because JPQL has no equivalent syntax.

**Alternatives considered:**
- *Optimistic locking (`@Version`):* Add a version column to `outbox_events`. Multiple relay instances race to update rows; losers get `OptimisticLockException` and skip.
- *Redis distributed lock:* A relay instance acquires a named lock before polling the table, ensuring mutual exclusion at the process level.
- *Single relay instance:* Prohibit horizontal scaling; only one `OutboxPoller` runs at a time.

**Tradeoff:**
Optimistic locking causes wasted work: every relay instance reads the same rows, attempts an update, and most fail. Under contention the majority of database round-trips accomplish nothing. `SKIP LOCKED` eliminates that: each instance gets a disjoint set of rows, with no retries and no conflicts. Redis locking avoids contention too, but adds a network hop to Redis for every poll cycle and introduces Redis as an availability dependency.

The constraint is database portability: `SKIP LOCKED` is a PostgreSQL (and MySQL 8+, Oracle) extension — it is not part of the SQL standard. This codebase is deliberately PostgreSQL-only, so that portability cost is accepted.

---

## 3. Java 21 Virtual Threads over WebFlux

**Decision:** `spring.threads.virtual.enabled=true` is set in `payment-service`'s application properties, enabling Project Loom virtual threads for all Spring MVC request handling. The codebase uses standard blocking JDBC and Spring Data JPA throughout.

**Alternatives considered:**
- *Spring WebFlux / Project Reactor:* Fully reactive stack with non-blocking I/O throughout.
- *Larger platform thread pool:* Increase `server.tomcat.threads.max` to handle more concurrent requests.
- *Kotlin coroutines:* Structured concurrency with suspend functions, compatible with Spring WebFlux.

**Tradeoff:**
WebFlux achieves high concurrency by never blocking a carrier thread, but it requires a reactive programming model end-to-end. Every database call, HTTP client call, and intermediate operation must return a `Mono` or `Flux`. This fundamentally changes how code reads, how exceptions propagate, and how transactions are managed — JPA's `@Transactional` does not compose with reactive pipelines cleanly.

Virtual threads give the same high concurrency with imperative, synchronous code. A virtual thread blocks cheaply (it yields the carrier thread without parking it), so thousands of concurrent requests can block on JDBC without exhausting OS threads. The codebase retains `@Transactional`, Spring Data repositories, and standard exception handling — no reactive rewiring required.

The main risk is **carrier thread pinning**: if a virtual thread enters a `synchronized` block while blocking on I/O, it pins the underlying carrier thread, negating the benefit. Hibernate 6.2+ and HikariCP 5.1+ are both virtual-thread-aware and avoid synchronized blocking. These versions are transitively included via `spring-boot-starter-parent 3.3.4` and should be verified when upgrading.

---

## 4. Redpanda over Apache Kafka Locally

**Decision:** `docker-compose.yml` uses `redpandadata/redpanda:latest` as the Kafka broker for local development and CI. Testcontainers integration tests also use Redpanda's embedded image. Production deployments would use Apache Kafka or a managed Kafka service.

**Alternatives considered:**
- *Kafka in KRaft mode:* Kafka 3.x without ZooKeeper, using the native Raft consensus.
- *Kafka with ZooKeeper:* The legacy Kafka deployment model, still common in older environments.
- *Confluent Cloud:* Fully managed Kafka SaaS, usable from local dev via cloud credentials.

**Tradeoff:**
Redpanda starts in under two seconds, runs as a single binary with no JVM, and uses roughly 100 MB of memory under the `--memory 512M` cap set in `docker-compose.yml`. It exposes a Kafka-compatible API (same protocol, same client libraries) and ships with a built-in Schema Registry on port 8081. This eliminates the need for a separate `cp-schema-registry` container in Testcontainers tests — the `RedpandaContainer` image exposes the registry URL directly.

Kafka KRaft is a closer production match but is heavier to start and still runs on the JVM, which adds startup overhead in CI. ZooKeeper-based Kafka adds a second process and topology to manage. Confluent Cloud requires credentials and network access, breaking offline development.

The acknowledged risk: Redpanda's Kafka compatibility is high but not perfect. Edge-case behaviors — specific producer configs, transaction semantics, compaction details — can diverge. Integration tests pass, but a behavioral gap between local Redpanda and production Kafka would only surface in staging.

---

## 5. Avro over JSON for Event Serialization

**Decision:** Events are serialized as Avro using the schema defined in `shared/src/main/avro/PaymentEvent.avsc` and registered with Confluent Schema Registry. Producers and consumers use `KafkaAvroSerializer` / `KafkaAvroDeserializer` from `io.confluent:kafka-avro-serializer:7.6.0`.

**Alternatives considered:**
- *Plain JSON:* No schema enforcement; any JSON object is a valid message.
- *JSON Schema:* Schema enforcement over JSON payloads, registered with a JSON Schema-aware registry.
- *Protobuf:* Binary format with strong typing, schema registry support, and excellent cross-language codegen.

**Tradeoff:**
Plain JSON requires consumers to defensively handle missing fields, changed types, and extra fields on every deployment. A producer adding a new field or renaming one silently breaks consumers with no build-time signal.

Avro enforces schema compatibility at publish time — the registry rejects a schema that would break existing consumers if configured in `BACKWARD` or `FULL` compatibility mode. The generated `PaymentEvent` Java class (from `PaymentEvent.avsc`) gives compile-time safety: a field rename in the schema is a compile error in `OutboxPublisher.toAvro()`.

**Why `amount` is a `String` in `PaymentEvent.avsc`:** The `payments` table stores `amount` as `NUMERIC(19,4)` — an exact decimal. Avro's numeric types are `int`, `long`, `float`, and `double`; none of these can represent arbitrary-precision decimals without rounding. Avro does support a `decimal` logical type (backed by `bytes` or `fixed`), but it requires consumers to know the scale and precision to decode it, adding coupling. Using `String` (with `BigDecimal.toPlainString()` on the producer side and `new BigDecimal(amount)` on the consumer side) transmits the exact decimal text with no loss and no schema coupling. The `doc` field in the schema records this intent explicitly.

Protobuf would be a reasonable alternative — smaller wire size, better cross-language support — but the Confluent ecosystem (which this project uses for the Schema Registry) has first-class Avro support, and the operational tooling (schema evolution checks, subject naming strategy) is more mature for Avro.

---

## 6. Partition Key = `paymentId`

**Decision:** `OutboxPublisher.publish()` uses `event.getPaymentId().toString()` as the Kafka partition key when sending to `payments.events`:

```java
kafkaTemplate.send(Topics.PAYMENT_EVENTS,
        event.getPaymentId().toString(),   // partition key
        avroEvent)
        .get();
```

**Alternatives considered:**
- *`merchantId` as partition key:* All events for a merchant go to the same partition.
- *`null` / round-robin:* No key; Kafka assigns partitions in round-robin, maximizing throughput.
- *Random UUID:* Same effect as round-robin but explicit.

**Tradeoff:**
Kafka guarantees ordering only within a single partition. A single payment goes through multiple state transitions: `INITIATED → AUTHORIZED → SETTLEMENT_PENDING → SETTLED`. If these events land on different partitions, a consumer processing them in parallel could see `SETTLED` before `INITIATED`. Using `paymentId` as the key routes all events for the same payment to the same partition, guaranteeing in-order delivery per payment.

`merchantId` as a key would group events by merchant, useful if consumer logic is merchant-scoped, but it creates ordering across payments — unrelated transitions for payment A and payment B are serialized unnecessarily, reducing throughput.

Round-robin maximizes partition utilization and throughput but sacrifices per-payment ordering entirely.

The cap: with `paymentId` keying, the maximum parallelism for a single payment is 1 (one partition). Across all payments, parallelism is bounded by the partition count (currently 12). This is adequate for the expected payment throughput and aligns with the Kafka recommendation that partition count should be set to the target consumer parallelism.

---

## 7. `@Version` Optimistic Locking on `Payment`

**Decision:** `Payment.java` declares a `@Version private Long version` field. Spring Data JPA / Hibernate automatically includes this in `UPDATE` statements and throws `OptimisticLockException` if two concurrent transactions attempt to update the same row.

**Alternatives considered:**
- *`SELECT FOR UPDATE` (pessimistic locking):* Lock the payment row at read time; concurrent readers block until the first writer releases.
- *Redis distributed lock:* Acquire a named lock on the payment ID before reading.
- *Serializable transaction isolation:* Promote all transactions to `SERIALIZABLE` isolation level.

**Tradeoff:**
Optimistic locking is low-overhead for workloads where concurrent writes to the *same* payment row are rare. No lock is held between the `SELECT` and the `UPDATE`; the version check happens only at write time. For the payment lifecycle — where transitions on a single payment are sequential and user-driven — concurrent conflicts are the exception, not the norm.

Pessimistic locking (`SELECT FOR UPDATE`) holds a row lock for the duration of the transaction. Under low contention it adds overhead with no benefit; under high contention it serializes reads unnecessarily.

The tradeoff is that callers must handle `OptimisticLockException`. The concurrent-write test in `PaymentServiceIntegrationTest` verifies exactly this: two threads racing to authorize the same payment — only one succeeds. The application layer (or HTTP client retry) is responsible for catching and retrying on conflict.

---

## 8. Consumer-Side Idempotency via `processed_events`

**Decision:** Each consumer (Ledger, Fraud, Notification) writes a `(eventId, consumerGroup)` row to `processed_events` after processing. Before processing, it checks `processedEventRepository.existsByIdEventIdAndIdConsumerGroup()`. Duplicate deliveries are silently skipped. The composite primary key `(event_id, consumer_group)` enforces uniqueness at the database level.

**Alternatives considered:**
- *Kafka exactly-once semantics (EOS):* Kafka transactions with `isolation.level=read_committed` and transactional producers/consumers.
- *Idempotent-by-design:* Make the consumer logic itself idempotent (e.g., upsert instead of insert) and skip deduplication tracking.
- *Redis deduplication:* Store seen `eventId` values in a Redis set with a TTL.

**Tradeoff:**
Kafka EOS requires transactional producers (`transactional.id`), read-committed consumers, and exact-once consumer group management. It adds significant broker-side overhead and constrained consumer restart semantics. It also doesn't help if the consumer crashes *after* committing the Kafka offset but *before* completing its business logic — EOS guarantees at-most-once commit, not at-most-once side-effect.

Idempotent-by-design works well for simple operations (upserts) but is difficult to guarantee for side-effectful consumers (ledger entries, fraud signals, notifications) without careful schema design in each downstream system.

The `processed_events` table is simple, auditable, and durable. The composite primary key means a duplicate insert returns a constraint violation — no row is double-processed. The table also serves as an audit log: it records exactly which events each consumer group has handled.

The cost is write amplification: at 100 RPS with 3 consumer groups, each event triggers 3 idempotency writes — 300 extra rows per second. At this scale that is negligible. At 10,000 RPS it warrants revisiting (partitioned table, TTL-based cleanup, or Redis).

---

## 9. Flyway Per-Service with Separate History Tables

**Decision:** Each service that runs Flyway uses a distinct history table:
- `payment-service` → `flyway_schema_history_payments`
- `consumer-stubs` → `flyway_schema_history_consumers`
- `outbox-relay` → `flyway.enabled=false` (no migrations; reads schema owned by `payment-service`)

**Alternatives considered:**
- *Shared `flyway_schema_history` table:* All services use Flyway's default table name; whichever service starts first "wins" and applies all migrations.
- *Single service owns all migrations:* Only `payment-service` runs Flyway; other services wait for the schema to exist.

**Tradeoff:**
The shared history table approach fails when two services apply migrations to the same table simultaneously on startup — migrations are not idempotent by default, and Flyway's locking is scoped per history table. If `outbox-relay` and `payment-service` both start at the same time and both try to apply `V1__init.sql`, one will fail.

The "single owner" approach is clean but tightly couples service startup ordering. Operators must ensure `payment-service` completes its migrations before `outbox-relay` or `consumer-stubs` start — a fragile requirement for container orchestration.

Per-service history tables allow each service to track exactly which migrations it has applied, independently. `outbox-relay` sets `flyway.enabled=false` because it reads tables created by `payment-service` and has no schema of its own to manage. `consumer-stubs` runs its own Flyway only for `processed_events`, which it owns.

The operational cost: adding a new service requires remembering to set `spring.flyway.table=flyway_schema_history_<service>` in its `application.yml`. Without this, it defaults to the standard table name and may conflict with another service.

---

## 10. `@RetryableTopic` for Consumer Retry

**Decision:** `LedgerConsumer` (and equivalents in Fraud and Notification) are annotated with `@RetryableTopic(attempts = "3", backoff = @Backoff(delay = 1000, multiplier = 2.0))`. Failed messages are routed to suffix-named retry topics (`payments.events-retry-0`, `payments.events-retry-1`) before landing on `payments.events.DLT` after exhausting all attempts.

**Alternatives considered:**
- *Manual `try/catch` retry:* Catch exceptions inside `onPaymentEvent()`, loop with `Thread.sleep()` between attempts.
- *`@Retryable` (Spring Retry):* Method-level retry annotation, retries synchronously on the same thread without routing to a different topic.
- *Manual DLT:* Catch exceptions, publish to a dead-letter topic manually, and commit the original offset.

**Tradeoff:**
Manual in-method retry blocks the consumer thread for the duration of the backoff. During a 1-second backoff after attempt 1, no other messages are processed from that partition. This degrades throughput across all payments when a single consumer failure triggers retries.

`@RetryableTopic` routes failed messages to a separate retry topic and commits the original offset immediately. The main topic continues processing new messages at full throughput. The retry topics are consumed independently with their own offset tracking.

`@Retryable` retries synchronously like the manual loop — it does not offload retries to a separate topic and blocks the consumer for the backoff duration.

The tradeoff is topic proliferation: each `@KafkaListener` with `@RetryableTopic` at `attempts=3` creates two retry topics and one DLT topic. With three consumer groups each listening on `payments.events`, that is 9 additional topics. Topic count is cheap in both Redpanda and Kafka, but operators must be aware of the naming convention (`SUFFIX_WITH_INDEX_VALUE` means `-0`, `-1` not `-1`, `-2`).
