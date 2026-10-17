# CoreBank

**CoreBank** is a production-oriented **Banking as a Service (BaaS)** portfolio backend built with Java 21 and Spring Boot. Version **0.4.0** adds an immutable **double-entry ledger** and account-level reconciliation while preserving the operational balance model, deposits, withdrawals, internal transfers and optimistic concurrency controls from previous releases.

The project evolves release by release so each version demonstrates one group of backend engineering concerns before introducing distributed-system complexity.

## What v0.4 demonstrates

- Java 21 and Spring Boot 3.5
- Modular-monolith architecture
- PostgreSQL + Spring Data JPA
- Flyway migrations
- ACID money movements
- Deposits, withdrawals and internal transfers
- Immutable transaction history
- Immutable double-entry ledger
- One balanced journal per financial operation
- `CUSTOMER_LIABILITY` ledger accounts per CoreBank account
- Technical `TECH:CASH_CLEARING` account
- Opening-balance backfill during migration
- PostgreSQL append-only triggers for journal/ledger rows
- Operational balance × ledger reconciliation
- Optimistic locking with JPA `@Version`
- RFC 9457-style `ProblemDetail` errors
- Swagger / OpenAPI
- Actuator
- Unit, MVC, integration and concurrency tests
- Testcontainers PostgreSQL
- JaCoCo
- Docker Compose

## Architecture

```text
com.corebank
├── account
│   ├── domain
│   ├── application
│   ├── infrastructure
│   └── web
├── customer
│   ├── domain
│   ├── application
│   ├── infrastructure
│   └── web
├── transaction
│   ├── domain
│   ├── application
│   ├── infrastructure
│   └── web
├── transfer
│   ├── application
│   └── web
├── ledger
│   ├── domain
│   ├── application
│   └── infrastructure
├── reconciliation
│   ├── application
│   └── web
├── shared
│   ├── config
│   └── exception
└── CoreBankApplication
```

CoreBank remains a **modular monolith** in v0.4. `accounts.balance` is still the operational balance used by business flows. The ledger is an immutable accounting record used for auditability and reconciliation; it is not yet the sole source of truth.

## Core financial model

### Operational model

`Account` owns mutable account state:

```text
status
balance
currency
version
```

`AccountTransaction` stores immutable movement history:

```text
DEPOSIT
WITHDRAWAL
TRANSFER_OUT
TRANSFER_IN
```

Transfers use the same `transferId` on both account-history rows and store the counterparty account id.

### Double-entry ledger

Every successful financial operation produces exactly one `journal_entry` and exactly two `ledger_entries` in v0.4.

```text
journal_entries
├── id
├── reference_type
├── reference_id
├── description
└── created_at

ledger_entries
├── id
├── journal_entry_id
├── ledger_account_id
├── side DEBIT | CREDIT
├── amount
└── created_at
```

Required invariant:

```text
SUM(DEBIT) = SUM(CREDIT)
```

Customer accounts map to one ledger account:

```text
CUSTOMER:<accountId>
type = CUSTOMER_LIABILITY
```

The technical clearing account is:

```text
TECH:CASH_CLEARING
```

### Deposit posting

For a deposit of `100.00`:

```text
DEBIT   TECH:CASH_CLEARING           100.00
CREDIT  CUSTOMER_LIABILITY(account)  100.00
```

### Withdrawal posting

```text
DEBIT   CUSTOMER_LIABILITY(account)  100.00
CREDIT  TECH:CASH_CLEARING           100.00
```

### Internal transfer posting

For transfer A → B:

```text
DEBIT   CUSTOMER_LIABILITY(A)  100.00
CREDIT  CUSTOMER_LIABILITY(B)  100.00
```

The transfer journal uses the existing business `transferId` as `reference_id`; no second transfer identifier is created.

## Atomicity

Operational state, movement history and ledger posting share the same Spring transaction.

Example deposit flow:

```text
BEGIN
  load account
  validate business rules
  update operational balance
  insert account_transactions row
  insert journal_entries row
  insert two ledger_entries rows
COMMIT
```

If ledger persistence fails, the operational balance and transaction history are rolled back too.

The same atomicity applies to withdrawals and internal transfers.

## Ledger immutability

`journal_entries` and `ledger_entries` are append-only.

At application level, entities expose no accounting-field setters. At database level, PostgreSQL triggers reject both:

```text
UPDATE
DELETE
```

Future corrections should be represented through compensating/reversal entries rather than mutation of historical accounting records.

## Opening-balance migration

Migration `V4__create_double_entry_ledger.sql` creates:

- `ledger_accounts`;
- `journal_entries`;
- `ledger_entries`;
- `TECH:CASH_CLEARING`;
- one `CUSTOMER_LIABILITY` for every existing CoreBank account.

Existing non-zero operational balances receive an `OPENING_BALANCE` journal:

```text
DEBIT   TECH:CASH_CLEARING
CREDIT  CUSTOMER_LIABILITY(account)
```

