# CoreBank

**CoreBank** is a production-oriented **Banking as a Service (BaaS)** backend built with Java 21 and Spring Boot. Version **0.8.0** adds a fail-open, ephemeral **Redis performance layer** for privacy-conscious Customer/Account summaries and completed idempotency replay pointers. Financial correctness continues to depend on PostgreSQL, never Redis.

The project evolves release by release so each version demonstrates a focused set of backend engineering concerns without prematurely turning the system into a distributed architecture.

## What v0.8 demonstrates

- Redis 7 + Spring Cache for safe, five-minute Customer/Account summaries
- Redis replay pointers for COMPLETED idempotency records (one-hour TTL)
- SHA-256 Redis idempotency keys and explicit JSON payloads
- AFTER_COMMIT-only population of new replay pointers
- Redis fail-open on cache read/write/eviction errors
- PostgreSQL remains authoritative for all financial state and replay bodies
- Testcontainers integration tests for Redis, PostgreSQL and outage recovery

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
- Durable retry state in PostgreSQL
- Explicit retry backoff
- Failure classification: retriable vs non-retriable
- Transactionally safe dead-letter handoff
- Dedicated Kafka DLQ topic
- At-least-once publication semantics
- PostgreSQL `FOR UPDATE SKIP LOCKED` for concurrent publishers
- Versioned event envelopes
- Testcontainers PostgreSQL + Kafka
- JUnit 5 / Mockito / MockMvc
- JaCoCo
- Docker Compose
- Swagger / OpenAPI
- Actuator

## v0.8: Redis cache with PostgreSQL authority

> **Redis accelerates CoreBank; PostgreSQL guarantees CoreBank.**

Redis is **optional and disposable**. The application must continue to serve requests and preserve all banking invariants if the Redis service is stopped, restarted or flushed. PostgreSQL remains the system of record for balances, ledger, financial transactions, idempotency records and locks, transactional outbox, and DLQ.

Read-only cached projections:

| Endpoint | Source / cache | Response data |
|---|---|---|
| `GET /api/v1/customers/{id}` | PostgreSQL | Existing complete Customer (may contain document/email) |
| `GET /api/v1/customers/{id}/summary` | Redis cache-aside, 5 min; PostgreSQL fallback | id, name, createdAt; **never** document/email |
| `GET /api/v1/accounts/{id}` | PostgreSQL | Existing complete Account including balance |
| `GET /api/v1/accounts/{id}/balance` | PostgreSQL | Authoritative current balance |
| `GET /api/v1/accounts/{id}/summary` | Redis cache-aside, 5 min; PostgreSQL fallback | id, customerId, branch, number, status, currency, createdAt; **never** balance |

Cache values are dedicated JSON projections, not serialized JPA entities. A balance-changing deposit, withdrawal or transfer does not alter the account summary fields; it therefore does not invalidate that projection. Any future update to a projected field must evict the corresponding key **after** the PostgreSQL commit.

Completed idempotency replay uses explicit Redis pointers, never a financial response cache:

```text
corebank:v1:customer-summary:{customerId}       TTL 5 minutes
corebank:v1:account-summary:{accountId}         TTL 5 minutes
corebank:v1:idempotency:{sha256(rawKey)}         TTL 1 hour
```

The idempotency pointer stores only `recordId`, `operation`, `requestHash`, `resourceId`, and `httpStatus`. Even on Redis HIT, CoreBank loads the matching PostgreSQL `IdempotencyRecord`, verifies the original key, operation, request hash, status and pointer metadata, and replays the **PostgreSQL** response. `IN_PROGRESS` and the advisory transaction lock remain PostgreSQL-only. New pointers are written after successful PostgreSQL commit via `AFTER_COMMIT` listener. On Redis MISS, corruption, timeout or full outage, CoreBank falls back safely to PostgreSQL.

Redis must never contain raw `Idempotency-Key` text, `response_body`, financial balances, account history, customer document/email, financial payloads, ledger/outbox/DLQ state, or credentials. Cache errors log sanitized cache operation/type only, without serialized values or PII.

Local prerequisites: Java 21, Maven 3.9+, Docker + Compose. Start dependencies:

```powershell
docker compose up -d postgres kafka redis
mvn spring-boot:run
```

Default local connections: PostgreSQL `localhost:5432`, Kafka `localhost:9092`, Redis `localhost:6379`, API `localhost:8080`. Redis uses `redis:7-alpine` with a healthcheck and **no persistent volume**. Losing its content only causes cache misses. Redis has short 500 ms connection/operation timeouts by default; the Redis Actuator health contributor is excluded from global health, since it is not a mandatory availability dependency.

