# Testing Walkthrough (Demo Guide)

A step-by-step manual test of the Event Ledger, in the order to **demo it live**.
Each step has: the **command**, what to **expect**, and **why it matters**.

- **Gateway** (public) → `http://localhost:8080`
- **Account Service** (internal) → `http://localhost:8081`

> 💡 **Tip:** the databases are in-memory. **Restarting a service wipes its data.**
> For a clean demo with predictable numbers, restart both services first.

---

## Table of Contents

1. [Start / Stop the system](#1-start--stop-the-system)
2. [Health check](#2-health-check)
3. [Add money (CREDIT)](#3-add-money-credit)
4. [Check balance](#4-check-balance)
5. [Take money out (DEBIT)](#5-take-money-out-debit)
6. [Idempotency — no double charge](#6-idempotency--no-double-charge)
7. [Out-of-order events](#7-out-of-order-events)
8. [Invalid input & rejected transactions](#8-invalid-input--rejected-transactions)
9. [Graceful degradation — a service goes down](#9-graceful-degradation--a-service-goes-down)
10. [Circuit breaker — the "fuse"](#10-circuit-breaker--the-fuse)
11. [One-glance cheat sheet](#11-one-glance-cheat-sheet)

---

## 1. Start / Stop the system

**Build + start both services** (run from the project root):

```bash
cd ~/Developer/prototype/Schwab/event-ledger
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -DskipTests package
java -jar account-service/target/account-service-1.0.0.jar > /tmp/acct.log 2>&1 &
java -jar event-gateway/target/event-gateway-1.0.0.jar   > /tmp/gw.log   2>&1 &
```

> Start **account-service first** — the Gateway depends on it.

**Stop both services:**

```bash
pkill -f account-service-1.0.0.jar
pkill -f event-gateway-1.0.0.jar
```

**Force-stop whatever is on a port** (if a stray copy is running):

```bash
lsof -ti :8081 | xargs kill   # account-service
lsof -ti :8080 | xargs kill   # gateway
```

---

## 2. Health check

**What it tests:** is each service alive, and is its database reachable?

```bash
curl -s http://localhost:8081/health
curl -s http://localhost:8080/health
```

**Expect:**

```json
{"status":"UP","service":"account-service","timestamp":"...","checks":{"database":"UP"}}
```

**Why it matters:** machines (Docker, load balancers) poll this to know if a service
is healthy. The `database` check is a *real* connection test, not a hard-coded "OK".

---

## 3. Add money (CREDIT)

**What it tests:** the core happy path — submit an event, money lands in an account.

```bash
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 150.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T14:02:11Z"
  }'
```

**Expect:** `"status": "APPLIED"` → the money went in.

| Field | Meaning |
|---|---|
| `type: CREDIT` | money **in** (balance goes up) |
| `status: APPLIED` | successfully applied to the account |
| `appliedAt` | when the money landed |

---

## 4. Check balance

```bash
curl -s http://localhost:8080/accounts/acct-123/balance
```

**Expect:** `"balance": 150`

| Field | Meaning |
|---|---|
| `balance` | money in the account now |
| `creditCount` | number of money-**in** events |
| `debitCount` | number of money-**out** events |

---

## 5. Take money out (DEBIT)

**What it tests:** a DEBIT lowers the balance.

```bash
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-002",
    "accountId": "acct-123",
    "type": "DEBIT",
    "amount": 40.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T15:00:00Z"
  }'
```

Then check the balance again:

```bash
curl -s http://localhost:8080/accounts/acct-123/balance
```

**Expect:** balance = **150 − 40 = 110**.

**The core math:** `balance = (all CREDITs) − (all DEBITs)`.

---

## 6. Idempotency — no double charge

**What it tests:** the **same event sent twice** must NOT charge twice.

Send the **exact same** `evt-002` again (same `eventId`):

```bash
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-002",
    "accountId": "acct-123",
    "type": "DEBIT",
    "amount": 40.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T15:00:00Z"
  }'
```

Check the balance:

```bash
curl -s http://localhost:8080/accounts/acct-123/balance
```

**Expect:** balance **STAYS 110** (not 70). `transactionCount` stays the same.

**Why no error?** A duplicate is treated as **success**, not failure — the job
("take 40 out") is already done, so we return the original event with `200 OK`.

> 🔑 **The fingerprint is `eventId`.** Same `eventId` = same event = do it only once.
> **Demo clue:** the duplicate reply shows the **original** `receivedAt` time, not "now".

> ⚙️ **Under concurrency too.** Two identical events arriving at the *same instant* still
> apply exactly once (insert-and-catch on the primary key). That can't be shown with a single
> `curl`, so it's proven by the automated `AccountServiceConcurrencyTest` /
> `GatewayConcurrencyTest` (16 threads → one apply, one row).

---

## 7. Out-of-order events

**What it tests:** an event that **happened earlier** but **arrives later** still
lands in the right place. Uses a fresh account `acct-777`.

**Send #1** — happened at **18:00** (send first):

```bash
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "ooo-late",
    "accountId": "acct-777",
    "type": "CREDIT",
    "amount": 100.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T18:00:00Z"
  }'
```

**Send #2** — happened at **09:00** (arrives second, the "late" one):

```bash
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "ooo-early",
    "accountId": "acct-777",
    "type": "CREDIT",
    "amount": 50.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T09:00:00Z"
  }'
```

**List the events:**

```bash
curl -s "http://localhost:8080/events?account=acct-777"
```

**Expect:** `ooo-early` (09:00) appears **first**, even though it was sent **second**.

**Why it matters:** the list is sorted by **when it happened** (`eventTimestamp`),
not by arrival order. Balance is also correct regardless of order.

---

## 8. Invalid input & rejected transactions

**What it tests:** broken or disallowed events are blocked with a clear error.
The `-i` flag shows the **status code**.

**Zero amount (must be > 0):**

```bash
curl -i -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "bad-1",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 0,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T10:00:00Z"
  }'
```

**Expect:** `HTTP/1.1 400` and `"amount":"must be greater than 0"`.

**Unknown type (only CREDIT / DEBIT allowed):**

```bash
curl -i -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "bad-2",
    "accountId": "acct-123",
    "type": "TRANSFER",
    "amount": 5,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T10:00:00Z"
  }'
```

**Expect:** `HTTP/1.1 400`. The bad event is **not** saved.

**Malformed currency (must be a 3-letter ISO-4217 code):**

```bash
curl -i -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "bad-3",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 5,
    "currency": "usdollar",
    "eventTimestamp": "2026-05-15T10:00:00Z"
  }'
```

**Expect:** `HTTP/1.1 400` — `"currency":"must be a 3-letter ISO-4217 code"`.

**Currency mismatch — one currency per account (422):**

An account's currency is set by its **first** transaction. A later transaction in a
different currency is rejected with **422** rather than being summed into a meaningless
mixed-currency balance.

```bash
# 1) establish acct-eur in EUR
curl -s -o /dev/null -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{"eventId":"eur-1","accountId":"acct-eur","type":"CREDIT","amount":100,"currency":"EUR","eventTimestamp":"2026-05-15T10:00:00Z"}'

# 2) now try a USD transaction on the same account
curl -i -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{"eventId":"eur-2","accountId":"acct-eur","type":"DEBIT","amount":10,"currency":"USD","eventTimestamp":"2026-05-15T11:00:00Z"}'
```

**Expect:** the second call returns `HTTP/1.1 422` (Unprocessable Entity) with a clear
currency-mismatch message — a financial-correctness guard.

---

## 9. Graceful degradation — a service goes down

**What it tests:** when the Account Service is **down**, the Gateway stays calm —
no crash, no hang. Writes fail cleanly; local reads still work.

**Step A — turn OFF the Account Service:**

```bash
lsof -ti :8081 | xargs kill
curl -s http://localhost:8081/health     # expect: nothing back (it's down)
```

**Step B — try to add money (use a FRESH id):**

```bash
curl -i -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-new",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 25.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T16:00:00Z"
  }'
```

**Expect:** `HTTP/1.1 503` + `"status": "FAILED"` + a `failureReason`.
The Gateway gives a clear "can't do this now" — **not** a 500 or a freeze.

> Use a **fresh** `eventId`. A repeat of an already-applied id returns `200` from the
> Gateway's own store **without** calling the Account Service.

**Step C — reads still work (Gateway's own data):**

```bash
curl -s "http://localhost:8080/events?account=acct-123"
```

**Expect:** the list **still comes back** (you'll see `evt-new` as `FAILED`).

**Step D — balance needs the Account Service → clear 503:**

```bash
curl -i -s http://localhost:8080/accounts/acct-123/balance
```

**Expect:** `HTTP/1.1 503` with a clear message.

| Action | Lives where | While Account down |
|---|---|---|
| List events | Gateway | ✅ works |
| Get one event | Gateway | ✅ works |
| Add event | needs Account | ❌ 503 (saved as FAILED) |
| Get balance | needs Account | ❌ 503 (clear error) |

---

## 10. Circuit breaker — the "fuse"

**What it tests:** if calls keep failing, the Gateway **trips a fuse** and stops
trying for a while — then tests and recovers on its own.

The fuse has **3** positions:

| State | Meaning |
|---|---|
| 🟢 `closed` | normal — calls go through |
| 🔴 `open` | tripped — calls blocked, fail fast |
| 🟡 `half_open` | testing — let a few through to check recovery |

**Step A — (Account Service still down) fire several failing calls to trip it:**

```bash
for i in 1 2 3 4 5 6; do
  curl -s -o /dev/null -w "try $i  ->  HTTP %{http_code}\n" \
    -X POST http://localhost:8080/events \
    -H "Content-Type: application/json" \
    -d "{\"eventId\":\"cb-$i\",\"accountId\":\"acct-123\",\"type\":\"CREDIT\",\"amount\":1,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T16:00:00Z\"}"
done
```

**Expect:** six `503` lines.

**Step B — check the fuse state** (the line ending in `1.0` is the current state):

```bash
curl -s http://localhost:8080/actuator/prometheus | grep 'circuitbreaker_state{' | grep accountService
```

**Expect:** `state="open" ... 1.0` (or `half_open` if ~10s have passed).

**Step C — bring the Account Service back UP:**

```bash
java -jar account-service/target/account-service-1.0.0.jar > /tmp/acct.log 2>&1 &
curl -s http://localhost:8081/health      # wait for "UP"
```

**Step D — send good calls so the fuse closes** (needs ~3 successes in a row):

```bash
for i in 7 8 9; do
  curl -s -o /dev/null -w "good call $i  ->  HTTP %{http_code}\n" \
    -X POST http://localhost:8080/events \
    -H "Content-Type: application/json" \
    -d "{\"eventId\":\"cb-ok-$i\",\"accountId\":\"acct-123\",\"type\":\"CREDIT\",\"amount\":1,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T17:00:00Z\"}"
done
```

**Step E — confirm the fuse is back to normal:**

```bash
curl -s http://localhost:8080/actuator/prometheus | grep 'circuitbreaker_state{' | grep accountService | grep '1.0'
```

**Expect:** `state="closed" ... 1.0` 🟢

**The full story:** 🟢 closed → service dies → 🔴 open → wait → 🟡 half-open →
good calls → 🟢 closed. **It broke, protected itself, and healed — no human needed.**

---

## 11. One-glance cheat sheet

| # | Behavior | Command (short) | Expect |
|---|---|---|---|
| 2 | Health | `GET /health` | `UP` |
| 3 | Add money | `POST /events` CREDIT 150 | `201 APPLIED` |
| 4 | Balance | `GET /accounts/acct-123/balance` | `150` |
| 5 | Take out | `POST /events` DEBIT 40 | balance `110` |
| 6 | Idempotency | re-POST same `eventId` | balance **unchanged**, `200` |
| 7 | Out-of-order | POST late then early; list | early shows **first** |
| 8 | Bad input | `POST` amount `0` / bad currency | `400` |
| 8 | Currency mismatch | `POST` USD on a EUR account | `422` |
| 9 | Degradation | stop Account; POST fresh | `503 FAILED`, reads still work |
| 10 | Circuit breaker | loop fails → trip → recover | `open` → `half_open` → `closed` |

---

### Covered behaviors

- ✅ Health
- ✅ Credit / Debit / Balance
- ✅ Idempotency (no double charge) — including under concurrency (automated test)
- ✅ Out-of-order events
- ✅ Bad input rejected (incl. currency format)
- ✅ Currency consistency per account (422)
- ✅ Graceful degradation + circuit breaker