Zero-balance accounts receive their ledger account but no zero-value journal.

This allows existing accounts to reconcile immediately after upgrading to v0.4.

## Reconciliation

CoreBank can compare the operational balance with the customer-liability position in the ledger.

For a liability ledger account:

```text
ledger balance = credits - debits
```

Difference:

```text
operational balance - ledger balance
```

Statuses:

```text
RECONCILED
MISMATCH
```

A mismatch is reported only; CoreBank does **not** auto-correct either side.

### Reconciliation API

```http
GET /api/v1/reconciliation/accounts/{accountId}
```

Example response:

```json
{
  "accountId": "11111111-1111-1111-1111-111111111111",
  "operationalBalance": 850.00,
  "ledgerBalance": 850.00,
  "difference": 0.00,
  "status": "RECONCILED"
}
```

Missing accounts return `404 Not Found`.

## Business rules

### Account state

Accounts may be:

```text
ACTIVE
BLOCKED
CLOSED
```

Only `ACTIVE` accounts may participate in money movements.

### Money validation

- amount must be positive;
- BRL only;
- maximum two decimal places;
- silent rounding is not allowed;
- operational balance cannot become negative.

### Deposits and withdrawals

Each successful deposit or withdrawal creates:

- one operational balance change;
- one `account_transactions` row;
- one balanced accounting journal;
- two ledger entries.

Rejected operations create none of those financial records.

### Transfers

Transfers require:

- different source and destination accounts;
- both accounts exist;
- both accounts are `ACTIVE`;
- both accounts are BRL;
- sufficient source balance.

A successful transfer creates:

```text
TRANSFER_OUT on source
TRANSFER_IN  on destination
one TRANSFER journal
DEBIT source liability
CREDIT destination liability
```

All records share one ACID boundary.

### Concurrency

Accounts use optimistic locking with JPA `@Version`.

Concurrent movements cannot silently double-spend a stale balance or overwrite a destination credit. A stale write is treated as a conflict and the surrounding transaction rolls back.

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

Creating an account also creates its matching `CUSTOMER_LIABILITY` ledger account in the same transaction.

### Deposits

```http
POST /api/v1/accounts/{accountId}/deposits
```

### Withdrawals

```http
POST /api/v1/accounts/{accountId}/withdrawals
```

### Transaction history

```http
GET /api/v1/accounts/{accountId}/transactions
```

### Transfers

```http
POST /api/v1/transfers
```

### Reconciliation

```http
GET /api/v1/reconciliation/accounts/{accountId}
```

## Error contract

API failures use `application/problem+json`.

| HTTP | Meaning |
|---|---|
| `400` | malformed JSON, invalid UUID, invalid amount, too many decimals, invalid transfer input |
| `404` | customer or account does not exist |
| `409` | insufficient funds, blocked/closed account, integrity conflict, concurrent account update |
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

API:

```text
http://localhost:8080
```

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

## Verification

Full regression gate:

```bash
mvn clean verify
```

Important v0.4 suites include:

```bash
mvn -Dtest=LedgerServiceTest,ReconciliationServiceTest,ReconciliationControllerTest test
mvn -Dit.test=LedgerMigrationIT,LedgerPersistenceIT verify
mvn -Dit.test=AccountLedgerCreationIT,MoneyMovementLedgerIT verify
mvn -Dit.test=TransferLedgerIT,ConcurrentTransferIT verify
mvn -Dit.test=ReconciliationIT verify
```

PostgreSQL integration tests use Testcontainers.

JaCoCo report:

```text
target/site/jacoco/index.html
```

## Database migrations

Flyway owns schema changes and Hibernate uses schema validation.

```text
V1__create_corebank_schema.sql
V2__create_account_transactions.sql
V3__add_transfer_metadata.sql
V4__create_double_entry_ledger.sql
```

## Roadmap

| Release | Scope |
|---|---|
| v0.1 | Customers + Accounts |
| v0.2 | Deposits + Withdrawals + Transaction History |
| v0.3 | Account-to-account Transfers |
| **v0.4** | **Double-entry Ledger + Reconciliation** |
| v0.5 | Idempotency |
| v0.6 | Kafka events |
| v0.7 | Retry + DLQ |
| v0.8 | Redis |
| v0.9 | JWT + RBAC |
| v1.0 | Observability |
| v1.1 | GitHub Actions |
| v1.2 | AWS deployment |
| v2.0 | Service extraction / microservices |

## Why this project exists

CoreBank is a portfolio project focused on the engineering decisions behind transactional backend systems: business invariants, database guarantees, ACID boundaries, optimistic concurrency, accounting consistency, append-only audit records, reconciliation, integration testing and a deliberate path toward event-driven architecture.

The goal is not to simulate every banking feature at once. Each release introduces one group of concerns and keeps them observable, testable and explainable in code review or technical interviews.
