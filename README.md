# Event Ledger

*Two services, one ledger, and a stubborn refusal to get your balance wrong — even when the same
event shows up twice, events arrive in the wrong order, or the service on the other end falls over
mid-write.*

Upstream systems are messy. They deliver the **same event twice**. They send Tuesday's event
**after** Wednesday's. And every so often the thing you depend on is just **down**. This system
takes that chaos and still produces a correct, traceable, observable ledger.

```
   Client ──▶  Event Gateway  ──[ circuit breaker + traceparent ]──▶  Account Service
                 :8080 (public)                                          :8081 (internal)
                 own H2: events                                          own H2: transactions
```

- **Event Gateway** — the bouncer. Validates input, de-duplicates, records every event, and calls
  the ledger through a circuit breaker. The only door to the outside world.
- **Account Service** — the ledger. Owns balances and history; never exposed to clients.

---

## The interesting bits

The decisions I'd actually enjoy talking through in the walkthrough:

🧮 **Balance is a fold, not a running total.** `Σ credits − Σ debits`. Out-of-order? Doesn't matter.
Replays? Harmless. The two hardest requirements simply *dissolve* into the data model instead of
needing special-case code. → [how it works](docs/ARCHITECTURE.md#42-out-of-order-tolerance--balance)

🪪 **Idempotency that survives a race.** "`findById` then `save`" is a lie under concurrency — two
copies of the same event both pass the check. This one inserts and *catches the primary-key
collision*. Sixteen threads, one event, exactly one apply. → [how it works](docs/ARCHITECTURE.md#41-idempotency)

🔌 **When the ledger dies, the gateway lives.** Writes fail fast with a clean `503`; reads keep
serving from the gateway's own store. The circuit breaker trips, waits, then heals itself — no human
required. → [how it works](docs/ARCHITECTURE.md#44-resiliency-circuit-breaker--timeout)

🧵 **One request, one trace, two services.** A single client call becomes one clickable waterfall in
Jaeger, stitched together by a `traceparent` header. → [see it](docs/TOOLS.md#2-jaeger--distributed-tracing)

---

## Run it

```bash
docker compose up --build
```

Brings up both services plus the full observability stack — Gateway `:8080`, Jaeger UI `:16686`,
Prometheus `:9090`. (No Docker? `mvn -pl account-service spring-boot:run`, then the gateway.)

```bash
mvn test
```

**24 tests** — concurrency, resiliency, trace propagation, and the full Gateway → Account flow.
*(Needs JDK 21. If your `mvn` runs a newer JDK: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.)*

---

## Take it for a spin

The fastest way to *get* this system is to **try to break it** — submit the same event twice, fire
one out of order, then kill the ledger mid-write and watch the gateway stay composed. Every step,
with copy-paste `curl`s and exactly what to expect, lives in the
**[Testing Walkthrough »](docs/TESTING.md)**

---

## Go deeper

| If you want… | Open |
|---|---|
| 📐 The design — diagrams for the request flow, state machines, data model, deployment | **[Architecture & Design](docs/ARCHITECTURE.md)** |
| 🔒 The threat model — what's safe, what I'd harden for production, and why | **[Security Audit](docs/SECURITY.md)** |
| 🧪 To break it yourself, one curl at a time | **[Testing Walkthrough](docs/TESTING.md)** |
| 🛠️ Jaeger, Prometheus, Swagger, IntelliJ — how to drive each | **[Tools & Observability](docs/TOOLS.md)** |

---

## For the reviewer: requirements & API

<details>
<summary><b>✅ Requirement-by-requirement coverage</b> (click to expand)</summary>

<br>

| Requirement | Where it lives |
|---|---|
| **Idempotency** | `eventId` is the primary key in both stores, with an insert-and-catch that stays correct under **concurrent** duplicates. Returns the original on a repeat; balance never moves twice. |
| **Out-of-order** | Listings sort by `eventTimestamp`; balance is an order-independent fold. |
| **Balance** | `Σ CREDIT − Σ DEBIT` via a database `SUM` aggregate. |
| **Validation** | Bean Validation (`@NotBlank`/`@Size` ids, `@Positive` amount, enum `type`, ISO-4217 `currency`) → `400` with field messages. |
| **Service separation** | Two independent Spring Boot apps, each with its own H2; no shared code or state. |
| **Distributed tracing** | Micrometer + OpenTelemetry; W3C `traceparent` propagated Gateway → Account; trace IDs in both services' logs. |
| **Structured logging** | JSON (Logback + Logstash) with `timestamp`, `level`, `service`, `traceId`, `spanId`. |
| **Health checks** | `GET /health` on both, with a live DB-connectivity check. |
| **Custom metrics** | `gateway_events_total`, `ledger_transactions_applied_total`, + circuit-breaker state, on `/actuator/prometheus`. |
| **Resiliency** | Resilience4j **circuit breaker + timeout** on the Gateway → Account call. |
| **Graceful degradation** | Account down → `POST /events` returns `503`; reads keep working; balance returns a clear `503`. |
| **Docker Compose** | `docker compose up` starts both services + observability. |
| **Tests** | 24 across core, concurrency, resiliency, tracing, integration. |

Design trade-offs and assumptions (duplicate → `200`, one currency per account, etc.) are in the
**[Architecture doc](docs/ARCHITECTURE.md#7-trade-offs--alternatives)**.

</details>

<details>
<summary><b>📋 API at a glance</b> (click to expand)</summary>

<br>

**Event Gateway** (public, `:8080`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/events` | Submit a transaction event |
| `GET` | `/events/{id}` | Retrieve a single event (Gateway-local) |
| `GET` | `/events?account={id}` | List an account's events, ordered by `eventTimestamp` |
| `GET` | `/accounts/{id}/balance` | Balance, proxied to the Account Service through the breaker |
| `GET` | `/health` | Health + DB connectivity |

**Account Service** (internal, `:8081`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/accounts/{id}/transactions` | Apply a transaction (idempotent on `eventId`) |
| `GET` | `/accounts/{id}/balance` | Current balance + credit/debit counts |
| `GET` | `/accounts/{id}` | Account details + recent transactions |
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
  "metadata": { "source": "mainframe-batch" }
}
```

Status codes: `201` applied · `200` duplicate · `400` invalid · `422` business rule (e.g. currency
mismatch) · `503` Account Service unavailable. Full contract also published as OpenAPI at
`/swagger-ui.html` on each service.

</details>

---

**Stack:** Java 21 · Spring Boot 3.3 · H2 · Resilience4j · Micrometer + OpenTelemetry · Logback JSON ·
JUnit 5 + WireMock · Docker Compose + Jaeger + Prometheus.
