# Phase 3: TMDB Integration & Catalog Caching - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-03
**Phase:** 3-TMDB Integration & Catalog Caching
**Areas discussed:** Region source & deck ranking, TMDB outage behavior, Deck size & sparse filters, Cache scope & freshness

---

## Region source & deck ranking

| Option | Description | Selected |
|--------|-------------|----------|
| Hardcode default region (e.g. US) | Simplest, no new field | |
| Host picks region at session creation | Adds a region field to Session | ✓ |
| Per-participant region | More correct, but splits the shared deck | |

**User's choice:** Host picks region at session creation.
**Notes:** Optional at creation, defaults to a fixed region — user corrected the example default from US to **Germany (DE)**.

| Option | Description | Selected |
|--------|-------------|----------|
| No — fixed at creation | Simplest | |
| Yes — host can update it later | New update capability | ✓ |

**User's choice:** Yes, region can be updated after creation.

| Option | Description | Selected |
|--------|-------------|----------|
| Any participant can edit it | Consistent with Phase 2's no-host-role decision | ✓ |
| Only the session creator can edit it | Introduces a new creator/permission concept | |

**User's choice:** Any participant can edit it (kept consistent with Phase 2 D-03: no host/creator role exists).

**Follow-up question raised by user:** "How do you determine which movies might be of interest for the group of people" if not relying on streaming-provider filtering — user initially flagged this as possibly "a major feature."

| Option | Description | Selected |
|--------|-------------|----------|
| Yes — unfiltered = TMDB's general discover/popular results | Genre-only filtering, no provider/region dependency | ✓ |
| No — streaming filter is effectively always on with a default provider set | Hidden default provider scope | |

**User's choice:** Unfiltered deck = TMDB discover/popular results, genre-filtered only if set.

| Option | Description | Selected |
|--------|-------------|----------|
| Use TMDB's popularity/rating sort for v1 | No personalization logic; matches PROJECT.md's ML out-of-scope call | ✓ |
| Let's think this through more — not ready to lock it | Would reopen PROJECT.md scope | |

**User's choice:** Use TMDB's popularity/rating sort for v1 — confirmed this does not reopen the ML/personalization out-of-scope decision in PROJECT.md.

---

## TMDB outage behavior

| Option | Description | Selected |
|--------|-------------|----------|
| Serve stale cached data if any exists, else a clear error | Best UX during a TMDB blip | ✓ |
| Always return a clear error, never serve stale data | Simpler, more predictable, but blocks sessions during any outage | |

**User's choice:** Serve stale cached data if it exists; error only if nothing is cached yet.

| Option | Description | Selected |
|--------|-------------|----------|
| Yes — a few retries with backoff, then fall back to stale/error | Matches STACK.md's WebClient + retryWhen recommendation | ✓ |
| No — fail immediately to stale/error on first 429 | Simpler, no retry logic | |

**User's choice:** Retry with backoff on 429 before falling back.

---

## Deck size & sparse filters

| Option | Description | Selected |
|--------|-------------|----------|
| One fixed page from TMDB (~20 movies) | Simplest, no pagination logic | ✓ |
| Fetch/cache multiple pages (e.g. top 60-100) | Bigger deck, more TMDB calls to build cache | |

**User's choice:** One fixed page (~20 movies) per filter combination.

| Option | Description | Selected |
|--------|-------------|----------|
| Return whatever matches, even if a handful (or zero) | No special-casing, honest reflection of narrow filters | |
| Return an explicit "not enough movies, try broader filters" response | Backend enforces a minimum, nudges toward broader filters | ✓ |

**User's choice:** Explicit "not enough movies" response below a threshold.

| Option | Description | Selected |
|--------|-------------|----------|
| Fewer than 5 movies | Deck under 5 isn't enough for a real session | ✓ |
| Fewer than 10 movies | More headroom | |
| You decide | Leave to implementation | |

**User's choice:** Fewer than 5 movies triggers the "not enough movies" response.

---

## Cache scope & freshness

| Option | Description | Selected |
|--------|-------------|----------|
| Keyed by filter combo (genre, provider, region), shared across sessions | Fewer TMDB calls, matches ARCHITECTURE.md recommendation | ✓ |
| Keyed per-session | Simpler mental model, more redundant calls | |

**User's choice:** Cache keyed by filter combo, shared across sessions.

| Option | Description | Selected |
|--------|-------------|----------|
| A few hours (e.g. 6h) | Conservative, matches PITFALLS.md "effectively static for weeks" framing | ✓ |
| A full day (24h) | Fewer TMDB calls | |
| You decide | Treat as tunable config | |

**User's choice:** A few hours (~6h) freshness TTL for the deck cache.

---

## Claude's Discretion

- Exact deck cache TTL number as a config value (starting point ~6h)
- Genre/watch-provider reference-data cache TTL and refresh mechanism
- Exact Flyway migration shape for the new `Session.region` column
- HTTP client mechanics (`WebClient` vs `RestClient`) and exact retry/backoff parameters
- Cache table/entity shape (`CachedMovie`, genre, provider tables)
- Response shape (HTTP status, JSON envelope) for the "not enough movies" case

## Deferred Ideas

None — the "how do we pick interesting movies" tangent was resolved within this phase's scope (see Region source & deck ranking above) rather than deferred.
