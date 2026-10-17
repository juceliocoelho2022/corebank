# CoreBank

**CoreBank** is a production-oriented **Banking as a Service (BaaS)** portfolio backend built with Java 21 and Spring Boot. Version **0.2.0** adds atomic deposits, withdrawals and immutable transaction history on top of the v0.1 customer/account foundation.

The project is intentionally evolved release by release so each version demonstrates a specific backend engineering concern before introducing distributed-system complexity.

## What v0.2 demonstrates

- Java 21 and Spring Boot 3
- Modular-monolith architecture
- REST APIs with Bean Validation
- PostgreSQL + Spring Data JPA
- Flyway migrations
- Atomic account balance updates
- Immutable money-movement history
- Optimistic locking with JPA `@Version`
- Transaction rollback guarantees
- RFC 9457-style `ProblemDetail` errors
- Swagger / OpenAPI
- Actuator health endpoint
- Unit, MVC, persistence and concurrency-focused tests
- Testcontainers + PostgreSQL
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
├── shared
│   ├── config
│   └── exception
└── CoreBankApplication
```

CoreBank remains a **modular monolith** in v0.2. `Account` owns the mutable balance and status. `AccountTransaction` is an append-only audit record of a successful movement. `TransactionService` coordinates both in one ACID transaction.

This avoids premature microservices while keeping clear module boundaries for future extraction.

# Business Rules

The business rules are intentionally documented here because they are part of the system design, not just implementation details.

## 1. Only ACTIVE accounts can move money

An account may be in one of three states:

```text
ACTIVE
BLOCKED
CLOSED
```

Only `ACTIVE` accounts accept deposits or withdrawals.

**Why:** a blocked or closed account must not change its financial position. Keeping this rule inside the `Account` aggregate prevents controllers or services from accidentally bypassing it.

**Result:** attempts to move money on `BLOCKED` or `CLOSED` accounts return `409 Conflict`.

Example:

```text
Account status: BLOCKED
Withdrawal: R$ 50.00
Result: rejected
Balance: unchanged
History: unchanged
```

## 2. Money-movement amounts must be positive

Deposits and withdrawals require:

```text
amount > 0.00
```

Zero and negative values are invalid.

**Why:** a deposit of `0.00` has no business meaning, and negative values would invert the semantic meaning of deposit/withdrawal.

**Result:** invalid values return `400 Bad Request`.

## 3. BRL monetary values use at most two decimal places

The API and database use decimal monetary values with scale 2:

```text
NUMERIC(19,2)
```

Examples:

```text
100.00   valid
10.50    valid
10.001   invalid
```

**Why:** CoreBank must never silently round a value submitted by a client. Silent rounding can create reconciliation differences in transactional systems.

## 4. Withdrawals cannot exceed the available balance

A withdrawal is accepted only when:

```text
balance >= withdrawal amount
```

Example:

```text
Current balance: R$ 100.00
Requested withdrawal: R$ 130.00
Result: 409 Conflict
Final balance: R$ 100.00
```

**Why:** v0.2 has no overdraft or credit-limit concept. Therefore the committed balance must always remain non-negative.

The same invariant is also protected at database level:

```text
balance >= 0
```

## 5. Every successful movement creates exactly one history record

A successful deposit or withdrawal produces one immutable `account_transactions` row containing:

```text
transaction id
account id
type
amount
balance before
balance after
description
created at
```

Example:

```text
Balance before: R$ 200.00
Withdrawal:     R$ 50.00
Balance after:  R$ 150.00

History:
WITHDRAWAL | 50.00 | before=200.00 | after=150.00
```

**Why:** storing before/after snapshots makes troubleshooting and audit analysis easier and prepares the model for the double-entry ledger planned for v0.4.

Transaction history is append-only in application behavior. v0.2 exposes no update or delete endpoint for financial movements.

## 6. Balance change and transaction history are atomic

A movement is processed inside a single database transaction:

```text
BEGIN
  load account
  validate business rules
  update account balance
  insert transaction history
COMMIT
```

If any step fails:

```text
ROLLBACK
```

**Why:** a financial system must never commit a changed balance without its corresponding audit record, nor create a transaction record without the matching balance change.

Required invariant:

```text
changed balance <=> exactly one committed history record
```

## 7. Rejected operations do not create history

If a request fails because of:

- insufficient balance;
- invalid amount;
- blocked account;
- closed account;
- missing account;
- concurrency conflict;

then both balance and transaction history remain unchanged.

**Why:** transaction history represents accepted financial events, not failed attempts. Operational/security audit events can be introduced separately in a later observability/audit module.

## 8. Concurrent withdrawals cannot spend the same balance twice

Accounts use optimistic locking through JPA `@Version`.

Critical scenario:

```text
Initial balance: R$ 100.00

