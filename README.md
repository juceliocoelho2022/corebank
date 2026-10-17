# CoreBank

**CoreBank** is a production-oriented **Banking as a Service (BaaS)** portfolio backend built with Java 21 and Spring Boot. Version **0.5.0** adds persistent HTTP idempotency to deposits, withdrawals and internal transfers while preserving the ACID financial model, immutable transaction history, double-entry ledger, reconciliation and optimistic concurrency controls from previous releases.

The project evolves release by release so each version demonstrates one group of backend engineering concerns before introducing distributed-system complexity.

## What v0.5 demonstrates

- Java 21 and Spring Boot 3.5
- Modular-monolith architecture
- PostgreSQL + Spring Data JPA
- Flyway migrations
- ACID money movements
- Deposits, withdrawals and internal transfers
- Immutable transaction history
- Immutable double-entry ledger
- Operational balance × ledger reconciliation
- Optimistic locking with JPA `@Version`
- Persistent idempotency records in PostgreSQL
- Mandatory `Idempotency-Key` on financial POST endpoints
- Canonical SHA-256 request fingerprinting
- Exact replay of completed responses without duplicate financial effects
- Global key uniqueness in v0.5
- Non-blocking cross-instance concurrency guard with PostgreSQL advisory locks
- JSONB response persistence
- RFC 9457-style `ProblemDetail` errors
- Swagger / OpenAPI
- Actuator
- Unit, MVC, persistence, integration and concurrency tests
- Testcontainers PostgreSQL
- JaCoCo
- Docker Compose

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
│   ├── domain
│   ├── application
│   └── infrastructure
├── shared
└── CoreBankApplication
```

CoreBank remains a **modular monolith** in v0.5. `accounts.balance` is the operational balance used by business flows. The double-entry ledger remains the immutable accounting record used for auditability and reconciliation.

## Financial model

Successful operations remain atomic.

### Deposit

```text
accounts.balance          + amount
account_transactions      + 1 DEPOSIT
journal_entries           + 1
ledger_entries            + 2
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

### Internal transfer

```text
DEBIT   CUSTOMER_LIABILITY(source)
CREDIT  CUSTOMER_LIABILITY(destination)
```

A transfer creates one `TRANSFER_OUT`, one `TRANSFER_IN`, one journal and two ledger entries.

## Idempotency in v0.5

The following endpoints require an `Idempotency-Key` header:

```http
POST /api/v1/accounts/{accountId}/deposits
POST /api/v1/accounts/{accountId}/withdrawals
POST /api/v1/transfers
```

Key rules:

```text
length: 8..128 characters
opaque and case-sensitive
globally unique in v0.5
```

Example:

```http
POST /api/v1/accounts/11111111-1111-1111-1111-111111111111/deposits
Idempotency-Key: salary-2026-10-17-001
Content-Type: application/json

{
  "amount": 100.00,
  "description": "Salary"
}
```

First execution:

```http
HTTP/1.1 201 Created
Idempotent-Replayed: false
```

Exact retry with the same semantic request:

```http
HTTP/1.1 201 Created
Idempotent-Replayed: true
```

The second request returns the persisted original response and does **not** create another balance change, transaction-history row, journal or ledger entry.

## Canonical request fingerprinting

CoreBank hashes semantic request values rather than raw JSON.

For money:

```text
100
100.0
100.00
```

all normalize to the same canonical monetary value before SHA-256 hashing.

Descriptions use the normalized business value: padded text is trimmed and blank text is treated as `null`.

Fingerprints include the operation and all identity-defining request fields:

```text
DEPOSIT    -> accountId + amount + description
WITHDRAWAL -> accountId + amount + description
TRANSFER   -> sourceAccountId + destinationAccountId + amount + description
```

## Persistence model

Migration `V5__create_idempotency_records.sql` creates:

```text
idempotency_records
├── id UUID
├── idempotency_key VARCHAR(128) UNIQUE
├── operation_type DEPOSIT|WITHDRAWAL|TRANSFER
├── request_hash VARCHAR(64)
├── status IN_PROGRESS|COMPLETED
├── resource_id UUID
├── response_body JSONB
├── http_status INTEGER
├── created_at TIMESTAMPTZ
└── completed_at TIMESTAMPTZ
```

`COMPLETED` rows persist the original HTTP status and response body for deterministic replay.

There is no TTL or cleanup scheduler in v0.5. Idempotency records persist indefinitely so an old financial request cannot be re-executed merely because its key expired.

## Idempotency conflict semantics

### `400 Bad Request`

Returned for:

- missing `Idempotency-Key`;
- key shorter than 8 characters;
- key longer than 128 characters;
- malformed request input.

