# Balance Service

A small service that keeps account balances correct under concurrent, retried and duplicated requests.
Java 21, Spring Boot 3.5, PostgreSQL, Flyway.

```
./mvnw test                  # build + all tests (no external dependencies needed)
docker compose up -d --build    # Postgres + the service on :8080
```

## Running

**Requirements:** JDK 21. Nothing else for the tests. The Maven wrapper downloads Maven on first run.

- `./mvnw test` runs the whole suite against an in-memory H2 database (PostgreSQL mode).
  If Docker is available, the concurrency suite *also* runs against a real PostgreSQL 16 through
  Testcontainers. Without Docker that class is skipped, not failed.
- `docker compose up --build` starts PostgreSQL and the service. To run the service from the IDE instead,
  start only the database (`docker compose up postgres`) and run `BalanceServiceApplication`.

Configuration (environment variables): `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_SIZE`.

### HTTP API

| Method | Path                                | Body                                                              |
|--------|-------------------------------------|-------------------------------------------------------------------|
| POST   | `/api/v1/accounts`                  | `{"accountId"}`                                                   |
| GET    | `/api/v1/accounts/{id}`             | –                                                                 |
| POST   | `/api/v1/accounts/{id}/credits`     | `{"amount", "transactionId"}`                                     |
| POST   | `/api/v1/accounts/{id}/debits`      | `{"amount", "transactionId"}`                                     |
| POST   | `/api/v1/transfers`                 | `{"sourceAccountId", "destinationAccountId", "amount", "transactionId"}` |

```bash
curl -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' -d '{"accountId":"A"}'
curl -X POST localhost:8080/api/v1/accounts/A/credits -H 'Content-Type: application/json' \
     -d '{"amount":1000,"transactionId":"TX-1"}'
curl localhost:8080/api/v1/accounts/A
```

Errors are RFC 9457 problem documents with a stable `code` field:

| Status | code                                           | When                                             |
|--------|------------------------------------------------|--------------------------------------------------|
| 400    | `INVALID_REQUEST`                              | bad amount, blank/oversized id, same-account transfer |
| 404    | `ACCOUNT_NOT_FOUND`                            |                                                  |
| 409    | `TRANSACTION_ID_REUSED`                        | same `transactionId`, different operation        |
| 409    | `ACCOUNT_ALREADY_EXISTS`                       |                                                  |
| 422    | `INSUFFICIENT_FUNDS`, `BALANCE_LIMIT_EXCEEDED` | valid request declined by a business rule        |
| 503    | `BUSY` (+ `Retry-After`)                       | lock wait timed out; safe to retry with the same id |

Amounts are `long` minor units (cents, rials...). No floating point anywhere.

## Architecture

```
api/          BalanceController, ApiExceptionHandler   HTTP only, no business logic
service/      BalanceService (the required interface)
              JpaBalanceService                         locking, idempotency, rules
              Operation                                 validated request value object
account/      Account entity + repository, AccountService (opening accounts)
transaction/  TransactionRecord entity + repository     idempotency key + audit trail
error/        domain exceptions
```

Two tables:

- `account(id, balance, created_at)`, with `CHECK (balance >= 0)` as a last line of defence.
- `transaction_record(id = transactionId, type, source, destination, amount, status, rejection_reason, created_at)`.
  One row per transactionId, written **in the same database transaction** as the balance change.

The database is the single source of truth and the only place where coordination happens. There are no
in-process locks or caches, so the service is stateless and any number of instances can run side by side.

I picked this over an in-memory design (a `ConcurrentHashMap` of accounts with a `ReentrantLock` each).
That design is simpler and faster, but it loses all money on restart and can't scale past one instance.
For a financial ledger, durability and horizontal scaling aren't optional, and an RDBMS already gives
row locks, atomic commits and unique constraints.

## Concurrency

Every operation is one database transaction (READ COMMITTED):

1. `SELECT ... FOR UPDATE` on each involved account, **in ascending id order**.
2. Look up the `transactionId` in `transaction_record` (see Idempotency).
3. Check the business rules against the locked, current balances.
4. Update balances and insert the `transaction_record` row.
5. Commit, which releases the locks.

**Same account:** requests on one account queue on its row lock and run one after another. The
check-then-act in step 3 is safe because nobody else can change the balance between the check and the
update. With `balance = 1000` and two concurrent `debit(700)`, the second one sees 300 and is declined.
No lost updates, no negative balance.

