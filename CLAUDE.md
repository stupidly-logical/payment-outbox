# CLAUDE.md — Payment Pipeline

Agentic guidance for this repo. Read the relevant section before making changes.

## Quick Reference

| File | When to read |
|---|---|
| [docs/claude/architecture.md](docs/claude/architecture.md) | Understanding data flow, outbox pattern, state machine |
| [docs/claude/modules.md](docs/claude/modules.md) | Per-module responsibilities, key classes, ports |
| [docs/claude/patterns.md](docs/claude/patterns.md) | Design patterns, transactional rules, idempotency |
| [docs/claude/dev.md](docs/claude/dev.md) | Build, run, test commands |

## Project Snapshot

- **Language:** Java 21, Spring Boot 3.3.4, Maven multi-module
- **4 modules:** `shared` → `payment-service` → `outbox-relay` → `consumer-stubs`
- **DB:** PostgreSQL 16 with Flyway (schema owned by `payment-service`)
- **Messaging:** Redpanda (Kafka) + Confluent Schema Registry + Apache Avro
- **Event contract:** `PaymentEvent.avsc` in `shared/src/main/avro/`

## Non-Negotiable Rules

1. **Never split the payment + outbox write across transactions.** Both must be in one `@Transactional` block — this is the outbox guarantee.
2. **Schema migrations go only in `payment-service/src/main/resources/db/migration/`.** `outbox-relay` and `consumer-stubs` set `flyway.enabled=false`.
3. **Consumer idempotency check must happen before processing, save after.** The composite key `(eventId, consumerGroup)` in `processed_events` prevents duplicate work.
4. **PaymentStatus transitions are enforced by `canTransitionTo()`.** Do not bypass this in service code.
5. **Avro amount field is a String** — preserves `NUMERIC(19,4)` precision. Never use double/float.
