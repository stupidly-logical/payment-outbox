# Patterns & Rules

## Transactional Outbox

**Rule:** Payment state change + `outbox_events` INSERT must share one `@Transactional` boundary in `PaymentService`.

```java
@Transactional
public Payment transitionPayment(UUID paymentId, PaymentStatus targetStatus) {
    // both saves must be in this transaction
    paymentRepository.save(payment);
    outboxRepository.save(buildOutboxEvent(payment));
    return payment;
}
```

**Why:** If the app crashes between the DB commit and a Kafka send, the event would be lost. The relay picks up PENDING rows on restart — no event is ever skipped.

## Outbox Polling Query

```sql
SELECT * FROM outbox_events
WHERE status = 'PENDING'
ORDER BY created_at ASC
LIMIT :batchSize
FOR UPDATE SKIP LOCKED
```

`SKIP LOCKED` means multiple relay instances can poll concurrently without blocking each other. Each instance locks a disjoint batch.

## Consumer Idempotency

```java
if (processedEventRepository.existsByIdEventIdAndIdConsumerGroup(eventId, GROUP)) {
    return;  // already processed — safe to skip
}
// ... do work ...
processedEventRepository.save(new ProcessedEvent(eventId, GROUP));
```

The composite PK `(eventId, consumerGroup)` enforces uniqueness at DB level — duplicate INSERTs throw `DataIntegrityViolationException` (safe to catch/ignore or let Spring handle via transaction rollback + retry).

## Adding a New Consumer

1. Create class in `consumer-stubs/.../consumer/<domain>/`
2. Define a unique `GROUP` string constant
3. Copy the idempotency check pattern from `LedgerConsumer`
4. Add `@RetryableTopic` and `@KafkaListener` annotations
5. No DB migration needed — `processed_events` already holds any `(eventId, consumerGroup)` pair

## Adding a New Payment State

1. Add value to `PaymentStatus` enum in `shared`
2. Update `canTransitionTo()` switch cases
3. Add new `PATCH` endpoint in `PaymentController`
4. Add `service` method in `PaymentService` (follow `transitionPayment` pattern)
5. Add `CHECK` constraint update in a new Flyway migration (V4+)

## Avro Schema Evolution

- Schema lives in `shared/src/main/avro/PaymentEvent.avsc`
- Schema Registry (port 8081) enforces compatibility
- Default compatibility: BACKWARD — new fields must have defaults
- Never remove a field without a major schema version bump

## Outbox Payload

`OutboxEvent.payload` is a JSON string with these keys:
```
paymentId, merchantId, status, amount, currency, failureReason
```
`OutboxPublisher.toAvro()` parses this JSON to build the Avro event. If you add a field to the Avro schema, update both `PaymentService.PaymentEventPayload` record and `OutboxPublisher.toAvro()`.
