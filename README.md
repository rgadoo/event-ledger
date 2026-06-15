# Event Ledger

Two microservices that ingest financial transaction events and maintain account balances,
built to behave correctly when upstream systems deliver events **out of order** or **more than
once**, and to **degrade gracefully** when a downstream service is unavailable.

- **Event Gateway** (public, port `8080`) — validates input, enforces idempotency, stores events,
  and applies them to the Account Service through a circuit breaker.
- **Account Service** (internal, port `8081`) — owns account balances and transaction history.

---

## Table of Contents

- [Architecture](#architecture)
- [API Contract](#api-contract)
- [How It Meets the Requirements](#how-it-meets-the-requirements)
- [Design Decisions](#design-decisions)
- [Assumptions](#assumptions)
- [Tech Stack](#tech-stack)
- [Running the System](#running-the-system)
- [Running the Tests](#running-the-tests)
- [Observability Endpoints](#observability-endpoints)
- [Try It (curl)](#try-it-curl)
- [Bonus Features & Future Work](#bonus-features--future-work)

---

## Architecture

```
                      ┌───────────────────────┐         REST  (sync)
  Client ───────────► │   Event Gateway        │  ───── circuit breaker + timeout ─────►  ┌──────────────────────┐
                      │   :8080  (public)      │        W3C traceparent header             │  Account Service      │
                      │   H2: events           │  ◄────────────────────────────────────   │  :8081  (internal)    │
                      └───────────┬───────────┘                                            │  H2: transactions     │
                                  │                                                        └───────────┬──────────┘
                                  │  OTLP                                          OTLP                 │
                                  ▼                                                                     ▼
                          ┌───────────────┐        ┌─────────────┐        ┌────────────┐
                          │ OTel Collector │ ─────► │   Jaeger    │        │ Prometheus │  ◄── scrapes /actuator/prometheus
                          └───────────────┘        │  UI :16686  │        │   :9090    │      on both services
                                                   └─────────────┘        └────────────┘
```

Each service is an independent Spring Boot process with its **own in-memory H2 database** — no
shared database, no shared in-process state. They communicate over synchronous REST. A trace
context generated at the Gateway is propagated to the Account Service via the W3C `traceparent`
header, so a single client request is one traceable path across both services.

**Why the Account Service owns balance as a fold.** The balance is computed as
`sum(CREDIT) − sum(DEBIT)` over the stored transactions. Because it's a fold over a set, the
**arrival order is irrelevant** and **replaying an event is harmless** — the two hard requirements
(out-of-order, duplicates) fall out of the data model rather than needing special-case code.

---

## API Contract

### Event Gateway (public, `:8080`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/events` | Submit a transaction event |
| `GET` | `/events/{id}` | Retrieve a single event (Gateway-local) |
| `GET` | `/events?account={accountId}` | List an account's events, ordered by `eventTimestamp` (Gateway-local) |
| `GET` | `/accounts/{accountId}/balance` | Balance, proxied to the Account Service through the circuit breaker |
| `GET` | `/health` | Health + DB connectivity |

`POST /events` body:

```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": { "source": "mainframe-batch", "batchId": "B-9042" }
}
```

Status codes: `201` new event applied · `200` duplicate (original returned) · `400` validation
error · `503` Account Service unavailable (event stored locally, retryable).

### Account Service (internal, `:8081`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/accounts/{accountId}/transactions` | Apply a transaction (idempotent on `eventId`) |
| `GET` | `/accounts/{accountId}/balance` | Current balance + credit/debit counts |
| `GET` | `/accounts/{accountId}` | Account details + recent transactions |
| `GET` | `/health` | Health + DB connectivity |

The inter-service contract is also published as **OpenAPI** on each service (`/swagger-ui.html`).

---

## How It Meets the Requirements

| Requirement | Implementation |
|---|---|
| **Idempotency** | `eventId` is the primary key in both stores. The Gateway returns the original on a duplicate; the Account Service treats a repeat insert as a no-op. Balance is never altered twice. |
| **Out-of-order tolerance** | Listings sort by `eventTimestamp`; balance is an order-independent fold. |
| **Balance** | `sum(CREDIT) − sum(DEBIT)`, computed in `AccountService.balance()`. |
| **Validation** | Bean Validation on the request DTO (`@NotBlank`, `@Positive`, enum-bound `type`) → `400` with field-level messages. |
| **Service separation** | Two independent Spring Boot apps, each with its own H2 instance; no shared code/state. |
| **Distributed tracing** | Micrometer Tracing + OpenTelemetry bridge; W3C `traceparent` propagated Gateway → Account; trace IDs in both services' logs. |
| **Structured logging** | JSON via Logback + Logstash encoder, with `timestamp`, `level`, `service`, `traceId`, `spanId`. |
| **Health checks** | `GET /health` on both services with a live DB-connectivity check (plus Actuator at `/actuator/health`). |
| **Custom metrics** | `gateway_events_total{type,outcome}` and `ledger_transactions_applied_total{type,outcome}`, plus circuit-breaker state, on `/actuator/prometheus`. |
| **Resiliency** | Resilience4j **circuit breaker + timeout** on the Gateway → Account call. |
| **Graceful degradation** | Account down → `POST /events` returns `503` (not a hang/500); `GET /events/{id}` and `?account=` keep working; balance proxy returns a clear `503`. |
| **Docker Compose** | `docker compose up` starts both services + the observability stack. |
| **Tests** | 16 tests across core, resiliency, trace propagation, and full integration. |

---

## Design Decisions

### Resiliency: circuit breaker + timeout (and *why*)

The Gateway guards every call to the Account Service with a **Resilience4j circuit breaker**
combined with **connect/read timeouts** on the HTTP client.

- **Timeout** bounds how long a slow Account Service can tie up a Gateway request thread — a slow
  dependency can't cascade into Gateway-wide thread exhaustion.
- **Circuit breaker** stops hammering a service that's already failing: after the failure rate
  crosses the threshold the breaker **opens** and calls short-circuit immediately to a fallback,
  giving the downstream time to recover (it transitions to **half-open** and probes before closing).

This pairing maps directly onto the graceful-degradation requirement: the fallback raises
`AccountServiceUnavailableException`, which the Gateway translates into a fast, clear **`503`**
instead of a hang or a `500`. The breaker's state is exported as a metric and surfaced in
`/actuator/health`. Chosen over a plain retry because the failure mode we care about (a downstream
outage) is exactly where retries make things *worse* — a breaker fails fast and self-heals.

> 4xx responses from the Account Service are configured as `ignore-exceptions`, so a data/contract
> problem never trips the breaker (only genuine outages/timeouts do).

### Idempotency that tolerates partial failure

`eventId` is the idempotency key. The Gateway only short-circuits a duplicate once it is **`APPLIED`**.
An event that was stored but failed downstream (`RECEIVED`/`FAILED`) is **re-attempted** on a repeat
submission — safe because the Account Service is itself idempotent on `eventId`. This means a
transient outage doesn't permanently strand an event.

### Persistence ordering for degradation

The Gateway commits the event record *before* calling the Account Service and updates its status
afterward (each as its own transaction). So even when the downstream call fails, the local record
survives — which is what keeps the read endpoints available during an outage.

---

## Assumptions

- **Duplicate `eventId` → `200 OK`** with the original event (idempotent success). `409 Conflict`
  would be a reasonable alternative; `200` was chosen to signal "your request is already satisfied."
- **The Gateway exposes a proxied balance read.** The Account Service is internal-only, so clients
  read balances through the Gateway. This is also what makes the "balance queries return a clear
  error during an outage" requirement meaningful.
- **Same `eventId` is treated as the same event;** payloads aren't compared for conflicts. A
  production system might reject a mismatched replay — called out as future work.
- **Single currency per account** is assumed; amounts are summed without FX conversion.

---

## Tech Stack

- Java 21, Spring Boot 3.3 (Web, Data JPA, Validation, Actuator)
- H2 in-memory database (one per service)
- Resilience4j (circuit breaker + timeouts)
- Micrometer Tracing + OpenTelemetry (OTLP export), Micrometer + Prometheus (metrics)
- Logback + Logstash encoder (JSON logs)
- springdoc-openapi (API docs)
- JUnit 5, WireMock (downstream failure/propagation simulation)
- Docker Compose, OpenTelemetry Collector, Jaeger, Prometheus

---

## Running the System

### Prerequisites

- **Docker Compose path:** Docker Desktop (or Docker Engine + Compose v2).
- **Manual path:** JDK 21 and Maven 3.9+. *(If your default `mvn` runs on a newer JDK, point it at
  21: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` on macOS.)*

### Option A — Docker Compose (recommended)

```bash
docker compose up --build
```

Starts: `event-gateway` (8080), `account-service` (8081), `otel-collector`, `jaeger` (16686),
`prometheus` (9090). The Gateway waits for the Account Service to be healthy before starting.

```bash
docker compose down
```

### Option B — Run locally with Maven

```bash
# Terminal 1 — Account Service first (it's the dependency)
mvn -pl account-service spring-boot:run

# Terminal 2 — Event Gateway
mvn -pl event-gateway spring-boot:run
```

Both default to `localhost`; the Gateway expects the Account Service at `http://localhost:8081`.
Without the observability stack running you may see (silenced) OTLP export warnings — trace IDs are
still generated and propagated.

---

## Running the Tests

```bash
mvn test
```

16 tests across both modules:

- **Account Service** — idempotency, out-of-order balance, CREDIT−DEBIT fold, validation, health.
- **Event Gateway** (Account Service stubbed with WireMock) — full apply flow, idempotent duplicate
  (single downstream call), validation, chronological listing; **circuit breaker opens** after
  repeated failures and short-circuits; **503 graceful degradation** with local reads still working;
  **W3C `traceparent` propagation** to the Account Service.

---

## Observability Endpoints

| What | URL |
|---|---|
| Jaeger UI (trace waterfall across both services) | http://localhost:16686 |
| Prometheus | http://localhost:9090 |
| Gateway metrics | http://localhost:8080/actuator/prometheus |
| Account metrics | http://localhost:8081/actuator/prometheus |
| Gateway API docs | http://localhost:8080/swagger-ui.html |
| Account API docs | http://localhost:8081/swagger-ui.html |

In Jaeger, pick service `event-gateway` and open a `POST /events` trace to see the
Gateway → Account Service span waterfall sharing one trace ID.

---

## Try It (curl)

```bash
# 1. New event -> 201, applied
curl -i -X POST http://localhost:8080/events -H 'Content-Type: application/json' -d '{
  "eventId":"evt-001","accountId":"acct-123","type":"CREDIT","amount":150.00,
  "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}'

# 2. Same eventId again -> 200, balance unchanged (idempotent)
curl -i -X POST http://localhost:8080/events -H 'Content-Type: application/json' -d '{
  "eventId":"evt-001","accountId":"acct-123","type":"CREDIT","amount":150.00,
  "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}'

# 3. Out-of-order: a DEBIT with an EARLIER timestamp arrives later
curl -s -X POST http://localhost:8080/events -H 'Content-Type: application/json' -d '{
  "eventId":"evt-002","accountId":"acct-123","type":"DEBIT","amount":50.00,
  "currency":"USD","eventTimestamp":"2026-05-15T13:00:00Z"}'

# 4. Listing is chronological; balance is correct (150 - 50 = 100)
curl -s "http://localhost:8080/events?account=acct-123"
curl -s "http://localhost:8080/accounts/acct-123/balance"

# 5. Graceful degradation: stop the Account Service, then:
docker compose stop account-service
curl -i -X POST http://localhost:8080/events -H 'Content-Type: application/json' -d '{
  "eventId":"evt-003","accountId":"acct-123","type":"CREDIT","amount":5,
  "currency":"USD","eventTimestamp":"2026-05-15T15:00:00Z"}'    # -> 503
curl -s "http://localhost:8080/events?account=acct-123"          # -> still works (local read)
```

---

## Bonus Features & Future Work

**Included:** OpenTelemetry Collector + **Jaeger** trace visualization, **Prometheus** metrics
endpoint, OpenAPI/Swagger docs, retry-aware idempotency.

**Designed but deferred (future work):**

- **Async fallback queue** — when the Account Service is down, the Gateway already persists events
  as `RECEIVED`/`FAILED`; a background worker could replay them on recovery (the Account Service's
  idempotency makes replay safe). Deferred to keep the submission within its time budget and avoid
  the replay-ordering/test-surface risk it adds.
- **Conflicting-replay detection** — reject a repeat `eventId` whose payload differs from the original.
- **Rate limiting** and **contract tests** (e.g. Pact) between the two services.

---

## Project Structure

```
event-ledger/
├── pom.xml                     # Maven reactor (parent)
├── docker-compose.yml          # both services + otel-collector + jaeger + prometheus
├── otel-collector-config.yaml
├── prometheus.yml
├── account-service/            # internal service (balances, transactions)
└── event-gateway/              # public service (validation, idempotency, resilient calls)
```
