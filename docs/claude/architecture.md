# Architecture

## Data Flow

```
Client
  │  POST /api/v1/payments
  ▼
PaymentController
  │
  ▼
PaymentService (@Transactional)
  ├── INSERT payments (status=INITIATED)
  └── INSERT outbox_events (status=PENDING)   ← atomic with payment write
         │
         │  (OutboxPoller, every 500ms)
         │  SELECT FOR UPDATE SKIP LOCKED LIMIT 100
         ▼
OutboxPublisher
  ├── build PaymentEvent (Avro)
  ├── kafkaTemplate.send(...).get()           ← blocks for ACK
  └── mark outbox row PUBLISHED
         │
         ▼
Redpanda topic: payments.events
         │
         ├──► LedgerConsumer    (group: ledger-consumers)
         ├──► FraudConsumer     (group: fraud-consumers)
         └──► NotificationConsumer (group: notification-consumers)
                  │
                  ▼
         processed_events INSERT (eventId, consumerGroup)
```

## Retry / Error Path

```
OutboxPoller failure → incrementRetry() → retry up to 3×
  If retry_count >= 3:
    publishToDlt()  → payments.events.DLT
    markFailed()

Consumer failure → @RetryableTopic (3 attempts, exponential backoff)
  → payments.events-0, payments.events-1
  → payments.events.DLT
```

## Payment State Machine

```
INITIATED ──authorize──► AUTHORIZED ──settle──► SETTLEMENT_PENDING ──► SETTLED
    │                         │                          │
    └────────fail─────────────┴──────────fail────────────┘
                                     ▼
                                   FAILED
```

`SETTLED` and `FAILED` are terminal — `canTransitionTo()` returns false from these.

## Optimistic Locking

`Payment.version` (mapped to DB `version BIGINT`) — Spring Data JPA increments on each save. Concurrent updates throw `OptimisticLockException`, not silent overwrites.