### `409 Conflict`

Returned when:

- a key is reused for a different operation;
- a key is reused with a different canonical request fingerprint;
- the same key is already executing and the concurrency lock cannot be acquired.

## Concurrency model

The database remains the correctness authority.

Each new idempotent execution uses PostgreSQL:

```sql
pg_try_advisory_xact_lock(hashtextextended(:key, 0))
```

This lock is:

- transaction-scoped;
- non-blocking;
- effective across application instances sharing the database.

If the same key is already in progress, the second request fails fast with `409 Conflict` instead of waiting or polling.

The `UNIQUE(idempotency_key)` constraint remains the structural database guarantee.

## Atomicity and rollback

The idempotency reservation and the financial command participate in the same Spring transaction.

Conceptually:

```text
BEGIN
  acquire same-key advisory lock
  insert idempotency_records IN_PROGRESS
  update account balance(s)
  insert account_transactions
  insert journal_entries
  insert ledger_entries
  persist original JSON response
  mark idempotency record COMPLETED
COMMIT
```

If the business command or ledger persistence fails, the whole transaction rolls back, including the `IN_PROGRESS` idempotency row and any partial financial writes.

## Reconciliation

```http
GET /api/v1/reconciliation/accounts/{accountId}
```

For customer-liability ledger accounts:

```text
ledger balance = credits - debits
difference = operational balance - ledger balance
```

Statuses:

```text
RECONCILED
MISMATCH
```

A mismatch is reported only; CoreBank does not auto-correct either side.

## Main APIs

### Customers

```http
POST /api/v1/customers
```

### Accounts

```http
POST /api/v1/accounts
GET  /api/v1/accounts/{accountId}
GET  /api/v1/accounts/{accountId}/balance
```

### Deposits / withdrawals

```http
POST /api/v1/accounts/{accountId}/deposits
POST /api/v1/accounts/{accountId}/withdrawals
```

Both require `Idempotency-Key`.

### Transaction history

```http
GET /api/v1/accounts/{accountId}/transactions
```

### Transfers

```http
POST /api/v1/transfers
```

Requires `Idempotency-Key`.

### Reconciliation

```http
GET /api/v1/reconciliation/accounts/{accountId}
```

## Error contract

API failures use `application/problem+json`.

| HTTP | Meaning |
|---|---|
| `400` | malformed JSON, invalid amount/input or invalid/missing idempotency key |
| `404` | customer or account does not exist |
| `409` | insufficient funds, blocked/closed account, optimistic concurrency conflict or idempotency conflict |
| `500` | unexpected sanitized server failure |

## Prerequisites

- Java 21
- Maven 3.9+
- Docker + Docker Compose

## Local setup

```bash
cp .env.example .env
docker compose up -d postgres
mvn spring-boot:run
```

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

Health:

```text
http://localhost:8080/actuator/health
```

## Verification

Full regression gate:

```bash
mvn clean verify
```

Important v0.5 coverage includes:

```text
IdempotencyRecordTest
IdempotencyKeyValidatorTest
RequestFingerprintTest
IdempotencyServiceTest
IdempotencyMigrationIT
IdempotencyPersistenceIT
IdempotencyConcurrencyIT
MoneyMovementIdempotencyIT
TransferIdempotencyIT
IdempotencyRollbackIT
ConcurrentIdempotentRequestIT
```

PostgreSQL integration tests use Testcontainers.

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
```

## Roadmap

| Release | Scope |
|---|---|
| v0.1 | Customers + Accounts |
| v0.2 | Deposits + Withdrawals + Transaction History |
| v0.3 | Account-to-account Transfers |
| v0.4 | Double-entry Ledger + Reconciliation |
| **v0.5** | **Persistent HTTP Idempotency** |
| v0.6 | Kafka events |
| v0.7 | Retry + DLQ |
| v0.8 | Redis |
| v0.9 | JWT + RBAC |
| v1.0 | Observability |
| v1.1 | GitHub Actions |
| v1.2 | AWS deployment |
| v2.0 | Service extraction / microservices |

## v0.5 boundaries

Explicitly out of scope:

- Redis idempotency cache;
- TTL / key expiration;
- scheduled cleanup;
- Kafka;
- JWT/client-scoped key namespaces;
- idempotency for customer or account creation.

These are intentionally deferred to later releases.

## Why this project exists

CoreBank is a portfolio project focused on engineering decisions behind transactional backend systems: business invariants, database guarantees, ACID boundaries, accounting consistency, idempotent APIs, concurrency control, auditability, reconciliation and a deliberate path toward event-driven architecture.
