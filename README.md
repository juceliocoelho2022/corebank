# CoreBank

**CoreBank** is a production-oriented **Banking as a Service (BaaS)** backend built with Java 21 and Spring Boot. Version **0.6.0** introduces asynchronous financial events with **Apache Kafka** using a **PostgreSQL Transactional Outbox**, while preserving the ACID money-movement, idempotency, double-entry ledger, reconciliation and optimistic-concurrency guarantees implemented in previous releases.

CoreBank evolves release by release so each version demonstrates one group of backend engineering concerns without prematurely turning the project into a distributed system.

## What v0.6 demonstrates

- Java 21 + Spring Boot 3.5
- Modular-monolith architecture
- PostgreSQL + Spring Data JPA
- Flyway migrations
- ACID deposits, withdrawals and internal transfers
- Immutable transaction history
- Immutable double-entry ledger
- Operational-balance × ledger reconciliation
- Optimistic locking with JPA `@Version`
- Persistent HTTP idempotency
- Apache Kafka integration
- PostgreSQL Transactional Outbox
- At-least-once event publication
- PostgreSQL `FOR UPDATE SKIP LOCKED` for concurrent publishers
- Versioned event envelopes
- Explicit Kafka message keys
- JSONB outbox payload persistence
- Scheduler-based publication
- Testcontainers PostgreSQL + Kafka
- JUnit 5 / Mockito / MockMvc
- JaCoCo
- Docker Compose
- Swagger / OpenAPI
- Actuator

## Architecture

```text
com.corebank
├── account
├── customer
├── transaction
├── transfer
├── ledger
├── reconciliation
├── idempotency
├── event
│   ├── domain
│   ├── application
│   └── infrastructure
├── shared
└── CoreBankApplication
```

CoreBank remains a **modular monolith** in v0.6. Kafka is an asynchronous integration boundary; it is not the source of truth for balances or accounting.

## Financial consistency model

CoreBank continues to use:

```text
accounts.balance
```

as the operational balance used by business flows.

The immutable double-entry ledger remains the accounting record used for auditability and reconciliation.

A successful operation still updates its financial records atomically.

### Deposit

```text
accounts.balance          + amount
account_transactions      + 1 DEPOSIT
journal_entries           + 1
ledger_entries            + 2
outbox_events             + 1 DEPOSIT_COMPLETED
```

Accounting entry:

```text
DEBIT   TECH:CASH_CLEARING
CREDIT  CUSTOMER_LIABILITY(account)
```

### Withdrawal

```text
DEBIT   CUSTOMER_LIABILITY(account)
CREDIT  TECH:CASH_CLEARING
```

A successful withdrawal creates one `WITHDRAWAL_COMPLETED` outbox event.

### Internal transfer

```text
DEBIT   CUSTOMER_LIABILITY(source)
CREDIT  CUSTOMER_LIABILITY(destination)
```

A transfer creates:

```text
1 TRANSFER_OUT
1 TRANSFER_IN
1 accounting journal
2 ledger entries
1 TRANSFER_COMPLETED outbox event
```

It does **not** create two Kafka events merely because two transaction-history rows exist.

## Why Transactional Outbox

Publishing directly to Kafka inside a financial request would create a dual-write problem:

```text
PostgreSQL commit succeeds
Kafka send fails
```

or:

```text
Kafka accepts event
PostgreSQL transaction rolls back
```

CoreBank v0.6 avoids that coupling by making PostgreSQL the atomic boundary.

Conceptually:

```text
HTTP request
   ↓
IdempotencyService
   ↓
TransactionService / TransferService
   ↓
PostgreSQL transaction
   ├── account balance(s)
   ├── account_transactions
   ├── journal_entries
   ├── ledger_entries
   ├── idempotency_records
   └── outbox_events PENDING
            ↓
          COMMIT
            ↓
      OutboxScheduler
            ↓
      OutboxPublisher
            ↓
          Kafka
```

Core invariant:

```text
financial commit => durable outbox event
```

Kafka availability is deliberately **not** part of the financial HTTP transaction.

## Outbox persistence

Migration `V6__create_outbox_events.sql` creates:

```text
outbox_events
├── id UUID PK
├── event_type VARCHAR(64)
├── event_version INTEGER
├── aggregate_type VARCHAR(64)
├── aggregate_id UUID
├── correlation_id UUID
├── message_key VARCHAR(128)
├── payload JSONB
├── status PENDING|PUBLISHED
├── created_at TIMESTAMPTZ
└── published_at TIMESTAMPTZ NULL
```

