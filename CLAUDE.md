# CLAUDE.md — Payment Pipeline

Agentic guidance for this repo. Read the relevant section before making changes.

## Quick Reference

| File | When to read |
|---|---|
| [docs/claude/architecture.md](docs/claude/architecture.md) | Understanding data flow, outbox pattern, state machine |
| [docs/claude/modules.md](docs/claude/modules.md) | Per-module responsibilities, key classes, ports, test files |
| [docs/claude/patterns.md](docs/claude/patterns.md) | Design patterns, transactional rules, idempotency |
| [docs/claude/dev.md](docs/claude/dev.md) | Build, run, test commands |

## Project Snapshot

- **Language:** Java 21, Spring Boot 3.3.4, Maven multi-module
- **4 modules:** `shared` → `payment-service` → `outbox-relay` → `consumer-stubs`
- **DB:** PostgreSQL 16 with Flyway (schema owned by `payment-service`)
- **Messaging:** Redpanda (Kafka) + Confluent Schema Registry + Apache Avro
- **Event contract:** `PaymentEvent.avsc` in `shared/src/main/avro/`
- **Tests:** JUnit 5, Testcontainers 1.19.8, Awaitility, Mockito — no Docker Compose required

## Non-Negotiable Rules

1. **Never split the payment + outbox write across transactions.** Both must be in one `@Transactional` block — this is the outbox guarantee.
2. **Schema migrations go only in `payment-service/src/main/resources/db/migration/`.** `outbox-relay` sets `flyway.enabled=false` in production (enabled in `application-test.yml` for tests). `consumer-stubs` runs its own migration only for `processed_events`.
3. **Consumer idempotency check must happen before processing, save after.** The composite key `(eventId, consumerGroup)` in `processed_events` prevents duplicate work.
4. **PaymentStatus transitions are enforced by `canTransitionTo()`.** Do not bypass this in service code.
5. **Avro amount field is a String** — preserves `NUMERIC(19,4)` precision. Never use double/float.

## Test Rules

6. **`TestOutboxRelayApplication` omits `@EnableScheduling`.** Relay integration tests call `OutboxPoller.poll()` directly — never rely on scheduler timing in tests.
7. **Use `@SpyBean` to simulate failures, not container restart.** Stopping a shared static Testcontainers container breaks test isolation. Spy the `OutboxPublisher` instead.
8. **Async assertions use Awaitility only.** `Thread.sleep()` is banned in tests.
9. **`Propagation.NOT_SUPPORTED` is required for concurrent tests.** The optimistic-locking test must run outside any test-managed transaction so threads see committed data.
10. **Each test module has its own `AbstractXxxIntegrationTest`.** Static containers and `@DynamicPropertySource` are defined there — never duplicated in concrete test classes.