**Different accounts:** row-level locks, so operations on A and B never wait for each other.
`lockOnOneAccountDoesNotBlockAnotherAccount` holds a lock on A and checks that B still goes through
while A waits.

**Why pessimistic and not optimistic locking (`@Version`)?** Balances are classic hot rows: a merchant
account can get many writes per second. Under optimistic locking every conflict costs a full retry, and
under real contention retries pile up (most work is wasted, latency becomes unpredictable). A row lock
makes contending requests wait in line and each one succeeds on its first attempt. The cost is that
throughput on a *single* account is bounded by how fast one transaction commits, and a connection is held
while waiting. Lock waits are capped (`lock_timeout = 5s`); past that the client gets a 503 and can retry.

**Why not Redis/Kafka?** A distributed lock in Redis plus a balance in the database means two systems that
can disagree, and it still needs fencing to be correct. Partitioning commands per account through Kafka is
a valid design for very high throughput, but turns a synchronous API into an asynchronous one and adds a
lot of moving parts. Neither is justified at this scale, so I left them out (see "What I'd do next").

## Idempotency

`transaction_record.id` is the `transactionId` and the primary key. The record is written atomically with
the balance change, so "balance changed" and "transaction recorded" are always both true or both false.

Flow for a request:

1. **Fast path:** if a record already exists, answer from it without taking any account lock. A retry
   storm doesn't queue behind real work.
2. Otherwise lock the accounts, then **check again**. A duplicate may have committed while we were
   waiting for the lock. That's why READ COMMITTED matters here: each statement sees the latest committed
   rows.
3. Apply and insert the record.

**Concurrent duplicates** (the same request arriving twice at once) touch the same accounts, so they
serialize on the row lock. The first applies; the second gets the lock, finds the record, and returns the
same result. **The same id used concurrently for operations on different accounts** doesn't serialize on
account locks. There the primary key decides: exactly one insert commits, the other transaction rolls
back completely, and the caller gets `TRANSACTION_ID_REUSED`. Both cases are covered by concurrent tests.

Semantics I chose:

- **Same id, same operation → same outcome**, including failures. If a debit was declined for insufficient
  funds, a retry with the same id is declined again, even if the account was topped up in between.
  Otherwise a client retrying on a timeout could get a payment through minutes later without meaning to.
  To try again, the client sends a new transactionId. Rejections are stored with `status = REJECTED`.
- **Same id, different operation** (amount, type or accounts) → `409 TRANSACTION_ID_REUSED`. Silently
  returning the old result would hide a real client bug.
- **Requests that fail validation** (bad amount, unknown account) are **not** recorded. Nothing was
  processed, so the id stays free for a corrected request.
- Records are kept forever. That's fine for an audit table, but see "Limitations".

## Transfer

**Atomicity** comes from the database transaction: the debit, the credit and the record commit together
or not at all. All rules (source has funds, destination won't overflow) are checked before anything is
modified. If anything fails in the middle (an exception, a crash, a lost connection), the transaction rolls
back and both balances stay as they were. The amount debited and credited is the same `long` by
construction.

**Deadlocks:** the classic case is `A→B` locking A then waiting for B, while `B→A` holds B and waits for A.
This can't happen here, because every operation takes its locks in the same global order (ascending
account id). With a total order, a wait-for cycle is impossible. `opposingTransfersDoNotDeadlock` runs 1,000
transfers in both directions at once and must finish with exact balances.

The remaining theoretical risk would come from new code that locks rows outside this path or in a different
order. If the database ever detects a deadlock anyway, it aborts one transaction. Nothing is committed and
the client gets a retryable 503.

**Same-account transfer** (`transfer(A, A, ...)`) is rejected with `400 INVALID_REQUEST` and nothing is
recorded. It has no meaningful effect on money, and it's far more likely a client bug than intent.
Treating it as a no-op success would hide that bug and leave a confusing record in the audit trail.

## Validation

- `amount <= 0` → 400.
- blank or > 64-character ids (the column width) → 400.
- unknown account → 404. Nothing is changed.
- a credit that would overflow `long` → 422 `BALANCE_LIMIT_EXCEEDED`, recorded like any other rejection.
- schema-level `CHECK` constraints repeat the critical invariants (non-negative balance, positive amount,
  a consistent shape per transaction type), so a bug in the code can't silently corrupt data.

All validation happens in the service, not the controller, so callers of `BalanceService` get exactly
the same behaviour as HTTP clients.

## Testing