The database enforces:

```text
event_version >= 1
status ∈ {PENDING, PUBLISHED}
PENDING   => published_at IS NULL
PUBLISHED => published_at IS NOT NULL
```

A partial index supports pending-event polling:

```sql
CREATE INDEX idx_outbox_pending_created_at
ON outbox_events (created_at)
WHERE status = 'PENDING';
```

`outbox_events.id` is the same UUID exposed externally as `eventId`.

## Financial events

v0.6 defines exactly three event types:

```text
DEPOSIT_COMPLETED
WITHDRAWAL_COMPLETED
TRANSFER_COMPLETED
```

### Event envelope

```json
{
  "eventId": "uuid",
  "eventType": "TRANSFER_COMPLETED",
  "eventVersion": 1,
  "occurredAt": "2026-10-17T15:40:00Z",
  "aggregateType": "TRANSFER",
  "aggregateId": "uuid",
  "correlationId": "uuid",
  "payload": {}
}
```

Semantics:

- `eventId` identifies the event instance and can be used for consumer deduplication;
- `eventType` identifies the business event;
- `eventVersion` versions the event contract;
- `occurredAt` is the UTC event timestamp;
- `aggregateId` identifies the principal business aggregate;
- `correlationId` identifies the originating financial operation;
- `payload` contains only the required integration data.

JPA entities are never serialized directly as Kafka contracts.

### Deposit payload

```json
{
  "accountId": "uuid",
  "transactionId": "uuid",
  "amount": 100.00,
  "currency": "BRL"
}
```

### Withdrawal payload

```json
{
  "accountId": "uuid",
  "transactionId": "uuid",
  "amount": 50.00,
  "currency": "BRL"
}
```

### Transfer payload

```json
{
  "transferId": "uuid",
  "sourceAccountId": "uuid",
  "destinationAccountId": "uuid",
  "amount": 125.00,
  "currency": "BRL"
}
```

The events intentionally exclude customer name/e-mail/document, credentials, balances, original HTTP bodies, `Idempotency-Key`, stack traces and free-form transaction descriptions.

## Kafka topology

Topic:

```text
corebank.financial-events.v1
```

Local topology:

```text
partitions = 3
replication-factor = 1
```

Kafka message keys:

```text
DEPOSIT_COMPLETED    -> accountId
WITHDRAWAL_COMPLETED -> accountId
TRANSFER_COMPLETED   -> sourceAccountId
```

The message key is persisted in `outbox_events.message_key`; the publisher does not need to inspect JSON to discover partitioning information.

## Publisher concurrency

The publisher claims one event at a time using PostgreSQL locking equivalent to:

```sql
SELECT *
FROM outbox_events
WHERE status = 'PENDING'
ORDER BY created_at
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

This allows multiple CoreBank instances to run publishers concurrently without JVM-local locking.

Example:

```text
Instance A locks event 1
Instance B skips event 1 and claims event 2
```

Each claimed event runs in its own `REQUIRES_NEW` transaction.

## Kafka acknowledgement and outbox state

The lifecycle is:

```text
PENDING
  ↓
KafkaTemplate.send(...)
  ↓
Kafka ACK
  ↓
PUBLISHED + published_at
```

CoreBank never marks an event `PUBLISHED` before the broker acknowledges the send.

If serialization, send or acknowledgement fails, the publisher transaction rolls back and the event remains:

```text
PENDING
```

The next scheduler cycle may try again.

## Delivery semantics: at-least-once

v0.6 deliberately provides **at-least-once publication**, not exactly-once delivery.

There is an unavoidable crash window:

```text
Kafka ACK
↓
process crashes before PostgreSQL commits PUBLISHED
↓
row remains PENDING
↓
event may be sent again
```

That duplicate possibility is part of the documented contract.

Consumers should eventually deduplicate using `eventId` when consumer-side processing is introduced.

## Scheduler and retry behavior

Production defaults:

```yaml
corebank:
  outbox:
    batch-size: 100
    fixed-delay-ms: 2000
