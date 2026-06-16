# Security Audit

A threat-model-style review of the Event Ledger. Each finding has a **severity**, the
**status** (Fixed / Accepted for this exercise / Production recommendation), and the
reasoning. The brief does not require authentication, so several items are deliberately
deferred — they're documented here rather than hidden.

> Legend — **Status:** ✅ Fixed · 🟡 Accepted (deliberate, for the exercise/demo) · 🔭 Production recommendation

## Summary

| # | Finding | Severity | Status |
|---|---|---|---|
| 1 | No authentication / authorization (IDOR on `accountId`) | High (prod) | 🔭 |
| 2 | Unbounded input fields (id/currency/metadata) — storage/memory abuse | Medium | ✅ |
| 3 | Actuator endpoints exposed unauthenticated | Medium | 🟡 / 🔭 |
| 4 | Internal Account Service published to the host | Medium | 🟡 / 🔭 |
| 5 | No rate limiting | Medium | 🔭 |
| 6 | No TLS (plaintext client↔gateway, gateway↔account) | Medium (prod) | 🔭 |
| 7 | Containers ran as root | Low | ✅ |
| 8 | Financial data (amount) in logs | Low | 🟡 |
| 9 | Downstream `traceparent` trusted | Low | 🟡 |
| — | SQL injection | — | ✅ none (parameterized) |
| — | Deserialization gadget surface | — | ✅ none (no default typing) |

---

## Findings

### 1. No authentication / authorization — **High (production)** 🔭
The Gateway is fully open. Any caller can submit events for **any** `accountId` and read
**any** account's events/balance — a classic IDOR (insecure direct object reference) and a
trivial account-enumeration surface. For a financial system this is the single most important
production gap.

**Recommendation:** authenticate clients (OAuth2/JWT at the edge), and authorize each request
against the caller's entitlement to the `accountId`. Out of scope per the brief; called out
explicitly so it is not mistaken for an oversight.

### 2. Unbounded input fields — **Medium** ✅ Fixed
`eventId`, `accountId`, `currency` were only `@NotBlank`; a client could store arbitrarily
large strings (and an arbitrarily large `metadata` object), an easy storage/memory-abuse vector.

**Fix:** `@Size(max = 100)` on the id fields and a `@Pattern("[A-Z]{3}")` ISO-4217 constraint
on `currency`, on **both** the Gateway and Account Service request DTOs (defence in depth).
*Remaining:* the `metadata` object size is still only bounded by the request-body limit — a
production deployment should also set an explicit max request size at the edge/proxy.

### 3. Actuator endpoints exposed unauthenticated — **Medium** 🟡 / 🔭
`/actuator/health` (full details), `/actuator/prometheus`, and `/actuator/metrics` are open.
They leak internal state (DB status, disk, circuit-breaker state, metric names). Left open here
so the Prometheus/health demo works.

**Recommendation:** separate management port, require auth, `health.show-details: when-authorized`.
Noted inline in both `application.yml`.

### 4. Internal Account Service published to the host — **Medium** 🟡 / 🔭
`docker-compose.yml` publishes `8081:8081`, so the "internal" service is reachable directly,
bypassing the Gateway's validation/idempotency. Published only so it can be exercised in the demo.

**Recommendation:** drop the `ports:` block in production — the Gateway reaches it over the
compose network; nothing external can. Noted inline in `docker-compose.yml`.

### 5. No rate limiting — **Medium** 🔭
The Gateway will accept unbounded request volume (DoS surface). A bonus item, not implemented.
**Recommendation:** token-bucket limiter (Resilience4j `RateLimiter` or a gateway/proxy).

### 6. No TLS — **Medium (production)** 🔭
All traffic is plaintext HTTP. **Recommendation:** TLS at the client edge and mTLS between
services (the latter also gives service identity, complementing finding 1).

### 7. Containers ran as root — **Low** ✅ Fixed
Both images now create and run as a non-root `appuser` (a compromised process gets no in-container
root). The jar is read-only to the app; no writable paths are needed (in-memory DB).

### 8. Financial data in logs — **Low** 🟡 Accepted
`amount` and `accountId` are logged at INFO. Kept intentionally for debuggability (this codebase
optimizes for "a dev can trace a bug from the logs"). **Production note:** for PCI/GLBA-style
regimes, drop or mask `amount` and treat `accountId` as restricted.

### 9. Downstream `traceparent` trusted — **Low** 🟡 Accepted
The Gateway continues an incoming W3C `traceparent` if a client sends one (correct distributed-
tracing behavior). Worst case is trace-graph pollution, not a data risk. A hardened edge would
strip client-supplied trace headers.

---

## What's already safe (verified, not assumed)

- **SQL injection:** every query is a Spring Data derived method or a `@Query` with **named
  parameters** — no string concatenation anywhere.
- **Deserialization:** client JSON (`metadata`) is read into a plain `Map<String, Object>` with
  Jackson **default typing disabled** — no polymorphic gadget surface.
- **Error responses:** the catch-all returns a generic message and logs the stack trace
  **server-side only** — no stack traces or internal details leak to clients.
- **No H2 console** is enabled (a common accidental exposure).
- **CORS** is unconfigured → restrictive by default (no cross-origin browser access).
- **No hardcoded secrets:** the only credential is the embedded H2 `sa`/empty password for an
  in-memory DB that is never exposed. A real DB would use a secrets manager.

---

## Production hardening checklist

- [ ] AuthN/AuthZ at the Gateway, per-`accountId` authorization (finding 1)
- [ ] TLS at the edge + mTLS between services (finding 6)
- [ ] Rate limiting (finding 5)
- [ ] Secure/segregate actuator; `show-details: when-authorized` (finding 3)
- [ ] Don't publish the Account Service host port (finding 4)
- [ ] Explicit max request-body size at the edge (finding 2, remainder)
- [ ] Mask/drop financial data in logs (finding 8)
- [ ] Dependency/image scanning (e.g. `mvn dependency-check`, Trivy) in CI