| Class                     | What it covers                                                         |
|---------------------------|------------------------------------------------------------------------|
| `BalanceServiceTest`      | each operation, failures leave state untouched, validation, overflow   |
| `IdempotencyTest`         | sequential retries for credit/debit/transfer, rejected retries, id reuse |
| `H2ConcurrencyTest`       | concurrency suite on H2                                                |
| `PostgresConcurrencyTest` | the same suite on real PostgreSQL (needs Docker, skipped otherwise)    |
| `BalanceControllerTest`   | HTTP status codes and error mapping                                    |

Concurrency scenarios (`AbstractConcurrencyTest`):

- two overlapping `debit(700)` on 1,000, run 20 times: exactly one succeeds, balance 300
- 1,000 debits of 100 on 50,000: exactly 500 succeed, final balance 0
- 500 credits of 50 + 500 debits of 30 on 100,000: final balance exactly 110,000 (any lost update shows here)
- 200 concurrent copies of one credit, 100 of one transfer: applied once
- one transactionId raced on two different accounts: applied once
- 1,000 opposing transfers A↔B: no deadlock, balances unchanged
- 2,000 random transfers across 8 accounts with amounts large enough that many get declined: every balance
  equals what the *successful* transfers imply (they commute, so order doesn't matter), and total money
  is conserved
- a held lock on A doesn't block B

To make races likely, `ConcurrentRunner` holds all workers at a start gate and releases them together.
Every test asserts an exact end state. "No exception" proves nothing: lost updates only show up as
wrong numbers.

Tests share one database and never clean it. Each test creates its own accounts with random ids, so the
tests are independent and order doesn't matter.

**H2 vs PostgreSQL:** H2 is there so `./mvnw test` works on any machine. Locking is ultimately a property of
the database, though, which is why the same suite also runs against PostgreSQL when Docker is present.
That's the one I'd trust in CI.

## Technology choices

| Tech           | Why / what it solves                                         | Trade-off                                          |
|----------------|--------------------------------------------------------------|----------------------------------------------------|
| PostgreSQL     | durable state, row locks, atomic commits, unique constraints | single-account throughput limited by commit latency |
| Spring Data JPA| standard, pessimistic locks are one annotation               | less control over SQL than plain JDBC              |
| Flyway         | versioned schema; Hibernate never touches DDL (`ddl-auto: none`) | one more thing to maintain                     |
| H2 (tests)     | tests need nothing installed                                 | not identical to Postgres, hence Testcontainers    |
| Testcontainers | real-database confidence for the concurrency suite           | needs Docker; slower                               |
| Docker Compose | one-command local run                                        | –                                                  |

## Performance characteristics

- An operation does 1–2 locking `SELECT`s, 1–2 `UPDATE`s and one `INSERT`, in one round trip per statement
  and one commit. Retries of completed transactions cost one primary-key lookup and take no locks.
- Throughput scales with the number of *distinct* accounts being touched. A single hot account is processed
  serially, so its throughput is roughly 1 / (transaction duration).
- `getBalance` is a plain read and never waits for writers (MVCC).
- A waiting request holds a pool connection. The pool size (`DB_POOL_SIZE`) together with `lock_timeout`
  bounds how much a hot account can tie up.

## Limitations / what I'd do next

Implemented: everything in the brief (credit, debit, atomic transfer, concurrency, idempotency including
concurrent duplicates, validation, tests, README), plus the REST API, Postgres/Flyway and Docker setup.

Not done, and how I'd approach it:

- **Double-entry ledger.** `transaction_record` is an audit log, but the balance is still a mutable column.
  A proper ledger would store immutable debit/credit entries and treat the balance as a derived,
  cached value, which makes reconciliation possible.
- **Hot accounts.** If one account becomes a bottleneck: split it into N sub-balances and spread writes,
  or batch credits. Further out, partition commands by account id (e.g. Kafka keyed by account) with a
  single writer per partition.
- **Idempotency key retention.** Records are kept forever. Keys could expire after a retention window
  with the audit data moved elsewhere, as long as clients are told how long retries are honoured.
- **Idempotency key scope.** transactionIds are global. In a multi-tenant system they'd be scoped per
  client (`(client_id, transaction_id)`).
- **Observability.** Metrics for lock wait time, rejections and 503s per account would be the first
  thing to add; health endpoints are already exposed through Actuator.
- **AuthN/AuthZ**, rate limiting and an OpenAPI spec are out of scope here.
- Account closure, currencies, and overdraft limits aren't modelled.