```

The publisher processes at most `batch-size` successful events per cycle.

If one event fails to publish, the current batch stops immediately so the same oldest failed event is not retried repeatedly in one scheduler cycle.

The next scheduled cycle performs the next retry.

v0.6 intentionally has no:

```text
FAILED status
retry counter
exponential backoff
maximum attempt count
DLQ
```

Those concerns belong to **v0.7 — Retry + DLQ**.

## Kafka unavailable

A valid banking operation does not fail merely because Kafka is unavailable.

Example:

```text
Kafka offline
↓
POST deposit
↓
financial PostgreSQL transaction commits
outbox row commits as PENDING
HTTP 201 Created
```

When Kafka becomes available again:

```text
scheduler finds PENDING
↓
publisher sends event
↓
Kafka ACK
↓
PUBLISHED
```

## Interaction with HTTP idempotency

Financial POST endpoints still require `Idempotency-Key`:

```http
POST /api/v1/accounts/{accountId}/deposits
POST /api/v1/accounts/{accountId}/withdrawals
POST /api/v1/transfers
```

A first successful execution creates the financial records and exactly one outbox event.

An exact semantic replay:

```text
Idempotent-Replayed: true
```

returns the persisted HTTP response and does **not** execute the financial command again.

Therefore:

```text
1 real financial operation = 1 outbox event
```

not:

```text
1 HTTP attempt = 1 outbox event
```

## Full rollback boundary

For an idempotent deposit, conceptually:

```text
BEGIN
  idempotency IN_PROGRESS
  update account balance
  insert account_transaction
  insert journal
  insert ledger lines
  insert outbox event PENDING
  persist response JSON
  idempotency COMPLETED
COMMIT
```

A failure anywhere before commit rolls back every database effect, including the outbox row.

## Local environment

Prerequisites:

- Java 21
- Maven 3.9+
- Docker + Docker Compose

Start PostgreSQL and Kafka:

```bash
docker compose up -d postgres kafka
```

Check services:

```bash
docker compose ps
```

Run CoreBank:

```bash
mvn spring-boot:run
```

Defaults:

```text
PostgreSQL: localhost:5432
Kafka:      localhost:9092
API:        localhost:8080
```

Kafka uses the official JVM image:

```text
apache/kafka:3.8.1
```

and runs as a single combined broker/controller KRaft node for local development.

## Configuration

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}

corebank:
  kafka:
    topic:
      financial-events: corebank.financial-events.v1
  outbox:
    batch-size: 100
    fixed-delay-ms: 2000
```

## API documentation

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI:

```text
http://localhost:8080/v3/api-docs
```

Health:

```text
http://localhost:8080/actuator/health
```

Info:

```text
http://localhost:8080/actuator/info
```

## Verification

Full release gate:

```bash
mvn clean verify
```

Important v0.6 suites include:

```text
OutboxEventTest
FinancialEventFactoryTest
KafkaTopicConfigTest
OutboxPublisherTest
OutboxMigrationIT
OutboxPersistenceIT
FinancialOutboxIT
OutboxIdempotencyIT
OutboxRollbackIT
OutboxPublicationWorkerIT
OutboxConcurrencyIT
OutboxKafkaIT
OutboxKafkaFailureIT
```

Integration tests use Testcontainers for real PostgreSQL and Kafka where appropriate.

JaCoCo report:

```text
target/site/jacoco/index.html
```

## Database migrations

```text
V1__create_corebank_schema.sql
V2__create_account_transactions.sql
V3__add_transfer_metadata.sql
V4__create_double_entry_ledger.sql
V5__create_idempotency_records.sql
V6__create_outbox_events.sql
```

Flyway owns schema evolution; Hibernate uses schema validation.

## Roadmap

| Release | Scope |
|---|---|
| v0.1 | Customers + Accounts |
| v0.2 | Deposits + Withdrawals + Transaction History |
| v0.3 | Account-to-account Transfers |
| v0.4 | Double-entry Ledger + Reconciliation |
| v0.5 | Idempotency |
| **v0.6** | **Kafka Events + Transactional Outbox** |
| v0.7 | Retry + DLQ |
| v0.8 | Redis |
| v0.9 | JWT + RBAC |
| v1.0 | Observability |
| v1.1 | GitHub Actions |
| v1.2 | AWS deployment |
| v2.0 | Service extraction / microservices |

## Why this project exists

CoreBank is a portfolio project focused on engineering decisions behind transactional backend systems: business invariants, ACID boundaries, database guarantees, optimistic concurrency, accounting consistency, append-only audit data, idempotency and reliable asynchronous integration.

The objective is not to simulate every banking feature at once. Each release introduces one class of production concern and makes its guarantees explicit, testable and explainable in code review or technical interviews.
