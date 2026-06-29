# Implementation Notes — Custom Logic vs Libraries

A clear line between what this project **builds itself** and what it **delegates to libraries**.
The principle: libraries handle the generic plumbing; the project owns the business logic and the
design decisions.

---

## Built here (the project's own logic)

| Area | What's custom |
|---|---|
| **Idempotency** | Insert-and-catch design, `eventId` as the key, retry-aware status handling |
| **Balance** | Modeled as a fold (`Σ credits − Σ debits`) via a database `SUM` aggregate |
| **Event lifecycle** | `RECEIVED → APPLIED / FAILED` state transitions |
| **Graceful degradation** | Store-first, call the Account Service, update status, return `503` |
| **Currency consistency** | One-currency-per-account rule (rejected with `422`) |
| **Validation rules** | Which fields are required and their limits |
| **Resiliency fallback** | What happens when the breaker trips (clean `503`) |
| **Custom metrics** | `gateway_events_total`, `ledger_transactions_applied_total` |
| **Health logic** | Live database-connectivity check |
| **Tests, Docker setup, docs** | All project-authored |

---

## Delegated to libraries (configured, not reinvented)

| Concern | Library |
|---|---|
| Circuit breaker + timeout | Resilience4j |
| Web framework, REST, dependency wiring | Spring Boot |
| Database access | Spring Data JPA / Hibernate |
| Embedded database | H2 |
| Tracing (trace ids, `traceparent` propagation) | Micrometer Tracing + OpenTelemetry |
| Metrics collection / exposure | Micrometer + Prometheus |
| JSON log formatting | Logback + Logstash encoder |
| Validation engine | Bean Validation |
| API documentation | springdoc-openapi |
| Test tooling | JUnit 5 + WireMock |
| Trace / metrics viewers | Jaeger, Prometheus |

---

## Rationale

Reinventing a circuit breaker, a web server, or a database is how subtle bugs get introduced —
those are solved problems with battle-tested libraries. The engineering effort is therefore
concentrated where it adds value: the **domain logic** (idempotency, balance as a fold, the
degradation flow) and the **design decisions** (circuit breaker over retry, fold over a running
total). The judgment is in the decisions, not in re-implementing infrastructure.