Configuration through `REDIS_HOST`, `REDIS_PORT`, `corebank.cache.key-prefix`, `corebank.cache.summary-ttl` (default `5m`) and `corebank.cache.idempotency-replay-ttl` (default `1h`). Redis credentials must not be committed; environment-specific hardening (TLS/ACL) belongs to deployment work.

**Release gate:** `mvn clean verify`. The feature branch is not a tested/released v0.8 build until fresh Maven output shows zero failures/errors and `BUILD SUCCESS`.

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

CoreBank remains a **modular monolith**. Kafka is an asynchronous integration boundary; PostgreSQL remains the transactional system of record for financial state and publication state.

## Core transactional invariant

A valid financial transaction must never depend on Kafka availability.

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

The financial transaction commits before Kafka publication is attempted.

```text
financial commit => durable outbox event
```

## Primary financial events

CoreBank publishes three versioned financial events:

```text
DEPOSIT_COMPLETED
WITHDRAWAL_COMPLETED
TRANSFER_COMPLETED
```

Primary topic:

```text
corebank.financial-events.v1
```

Local topology:

```text
partitions = 3
replication-factor = 1
```

Message keys:

```text
DEPOSIT_COMPLETED    -> accountId
WITHDRAWAL_COMPLETED -> accountId
TRANSFER_COMPLETED   -> sourceAccountId
```

Example event envelope:

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

JPA entities are never serialized directly as Kafka contracts.

Event payloads intentionally exclude customer PII, credentials, balances, original HTTP bodies, `Idempotency-Key`, stack traces and free-form transaction descriptions.

## Durable retry state

Migration `V7__add_outbox_retry_state.sql` evolves `outbox_events` with:

```text
attempt_count
next_attempt_at
last_error_type
last_error_message
```

Primary outbox states:

```text
PENDING
PUBLISHED
DEAD_LETTERED
```

Eligible events are claimed using PostgreSQL locking equivalent to:

```sql
SELECT *
FROM outbox_events
WHERE status = 'PENDING'
  AND next_attempt_at <= NOW()
ORDER BY next_attempt_at, created_at
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

This makes retry timing durable across process restarts and allows multiple application instances to publish concurrently without JVM-local locks.

## Primary retry policy

Maximum publication attempts:

```text
5
```

Backoff:

```text
1st failure -> retry in 2s
2nd failure -> retry in 5s
3rd failure -> retry in 15s
4th failure -> retry in 30s
5th failure -> DEAD_LETTERED
```

Known deterministic serialization/contract failures do not waste the full retry budget. They are dead-lettered immediately.

A row waiting for `next_attempt_at` does not block newer eligible rows.

## Dead-letter handoff

CoreBank does not perform a direct dual-write from the primary outbox to Kafka DLQ.

When an event is exhausted or non-retriable, one PostgreSQL transaction performs:

```text
outbox_events.status = DEAD_LETTERED
+
insert dead_letter_events status = PENDING
```

Only after that transaction commits does a separate publisher send the dead-letter record to Kafka.

This preserves the same architectural principle used by the primary Transactional Outbox.

## Dead-letter persistence

Migration `V8__create_dead_letter_events.sql` creates durable dead-letter records containing:

```text
id
original_event_id UNIQUE
event metadata
message_key
payload
attempt_count
original failure metadata
failed_at
status
next_attempt_at
publish_attempts
DLQ publication error metadata
created_at
published_at
```

`original_event_id` is unique so one primary outbox event cannot generate multiple dead-letter rows.

Original failure metadata and later DLQ-publication failures are stored separately.

Full stack traces are not persisted.

## Kafka DLQ

DLQ topic:

```text
corebank.financial-events.dlq.v1
```

Local topology:

```text
partitions = 3
replication-factor = 1
```

Example DLQ envelope:

```json
{
  "deadLetterId": "uuid",
  "originalEventId": "uuid",
  "eventType": "TRANSFER_COMPLETED",
  "eventVersion": 1,
  "aggregateType": "TRANSFER",
  "aggregateId": "uuid",
  "correlationId": "uuid",
  "failedAt": "2026-10-17T15:40:00Z",
  "attemptCount": 5,
  "errorType": "TimeoutException",
  "errorMessage": "Transient Kafka publication failure",
  "payload": {}
}
```

## DLQ retry policy

The DLQ publisher retries indefinitely because PostgreSQL already retains the durable dead-letter record and v0.7 intentionally has no "DLQ of the DLQ".

```text
1st DLQ failure -> 5s
2nd             -> 15s
3rd             -> 30s
4th             -> 60s
5th+            -> 60s
```

No worker thread sleeps for these delays. Scheduling is driven by durable `next_attempt_at` values in PostgreSQL.

## Delivery semantics

Both primary and DLQ publication are **at least once**, not exactly once.

Primary crash window:

```text
Kafka ACK
↓
process crashes before PostgreSQL commits PUBLISHED
↓
row may be sent again
```

DLQ publication has the same type of crash window.

Stable identifiers are available for future consumer deduplication:

```text
primary -> eventId
DLQ     -> deadLetterId / originalEventId
```

## HTTP idempotency

Financial POST endpoints require `Idempotency-Key`:

```text
POST /api/v1/accounts/{accountId}/deposits
POST /api/v1/accounts/{accountId}/withdrawals
POST /api/v1/transfers
```

An exact semantic replay returns the persisted response and does not execute the financial command again.

Therefore:

```text
1 real financial operation = 1 primary outbox event
```

## Kafka unavailable

Kafka failure does not roll back an already valid financial operation.

```text
Kafka unavailable
↓
financial PostgreSQL transaction commits
↓
outbox event remains durable
↓
HTTP operation remains successful
↓
publisher retries asynchronously
```

## Configuration

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}

corebank:
  kafka:
    topic:
      financial-events: corebank.financial-events.v1
      financial-events-dlq: corebank.financial-events.dlq.v1

  outbox:
    batch-size: 100
    fixed-delay-ms: 2000
    max-attempts: 5
    retry-delays:
      - 2s
      - 5s
      - 15s
      - 30s

  dlq:
    batch-size: 100
    fixed-delay-ms: 2000
    retry-delays:
      - 5s
      - 15s
      - 30s
      - 60s
    max-delay: 60s
```

