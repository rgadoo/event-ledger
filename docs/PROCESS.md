# Process & Decisions

A short summary of how I approached this exercise: how I planned it, the decisions I made and why,
how I used AI tools, and where my time went. The aim is to show the thinking behind the work, not
just the finished code.

## Approach

I treated this as a design problem first and a coding problem second.

1. **I read the brief carefully and made the key choices before writing any code** — which language
   to use, how to handle failures, and how far to take the optional extras. Some of these were real
   decisions, so I thought them through instead of just picking the obvious option.
2. **I planned my approach within the structure the brief required.** The brief specified two separate
   services, each with its own database — that part was a requirement, not my choice. What I decided was
   *how* they talk to each other, how the data is modeled so the balance stays correct, and how the
   system behaves when a service is down. Planning that first meant the coding was straightforward.
3. **I built it in small steps and saved each step**, so the project history shows how it actually
   came together (something the brief asked for).

## The decisions I made (and why)

- **Language — Java / Spring Boot.** It fits the kind of environment this role works in, and has
  strong built-in support for everything the exercise asks for.
- **Handling failure — a "circuit breaker" instead of retrying.** If the other service is down,
  retrying just piles on more pressure. A circuit breaker stops calling it for a short while, then
  checks whether it has recovered — and heals on its own.
- **Working out the balance by adding up history, rather than keeping one running number.** This
  sounds small, but it is the most important choice: it means the balance is automatically correct
  even when events arrive in the wrong order or arrive twice.
- **Preventing duplicates safely.** Every event has a unique id. I made sure the system handles two
  copies arriving at the exact same moment — not just the easy case.
- **Knowing when to stop.** I added the two optional features that give the most value for the least
  risk, and deliberately left out one that would have taken a lot of time for little gain.

## How it came together

I built a working version first, then made it stronger.

1. **Build** — the two services, the tests, the Docker setup, and a first README, one step at a time.
2. **Check it by hand** — I ran the whole thing and tried every scenario myself (duplicates,
   out-of-order events, a service going down) before trusting it.
3. **Review my own work like a senior engineer** — once it worked, I went looking for weaknesses on
   purpose. I found three real ones — around simultaneous duplicates, mixing currencies, and how the
   balance was calculated at large scale — and fixed each, adding a test that proves the fix.
4. **Security check** — I reviewed it for common risks and tightened what I could, then wrote up
   what is safe and what I would improve for a real production system.
5. **Re-test everything** — I ran the full automated tests again, plus a complete live run in Docker,
   to confirm nothing broke.
6. **Documentation** — a clear README as the starting point, with deeper documents for the design,
   security, testing, and tools.

## How I used AI

I used an AI coding assistant (Claude Code) as a partner, but I stayed in charge of the decisions.

- **I made the calls** — what to build, how to design it, how to handle failure, what to include, and
  when to do the deeper reviews.
- **The AI did the heavy lifting on typing** — setting things up, writing routine code and tests, and
  drafting documentation — which I checked and adjusted at every step.
- **I drove the important parts** — I pushed for the deeper review that found the real problems, set
  the tone for the documentation, and ran the final testing. The AI was fast; I decided what was worth
  doing and whether the result was right.

The benefit: because the AI handled most of the routine work, I could spend more of my time on the
things that actually matter — making sure it is correct, secure, and well documented.

## Where the time went

Comfortably within the time budget, with most of the effort going to the parts that matter:

| Phase | What I focused on |
|---|---|
| Planning | making the key decisions and deciding the design |
| Building | both services, tests, Docker, first README |
| Strengthening | fixing the three issues I found, each with a test |
| Security | reviewing risks and tightening them |
| Re-testing | full automated tests plus a live Docker run |
| Documentation | the README and the supporting documents |

Because the AI handled most of the routine coding, I could spend the saved time on the review, the
security work, and clear documentation — instead of on routine setup.

## What I would do next

With more time I would add sign-in and access control, automatic retry of failed events once a
service recovers, limits on how fast requests can arrive, and extra tests between the two services.
I have written these up in the design and security documents rather than half-building them here.
