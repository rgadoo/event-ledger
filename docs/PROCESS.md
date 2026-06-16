# Process & Decisions

A short account of how I approached this exercise — the lifecycle, where I applied judgment, how I
used AI tooling, and where the time went. The goal is to make the *engineering decisions* visible,
not just the finished result.

## Approach

I treated this as a design exercise first and a coding exercise second:

1. **Read the brief and pinned down the real choices** before writing any code — language and
   framework, the resiliency pattern, and how far to take tracing and the optional bonuses. A few of
   these were genuine forks worth deciding deliberately rather than defaulting into.
2. **Locked the architecture up front** — two independently deployable services, each with its own
   database; balance modeled as a fold; idempotency keyed on `eventId`; a circuit breaker on the
   inter-service call. Writing that down first turned the implementation into execution rather than
   exploration.
3. **Built in small, reviewable steps**, committing each so the history reflects the actual working
   process (a stated requirement).

## Where I used judgment

The decisions I would defend in a review:

- **Stack — Java / Spring Boot.** Matches the target environment and has first-class support for what
  is being graded: Resilience4j, Micrometer with OpenTelemetry, and Actuator.
- **Resiliency — circuit breaker over retry.** The failure mode that matters is a downstream outage,
  where retries only add load to a struggling service. A breaker fails fast and self-heals.
- **Balance as a fold, not a running total.** This makes out-of-order tolerance and idempotency
  properties of the data model instead of special-case code — the most leveraged decision in the design.
- **Idempotency strategy.** Primary key for de-duplication, with insert-and-catch so it stays correct
  under concurrency rather than relying on a naive check-then-insert.
- **Scope discipline.** I chose the two bonuses with the best ratio of reviewer value to risk (Jaeger
  and Prometheus) and explicitly deferred the async fallback queue rather than half-building it.

## Lifecycle

Working software first, then depth:

1. **Build** — Account Service, then Gateway, then resiliency, tracing, observability, Docker Compose,
   tests, and the README — each as its own commit.
2. **Verify** — ran the full stack and exercised every behavior by hand (idempotency, out-of-order,
   degradation, and the trace in Jaeger) before trusting it.
3. **Audit & harden** — once it worked, I ran a deliberate principal-level review of my own code and
   found real gaps: idempotency that raced under concurrency, currency that could be summed
   inconsistently, and a balance that loaded every row. I fixed each with a test that fails without the fix.
4. **Security review** — a threat-model pass (input bounds, deserialization safety, non-root
   containers, actuator posture), documented with severities.
5. **Regression** — re-ran the suite and a full live Docker end-to-end pass (functional, resiliency,
   observability) before finalizing.
6. **Documentation** — the README as the front door, with deeper docs for architecture, security,
   testing, and tooling.

## How I used AI

I used Claude Code as a pair-programmer and kept the engineering judgment with me:

- **I owned the decisions** — requirement interpretation, the design, the resiliency choice, the scope,
  and the call to run a principal-level audit and a security pass.
- **The agent accelerated execution** — scaffolding, boilerplate, test authoring, and documentation
  drafts — which I reviewed and steered at each step.
- **I directed the hard parts** — I pushed the concurrency and financial-correctness audit that
  surfaced the real bugs, set the documentation tone, and drove the end-to-end regression. The AI moved
  fast; I decided *what* was worth doing and *whether* the output was correct.

The net effect: the tooling removed most of the typing, so a larger share of the time went to the
things that actually differentiate the work — correctness under concurrency, financial correctness,
security, and clear documentation.

## Where the time went

Comfortably within the budgeted window, with the effort weighted toward the parts that matter:

| Phase | Focus |
|---|---|
| Design & clarification | deciding the forks, locking the architecture |
| Core build | both services, tests, Docker, first README |
| Audit & hardening | concurrency, currency, balance scalability — each with a test |
| Security review | threat model and fixes |
| Regression | full suite plus a live Docker end-to-end pass |
| Documentation | README and the architecture / security / testing / tooling docs |

Because AI assistance compressed the implementation, the depth — the self-audit, the security review,
and the documentation — fit inside the same budget that would otherwise have gone to boilerplate.

## What I would do next

With more time: authentication and authorization, an async fallback queue to auto-replay failed events
on recovery, rate limiting, and contract tests between the services. These are catalogued in the
architecture and security docs rather than half-built here.
