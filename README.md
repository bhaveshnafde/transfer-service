# Transfer Service

Orchestrates a money transfer across the Limits, Account, Ledger and Notification services.
It owns the transfer's state machine, makes `POST /transfers` safe to repeat (Challenge 2), and
runs the transfer as an orchestrated saga that undoes completed steps when a later one fails
(Challenge 3C).

Spring Boot 3.5 · Java 21 · PostgreSQL 16 · Flyway · springdoc OpenAPI

## Run it

Prerequisites: JDK 21, Maven 3.9+, Docker (only for the database).

```bash
# 1. This service's own database (port 5433, db transfer_db, user transfer_user)
docker compose -f docker-compose.dev.yml up -d

# 2. Stand-ins for the other four services, until the real ones are running (separate terminal)
java dev-stubs/DevStubs.java

# 3. The service, on port 8082
mvn spring-boot:run

# 4. Check it
curl localhost:8082/health
scripts/smoke.sh
```

With the real services running instead of the stubs, point the URLs at them (see below) and skip step 2.

| What | Where |
| --- | --- |
| Health | `GET http://localhost:8082/health` |
| Swagger UI | http://localhost:8082/swagger-ui.html |
| OpenAPI spec | `GET /v3/api-docs.yaml`. Export it for the report with `curl localhost:8082/v3/api-docs.yaml > docs/openapi/transfer-service.yaml` |

### Tests

```bash
mvn verify
```

No database needed: tests use in-memory H2 in PostgreSQL mode, and the other services are mocked.

### Configuration

Every setting comes from an environment variable; defaults are in `src/main/resources/application.yml`
and all variables are listed in `.env.example`.

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | 8082 | HTTP port |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5433/transfer_db`, `transfer_user`, `transfer_pass` | This service's own database |
| `ACCOUNT_SERVICE_URL` | `http://localhost:8081` | Account service |
| `LEDGER_SERVICE_URL` | `http://localhost:8083` | Ledger service |
| `LIMITS_SERVICE_URL` | `http://localhost:8084` | Limits/Fraud service |
| `NOTIFICATION_SERVICE_URL` | `http://localhost:8085` | Notification mock |
| `TRANSFER_MAX_AMOUNT` | 100000.00 | BR1 per-transaction cap |
| `DOWNSTREAM_CONNECT_TIMEOUT_MS`, `DOWNSTREAM_READ_TIMEOUT_MS` | 1000, 3000 | Timeouts on every downstream call |
| `RETRY_MAX_ATTEMPTS`, `RETRY_BASE_DELAY_MS`, `RETRY_MAX_DELAY_MS` | 3, 200, 2000 | Retry with exponential backoff and jitter |
| `RECOVERY_ENABLED`, `RECOVERY_INTERVAL_MS`, `RECOVERY_STUCK_AFTER_SECONDS` | true, 15000, 60 | Recovery job for stuck transfers |
| `LOG_FORMAT` | logstash | JSON logs (`logstash` or `ecs`) |

## API

| Method | Path | Success | Errors |
| --- | --- | --- | --- |
| POST | `/api/v1/transfers` (header `Idempotency-Key` required) | 201 COMPLETED, 202 still confirming | 400, 404, 409, 422, 503 |
| GET | `/api/v1/transfers/{id}` | 200 | 404 TRANSFER_NOT_FOUND |
| GET | `/api/v1/transfers/{id}/saga-steps` | 200, audit trail of steps and undos | 404 |
| GET | `/health` | 200 UP | 503 DOWN |

```bash
curl -i localhost:8082/api/v1/transfers \
  -H "Content-Type: application/json" -H "Idempotency-Key: demo-001" \
  -d '{"sourceAccountId":"ACC-1001","destinationAccountId":"ACC-2002","amount":1500.00,"currency":"INR"}'
```

Errors use the team's shared format:

```json
{"error": {"code": "INSUFFICIENT_FUNDS", "message": "...", "requestId": "...", "details": [{"transferId": "...", "status": "REJECTED"}]}}
```