## Local environment

Prerequisites:

- Java 21
- Maven 3.9+
- Docker + Docker Compose

Start PostgreSQL, Kafka and Redis:

```bash
docker compose up -d postgres kafka
```

Run the application:

```bash
mvn spring-boot:run
```

Defaults:

```text
PostgreSQL: localhost:5432
Kafka:      localhost:9092
Redis:      localhost:6379
API:        localhost:8080
```

Kafka uses `apache/kafka:3.8.1` in KRaft mode for local development.

## API documentation

```text
Swagger UI: http://localhost:8080/swagger-ui.html
OpenAPI:    http://localhost:8080/v3/api-docs
Health:     http://localhost:8080/actuator/health
Info:       http://localhost:8080/actuator/info
```

## Verification

Release gate:

```bash
mvn clean verify
```

v0.7 adds focused coverage for:

```text
RetryMigrationIT
OutboxRetryStateTest
RetryPolicyTest
PublicationFailureClassifierTest
DeadLetterPersistenceIT
DeadLetterServiceIT
OutboxRetryWorkerIT
OutboxDeadLetterIT
DeadLetterRetryPolicyTest
DeadLetterPublicationWorkerIT
DeadLetterConcurrencyIT
RetryToDlqKafkaIT
DlqKafkaFailureIT
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
V7__add_outbox_retry_state.sql
V8__create_dead_letter_events.sql
```

Flyway owns schema evolution; Hibernate validates the resulting schema.

## Roadmap

| Release | Scope |
|---|---|
| v0.1 | Customers + Accounts |
| v0.2 | Deposits + Withdrawals + Transaction History |
| v0.3 | Account-to-account Transfers |
| v0.4 | Double-entry Ledger + Reconciliation |
| v0.5 | Idempotency |
| v0.6 | Kafka Events + Transactional Outbox |
| **v0.7** | **Durable Retry + DLQ** |
| **v0.8** | **Redis (safe summaries + fail-open idempotency pointers)** |
| v0.9 | JWT + RBAC |
| v1.0 | Observability |
| v1.1 | GitHub Actions |
| v1.2 | AWS deployment |
| v1.3 | Frontend React |
| v2.0 | Service extraction / microservices |

## Why this project exists

CoreBank is a portfolio project focused on engineering decisions behind transactional backend systems: business invariants, ACID boundaries, database guarantees, optimistic concurrency, accounting consistency, append-only audit data, idempotency and reliable asynchronous integration.

The objective is not to simulate every banking feature at once. Each release introduces one class of production concern and makes its guarantees explicit, testable and explainable in code review or technical interviews.