Request A -> withdraw R$ 80.00
Request B -> withdraw R$ 80.00
```

Both requests may initially observe the same balance, but only one versioned account update may commit.

Expected committed state:

```text
Successful withdrawals: 1
Conflicting withdrawals: 1
Final balance: R$ 20.00
Withdrawal history rows: 1
```

**Why:** without concurrency control, both requests could subtract from the same original balance and create an invalid financial state.

A stale concurrent update is returned as `409 Conflict` and implementation details such as Hibernate/SQL exceptions are not exposed to API clients.

## 9. Transaction descriptions are optional and normalized

Descriptions:

- are optional;
- are trimmed before persistence;
- whitespace-only values become `null`;
- have a maximum length of 200 characters.

**Why:** this avoids meaningless blank values and keeps storage/API contracts deterministic.

## 10. Existing v0.1 account rules remain valid

### Customer

- document is unique;
- email is unique case-insensitively at database level;
- name length is 3–120 characters.

### Account

- default branch: `0001`;
- currency: `BRL`;
- initial status: `ACTIVE`;
- initial balance: `0.00`;
- account number comes from PostgreSQL sequence starting at `10000001`;
- balance is `NUMERIC(19,2)` and cannot be negative;
- account updates use optimistic locking.

## Transaction data model

```text
account_transactions
├── id UUID
├── account_id UUID
├── type DEPOSIT | WITHDRAWAL
├── amount NUMERIC(19,2)
├── balance_before NUMERIC(19,2)
├── balance_after NUMERIC(19,2)
├── description VARCHAR(200)
└── created_at TIMESTAMPTZ
```

Database constraints independently protect positive amounts, valid types and non-negative balance snapshots.

## API

### Create customer

```http
POST /api/v1/customers
```

### Create account

```http
POST /api/v1/accounts
```

### Get account

```http
GET /api/v1/accounts/{accountId}
```

### Get balance

```http
GET /api/v1/accounts/{accountId}/balance
```

### Deposit

```http
POST /api/v1/accounts/{accountId}/deposits
Content-Type: application/json
```

```json
{
  "amount": 1000.00,
  "description": "Initial deposit"
}
```

Success: `201 Created`

```json
{
  "transactionId": "98b5d7e5-...",
  "accountId": "5a38c8ca-...",
  "type": "DEPOSIT",
  "amount": 1000.00,
  "balanceBefore": 0.00,
  "balanceAfter": 1000.00,
  "description": "Initial deposit",
  "createdAt": "2026-10-17T12:00:00Z"
}
```

### Withdrawal

```http
POST /api/v1/accounts/{accountId}/withdrawals
```

```json
{
  "amount": 250.00,
  "description": "ATM withdrawal"
}
```

Success: `201 Created`.

### Transaction history

```http
GET /api/v1/accounts/{accountId}/transactions
```

Returns newest transactions first.

## Error contract

API failures use `application/problem+json`.

| HTTP | Meaning |
|---|---|
| `400` | malformed JSON, invalid UUID, invalid amount, too many decimals, description too long |
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

## Tests

Complete verification:

```bash
mvn clean verify
```

Important v0.2 suites:

```bash
mvn -Dtest=AccountTest test
mvn -Dtest=AccountTransactionPersistenceIT test
mvn -Dtest=TransactionServiceTest test
mvn -Dtest=ApiExceptionHandlerTest test
mvn -Dtest=TransactionControllerTest test
mvn -Dtest=MoneyMovementIT,ConcurrentWithdrawalIT test
```

PostgreSQL integration tests use Testcontainers.

JaCoCo report:

```text
target/site/jacoco/index.html
```

## Database migrations

Flyway owns schema changes and Hibernate uses:

```text
ddl-auto: validate
```

Migrations:

```text
V1__create_corebank_schema.sql
V2__create_account_transactions.sql
```

## Roadmap

| Release | Scope |
|---|---|
| v0.1 | Customers + Accounts |
| **v0.2** | **Deposits + Withdrawals + Transaction History** |
| v0.3 | Account-to-account Transfers |
| v0.4 | Double-entry Ledger |
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

CoreBank is a portfolio project focused on the engineering decisions behind transactional backend systems: explicit business rules, database invariants, ACID boundaries, concurrency control, error contracts, integration testing, and an incremental path toward event-driven architecture.

The goal is not to simulate every banking feature at once. Each release introduces one group of concerns and keeps them observable, testable and explainable in code review or technical interviews.