| Status | Codes |
| --- | --- |
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `MISSING_IDEMPOTENCY_KEY`, `INVALID_IDEMPOTENCY_KEY` |
| 404 | `ACCOUNT_NOT_FOUND` (from Account), `TRANSFER_NOT_FOUND` |
| 409 | `INSUFFICIENT_FUNDS` (from Account), `REQUEST_IN_PROGRESS` |
| 422 | `BR1_AMOUNT_NOT_POSITIVE`, `BR1_AMOUNT_ABOVE_CAP`, `BR1_TOO_MANY_DECIMALS`, `BR4_SAME_ACCOUNT`, `LIMIT_EXCEEDED` (from Limits), `ACCOUNT_NOT_ACTIVE` (from Account), `IDEMPOTENCY_KEY_REUSED` |
| 503 | `<SERVICE>_UNAVAILABLE` after retries, e.g. `LEDGER_UNAVAILABLE`; every completed step has been undone |

Every request and log line carries `X-Request-ID` (taken from the caller or generated), and it is
forwarded on every call to other services.

## The transfer saga

```
POST /transfers
   │
   ├─ 1 Limits   reserve limit usage  ─┐
   ├─ 2 Account  place hold           ─┤  undo in reverse order if a later step fails:
   ├─ 3 Ledger   post journal         ─┘  reverse journal → release hold → release limit
   ├─ 4 Account  capture hold   (commit point: debit source + credit destination in one local transaction)
   └─ 5 Notification            (async after the response; failure never fails the transfer)
```

State machine (BR6), enforced in `Transfer.transitionTo`:

```
PENDING → LIMIT_RESERVED → FUNDS_HELD → LEDGER_POSTED → COMPLETED
   │            │               │              │
   └────────────┴──── COMPENSATING ────────────┘
   │                       │
   └──→ REJECTED ←─────────┴──→ FAILED
```

- **REJECTED**: a business rule said no (limit exceeded, insufficient funds). 4xx.
- **FAILED**: a dependency gave no answer after retries; everything done so far was undone. 503.

Design choices worth explaining in the report:

1. **Orchestration, not choreography.** One place holds the transfer's state and the undo order, which
   makes the failure path easy to follow, test and demo. The trade-off is that the Transfer service
   knows about all the others.
2. **Each step is saved before the next starts.** No database transaction is held open across an HTTP
   call, and a crash never loses progress.
3. **A step that timed out may still have happened.** So the undo always includes the failed step
   itself, and every downstream call is idempotent per `transferId`. Undoing something that never
   happened is harmless (404 is treated as done).
4. **Capture has unknown-outcome handling.** If capture times out, the money may already have moved,
   so the transfer stays `LEDGER_POSTED` and the recovery job retries the capture instead of undoing.
5. **Retries only on "no answer".** Timeouts, connection errors and 5xx are retried with exponential
   backoff and jitter (`RetryPolicy`). A 4xx is a definite answer and is never retried.
6. **Recovery job.** Every 15 s it finishes transfers stuck for 60 s or more: after a crash, a failed
   undo (`COMPENSATING`) or an unknown capture (`LEDGER_POSTED`). The `@Version` column makes it safe
   alongside live requests.

## Challenge 2: idempotency

1. The request is fingerprinted (SHA-256 of source, destination, normalised amount, currency).
2. In one short transaction the service INSERTs `(key, fingerprint)` into `idempotency_keys` and creates
   the PENDING transfer (`TransferClaimer`).
3. The primary key on `idempotency_keys` decides the winner when identical requests arrive at the same
   moment. PostgreSQL makes the second INSERT wait for the first and then fail with a duplicate key, so
   only one saga ever starts. A Java "SELECT, then INSERT if missing" would not work: both requests
   could SELECT before either INSERTs.
4. A repeat then gets one of three answers:
   - **Different fingerprint:** 422 `IDEMPOTENCY_KEY_REUSED`.
   - **Finished:** the stored status and JSON body, byte for byte, with header `Idempotent-Replayed: true`.
   - **Still running:** 409 `REQUEST_IN_PROGRESS`, with the transferId to poll.

Evidence: `scripts/challenge2-idempotency.sh` fires 10 identical requests at once (`N=20` for more), then
a repeat, then a changed body, and writes everything to `evidence/challenge-2-idempotency/`.

## Challenge 3C: forced failure at step 3

```bash
USE_STUBS=1 scripts/challenge3c-saga-failure.sh   # with the dev stubs
scripts/challenge3c-saga-failure.sh               # with the real services, after stopping Ledger
```

The response is 503 `LEDGER_UNAVAILABLE`, and `GET /transfers/{id}/saga-steps` shows:

```
EXECUTE    LIMIT_RESERVATION  SUCCEEDED
EXECUTE    FUNDS_HOLD         SUCCEEDED
EXECUTE    LEDGER_JOURNAL     UNAVAILABLE   (after 3 attempts with backoff)
COMPENSATE LEDGER_JOURNAL     SUCCEEDED
COMPENSATE FUNDS_HOLD         SUCCEEDED
COMPENSATE LIMIT_RESERVATION  SUCCEEDED
```

With the stubs, the script also prints account balances before and after: unchanged, hold RELEASED.

## Business rules: where they live and where they are tested

| Rule | Code | Test |
| --- | --- | --- |
| BR1 amount > 0, ≤ cap, ≤ 2 decimals | `domain/TransferRules` | `TransferRulesTest`, `TransferApiIntegrationTest.BR1_*` |
| BR2 balance never negative | Account service (rejection handled in `TransferSagaOrchestrator`) | `TransferSagaOrchestratorTest.BR2_*` |
| BR3 daily limit | Limits service (rejection handled in `TransferSagaOrchestrator`) | `TransferSagaOrchestratorTest.BR3_*` |
| BR4 different accounts (active + same currency in Account) | `domain/TransferRules` | `TransferRulesTest.BR4_*` |
| BR6 only allowed transitions | `domain/TransferStatus`, `Transfer.transitionTo` | `TransferStatusTest` |

BR5 (balanced journal) belongs to the Ledger service.

## Contracts this service expects from the others

All calls send `X-Request-ID` and `Idempotency-Key: <transferId>:<step>`, and must be idempotent per
`transferId`. Errors use the shared format, so their `code` is passed through to the client.

| Service | Call | Answers |
| --- | --- | --- |
| Limits | `POST /api/v1/limit-reservations` `{transferId, accountId, amount, currency}` | 201 (200 if repeated); 422 `LIMIT_EXCEEDED` |
| Limits | `DELETE /api/v1/limit-reservations/{transferId}` | 204; 404 if nothing to release |
| Account | `POST /api/v1/holds` `{transferId, sourceAccountId, destinationAccountId, amount, currency}` | 201 (200 if repeated); 409 `INSUFFICIENT_FUNDS`; 422 `ACCOUNT_NOT_ACTIVE`, `CURRENCY_MISMATCH`; 404 `ACCOUNT_NOT_FOUND` |
| Account | `POST /api/v1/holds/{transferId}/capture` | 200 (200 if repeated); 409 `HOLD_NOT_ACTIVE` |
| Account | `POST /api/v1/holds/{transferId}/release` | 200 (200 if repeated); 404 if no hold; 409 `HOLD_ALREADY_CAPTURED` |
| Ledger | `POST /api/v1/journals` `{transferId, currency, entries: [{accountId, direction: DEBIT/CREDIT, amount}]}` | 201 (200 if repeated) |
| Ledger | `POST /api/v1/journals/{transferId}/reversal` | 201 (200 if repeated); 404 if no journal |
| Notification | `POST /api/v1/notifications` `{transferId, accountId, channel, template, message}` | 202 |

`dev-stubs/DevStubs.java` implements exactly these, so it doubles as an executable spec for teammates.

## Code layout

```
src/main/java/com/moneytransfer/transfer/
  domain/            Transfer, TransferStatus (BR6), TransferRules (BR1, BR4). Plain Java.
  domain/saga/       TransferSagaOrchestrator, RetryPolicy, ports to other services. Plain Java.
  application/       Idempotent submit flow, response rendering, async notification, recovery job
  infrastructure/    JPA/JDBC persistence and HTTP clients
  web/               Controllers, correlation-ID filter, error handling, DTOs
  config/            Properties (env vars) and wiring
src/main/resources/db/migration/   Flyway schema
dev-stubs/                         Throwaway stand-ins for the other services (dev only)
scripts/                           smoke test and challenge evidence scripts
```

The saga and business rules have no Spring dependency, so their unit tests run in milliseconds
without a database.

## Known limitations

- The recovery job assumes one instance. With several replicas, claim stuck rows with
  `SELECT ... FOR UPDATE SKIP LOCKED` or a lease column.
- If an undo is permanently refused (e.g. Account answers `HOLD_ALREADY_CAPTURED`), the transfer stays
  `COMPENSATING` and is logged at ERROR for manual reconciliation. There is no reconciliation endpoint.
- A repeat that arrives while the first request is still running gets 409 rather than waiting for the
  result.
- Notifications are sent in-process after the response. If the service crashes in between, that
  notification is lost. An outbox table would fix this.
- Idempotency records are kept forever; a real system would expire them after, say, 24 hours.
