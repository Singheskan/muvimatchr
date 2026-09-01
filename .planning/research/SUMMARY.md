# Project Research Summary

**Project:** MuviMatchr
**Domain:** Group movie-matching / swipe-to-decide web app (session-based, real-time, no auth)
**Researched:** 2026-09-01
**Confidence:** MEDIUM-HIGH

## Executive Summary

MuviMatchr is a "Tinder for movies" group-decision tool: participants join a code-based session with no accounts, swipe on a TMDB-sourced movie deck, and get shown a mutual match. This is a well-trodden product category (Matched, MatchaFilm, Movie Matchup, FlickFix, Reelgood's Swipe With Friends) with a consistent set of table-stakes features — swipe deck, code/link join, genre + streaming filters, unanimous-match rule, and a result reveal — but a genuine, well-documented gap in how competitors handle async multi-user voting: none of them build a good live "waiting on N people" experience, and several actively market ephemerality instead of persistence. That gap is exactly where the prior MuviMatchr prototype also failed, which makes it both the differentiator and the highest-risk part of the rebuild.

The recommended approach is a clean separation between a Kotlin/Spring Boot 4.1 backend (upgrading Kotlin to >=2.2) with PostgreSQL as the sole source of truth, and a React 19 + Vite SPA frontend using `motion` for the swipe-card interaction and STOMP-over-WebSocket (via `@stomp/stompjs`) purely as a push-notification layer, never as authoritative state. The architecture research is unusually well-grounded here because it directly inspected the prior prototype's actual code and confirmed the exact bug pattern to avoid: in-memory maps that don't survive restart and an aggregation routine that overwrote (rather than merged) per-participant votes.

The dominant risk is repeating that same class of bug in new clothes — race conditions in "has everyone finished" checks, non-idempotent vote writes, stale participant denominators when people join late, and WebSocket messages silently dropped during disconnects. All of these are addressed by a single consistent principle across the pitfalls and architecture research: persist every vote as an upserted row keyed by (session, participant, movie), compute aggregation via live DB queries only (never accumulated in-memory state), and treat the WebSocket channel strictly as a "go re-check status" signal with REST as the always-correct fallback on load/reconnect. Secondary risks (TMDB key exposure, guessable session codes, unauthenticated identity spoofing) are well-understood and cheap to prevent if designed in from the start rather than retrofitted.

## Key Findings

### Recommended Stack

Kotlin 2.3.20 + Spring Boot 4.1.x (Java 21, already in place) on the backend, paired with PostgreSQL 17/18 for durable persistence and Flyway for schema migrations — SQLite is explicitly rejected because free/cheap hosting tiers (Render, Railway) have ephemeral container filesystems that would violate the "survives restart" requirement. Real-time updates use Spring's STOMP-over-WebSocket (`spring-boot-starter-websocket`), which maps directly onto "broadcast waiting-room state to a session's topic" without hand-rolling routing. The frontend is a React 19 + Vite + TypeScript SPA (not Next.js — no SSR need), using `motion` (the current `framer-motion`) for the swipe-card drag/gesture interaction (`react-tinder-card` is unmaintained and rejected), `@stomp/stompjs` for the WebSocket client, and TanStack Query for server-state management.

**Core technologies:**
- Kotlin 2.3.20 + Spring Boot 4.1.x — backend framework; upgrade from 1.9.25 is mandatory since Boot 4 requires Kotlin >=2.2
- PostgreSQL (managed, e.g. Render) + Spring Data JPA + Flyway — durable persistence with versioned migrations, `ddl-auto=validate` only
- React 19 + Vite + TypeScript — SPA frontend, deepest gesture/animation ecosystem for swipe UI
- `motion` (^13.x) — swipe-card drag/gesture and general animation
- Spring STOMP-over-WebSocket + `@stomp/stompjs` — real-time push notification layer only, never source of truth

### Expected Features

The competitive landscape is dominated by small indie apps with thin public documentation, but the pattern across 9+ apps surveyed is consistent enough to trust for table-stakes decisions. MuviMatchr's already-scoped v1 (swipe deck, code/link join, genre+streaming filters, unanimous match, async voting with live status) matches the category's expected baseline almost exactly, and research confirms two structural decisions as correct: store per-movie vote *counts* (not a boolean) from day one, and treat the live waiting-room experience as a genuine differentiator since no competitor does it well.

**Must have (table stakes):**
- Card-based swipe deck (right = like, left = pass)
- No-account session join via code/link + display name
- Genre and streaming-availability filters
- Match = liked by every participant in the session (unanimous, for the couple-first primary use case)
- Result reveal screen with poster + "where to watch"

**Should have (competitive differentiators):**
- Live "waiting on N people" status with auto-transition to results — the clearest gap in the market
- Session/vote persistence across restarts — treated as ephemeral-by-design by competitors, a genuine trust differentiator here
- Per-movie vote-count aggregation in the data model (even if v1 UI only surfaces the single best match) — structural prerequisite for v2 ranked lists

**Defer (v2+):**
- Runtime/year/rating filters (cheap, additive, v1.x)
- Explicit no-match fallback UX (highest-vote-count "runner-up") — v1.x, trigger on real usage hitting no-match
- Undo last swipe — v1.x
- Majority/threshold matching for groups >2, weighted votes, ML personalization, true simultaneous real-time swiping — all explicitly out of scope

### Architecture Approach

The architecture is a classic layered service design organized by feature package (`session/`, `voting/`, `catalog/`, `realtime/`), not by technical layer — the prior prototype's layer-based packaging (`controller/`, `service/`) obscured the very bug this rebuild must fix. The single governing rule: **the database is the only source of truth for votes and session state**; the WebSocket layer is a thin, stateless push-notification wrapper that never carries authoritative data and is called one-directionally by the service layer. Suggested build order is: persistence schema first (Session/Participant/Vote entities + upsert + aggregation query, tested in isolation including a restart test) -> session/lobby REST endpoints -> TMDB integration/caching (parallelizable) -> vote/swipe recording -> match-aggregation logic (most heavily tested, given it's the prior prototype's exact failure point) -> real-time notification layer last (purely additive, since REST alone should already be fully correct) -> frontend SPA.

**Major components:**
1. Session/Lobby Controller & Service — create/join sessions, participant roster, no-auth identity via server-issued token
2. Vote/Swipe Controller & Service — upsert-on-conflict vote writes keyed by (session, participant, movie)
3. Match-Aggregation Service — pure read-model computing "everyone finished?" and "best match" directly from persisted votes, never from in-memory counters
4. TMDB Integration/Caching Layer — isolates TMDB's JSON shape behind `MovieCatalogService`, caches genre/provider/discover results server-side
5. Realtime Notification Layer — thin STOMP broadcast wrapper triggered by service-layer domain events; clients always reconcile via REST GET on connect/reconnect

### Critical Pitfalls

1. **In-memory state as source of truth** — exactly what broke the prior prototype (lost on restart, inconsistent across instances). Avoid by persisting session/participant/vote data to Postgres from the first phase that touches any of them; never build "get it working with a map, persist later."
2. **Race condition in "has everyone finished" checks** — concurrent last-votes can double-trigger or never-trigger completion. Avoid with an atomic, transactional completion check (live COUNT query or optimistic locking) so exactly one writer wins the "trigger result computation" race.
3. **Non-idempotent vote submission causing double-counting** — retries/rejoins/double-taps duplicate votes without a DB constraint. Avoid with a unique constraint on `(session_id, participant_id, movie_id)` and an upsert (`INSERT ... ON CONFLICT DO UPDATE`), not a plain insert.
4. **Stale/fixed participant denominator** — snapshotting "total participants" at session start breaks late joins and abandoned participants. Avoid by computing "everyone finished" as a live query against currently-joined participants, with an explicit product decision on late joiners and never-finishers.
5. **WebSocket messages missed during disconnect leave clients permanently stale** — fire-and-forget push silently drops disconnected clients. Avoid by treating WebSocket purely as a "go re-check status" signal; every connect/reconnect does a REST status fetch as the source of truth.

## Implications for Roadmap

Based on research, suggested phase structure:

### Phase 1: Persistence Foundation (Session, Participant, Vote schema)
**Rationale:** Every other pitfall and correctness requirement depends on this existing first; it's also the exact layer where the prior prototype failed, so it must be proven correct in isolation before anything is built on top of it.
**Delivers:** JPA entities + repositories for Session/Participant/Vote, Flyway migrations, unique constraints (`session_id`+`participant_id`+`movie_id` on Vote, unique join code), upsert vote-write query, restart-survival test.
**Addresses:** Persistence-across-restarts differentiator (FEATURES.md); structural prerequisite for vote-count aggregation.
**Avoids:** Pitfall 1 (in-memory state), Pitfall 4 (non-idempotent votes) — both enforced at the schema level from day one.

### Phase 2: Session/Lobby Flow (create, join, no-auth identity)
**Rationale:** Needs the schema from Phase 1; provides the minimum multi-participant surface needed to test aggregation logic meaningfully.
**Delivers:** REST endpoints for create/join/status, server-issued participant token (not display-name-as-identity), session code generation with sufficient entropy.
**Uses:** Spring Data JPA repositories, `session/` package boundary from ARCHITECTURE.md.
**Implements:** Session/Lobby Controller & Service component.

### Phase 3: TMDB Integration & Catalog Caching
**Rationale:** No dependency on voting/session logic — can be built and tested in parallel with Phase 2.
**Delivers:** Server-side `MovieCatalogClient`/`MovieCatalogService` with genre + `watch_region`-aware streaming filters, cached `CachedMovie`/genre/provider tables with TTL refresh, backend proxy so the TMDB API key never reaches the frontend.
**Uses:** `WebClient`/`RestClient` with Bearer-token auth, `retryWhen` backoff.
**Avoids:** Pitfall 6 (TMDB key exposure), the TMDB caching/rate-limit integration gotchas from PITFALLS.md.

### Phase 4: Vote Recording & Match Aggregation
**Rationale:** Depends on both the schema (Phase 1) and the deck (Phase 3) since a vote references a served movie; this is the single most correctness-critical phase given it's the prior prototype's exact failure point.
**Delivers:** Vote-submission endpoint (upsert), `MatchAggregationService` computing live "N of M finished" and unanimous-match queries purely from persisted votes, atomic/transactional completion-trigger logic.
**Addresses:** Match-determination + vote-count aggregation features (FEATURES.md P1).
**Avoids:** Pitfall 2 (race condition on completion), Pitfall 3 (stale denominator) — both require an explicit, tested design here, including a two-concurrent-clients test and a restart-mid-session test.

### Phase 5: Real-Time Notification Layer (WebSocket/STOMP)
**Rationale:** Deliberately last — because the REST/DB flow from Phases 1-4 should already be fully correct and usable via polling, WebSocket is additive UX polish that can't hide an aggregation bug behind "it works over the socket."
**Delivers:** STOMP endpoint + broker config, `SessionEventPublisher` broadcasting to `/topic/session/{code}`, client reconnect-then-reconcile pattern (REST status fetch on every connect/reconnect).
**Implements:** Realtime Notification Layer component; the "waiting on N people" differentiator feature.
**Avoids:** Pitfall 5 (missed messages on disconnect) — designed in from the start via the reconnect-then-REST pattern, not bolted on later.

### Phase 6: Frontend SPA (Swipe Deck, Waiting Screen, Results)
**Rationale:** Can begin against a stubbed/mocked API in parallel with Phases 3-5, but shouldn't be considered feature-complete until wired to real endpoints.
**Delivers:** React 19 + Vite SPA with join/lobby, swipe deck (`motion`-based drag), live waiting screen (`@stomp/stompjs` + REST reconcile), results screen with poster + watch-provider CTA.
**Addresses:** All P1 table-stakes UI features from FEATURES.md.

### Phase Ordering Rationale

- Persistence-first ordering directly mirrors ARCHITECTURE.md's "Suggested Build Order," which was derived by inspecting the prior prototype's actual failure and confirming each subsequent layer's correctness depends on the one before it.
- TMDB integration is intentionally parallelizable with session/lobby work since neither depends on the other — useful if working solo but batching effort, or if wanting to de-risk the external integration early.
- Real-time is pushed to the end deliberately: it's the layer most likely to *feel* like magic if built early (masking whether the underlying REST/DB logic is actually correct), so proving correctness without it first is a genuine risk-reduction move, not just a convenience.
- This ordering directly avoids re-creating the prior prototype's failure mode, where UI/swipe-flow polish outpaced correctness of the underlying aggregation.

### Research Flags

Needs deeper research during planning:
- **Phase 4 (Vote Recording & Match Aggregation):** Concurrency/locking strategy (optimistic locking vs. `SELECT ... FOR UPDATE` vs. atomic SQL) needs to be chosen concretely against the actual chosen DB driver/JPA version — PITFALLS.md flags this as the highest-risk logic in the whole app.
- **Phase 5 (Real-Time Layer):** STOMP client reconnect/resubscribe semantics (avoiding duplicate-message-on-reconnect, per the `stompjs` GitHub issue cited in PITFALLS.md) warrant a focused look at current `@stomp/stompjs` v7 reconnect API before implementation.
- **Phase 3 (TMDB Integration):** Current TMDB rate-limit and image-CDN connection-limit numbers are LOW-confidence (community forum, not official docs) and should be re-verified against current TMDB docs at implementation time.

Phases with standard, well-documented patterns (research-phase can likely be skipped):
- **Phase 1 (Persistence Foundation):** Standard Spring Data JPA + Flyway + Postgres pattern, HIGH confidence, no novel technical risk.
- **Phase 2 (Session/Lobby Flow):** Standard REST CRUD + token-based pseudo-identity pattern, well-documented.
- **Phase 6 (Frontend SPA):** Standard Vite/React scaffold + `motion` drag pattern, well-documented with first-party docs.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | MEDIUM-HIGH | Backend/DB/TMDB facts verified against official docs (spring.io, TMDB developer docs); frontend framework popularity and library-maintenance claims are cross-checked web search, not official sources |
| Features | MEDIUM | Niche category with thin official documentation — most competitor claims are marketing copy from app store listings, not verified engineering behavior; patterns cross-verified across 3+ independent apps where noted |
| Architecture | MEDIUM-HIGH | Component design and data-model patterns are well-established and cross-checked against official Spring docs; critically, also verified directly against this repo's actual prior-prototype code (HIGH confidence, primary source) |
| Pitfalls | MEDIUM | Concurrency/idempotency patterns are well-established distributed-systems knowledge (HIGH-confidence sources); some TMDB-specific numeric limits are single-sourced from community forums (LOW confidence, flagged for re-verification) |

**Overall confidence:** MEDIUM-HIGH

### Gaps to Address

- **Competitor internals for group (3+) matching rules are undocumented** — no surveyed app publishes exactly how they generalize "match" beyond 2 people; MuviMatchr's decision to keep strict unanimity in v1 and defer threshold/majority rules is a reasonable, low-risk choice but should be explicitly confirmed as a product decision during requirements, not just inferred from research.
- **No-match fallback behavior is unsolved in the market and undefined in v1 scope** — should be explicitly decided (even if deferred to v1.x) during requirements/roadmap so it's not discovered mid-implementation.
- **Late-joiner and abandoned-participant handling needs an explicit product decision** — PITFALLS.md flags this as a case that "silently breaks results" if left undefined; should be resolved during Phase 2/4 planning, not left implicit.
- **Concurrency mechanism for the completion-check race (Phase 4)** — needs a concrete technical decision (optimistic locking column vs. transactional SQL count) during phase planning, not just the general principle from this research.
- **TMDB rate-limit/CDN-connection numeric limits are LOW confidence** — re-verify against current TMDB docs when Phase 3 is planned.

## Sources

### Primary (HIGH confidence)
- spring.io/projects/spring-boot — current stable version verification
- developer.themoviedb.org (authentication, configuration, rate-limiting docs) — official TMDB API documentation
- Direct inspection of this repo's prior prototype (`MovieVoteController.kt`, `LobbyService.kt`, `model/Lobby.kt`) — confirms the exact in-memory/overwrite failure mode
- Spring Framework official docs — STOMP broker relay scaling with RabbitMQ
- Google Cloud — idempotency key / idempotent API design patterns

### Secondary (MEDIUM confidence)
- Web search — React/Vue/Svelte 2025/2026 adoption and ecosystem comparison
- Web search — Spring Boot WebSocket STOMP vs. raw WebSocket vs. SSE tradeoffs
- Web search — Render/Railway/Fly.io free-tier and Postgres hosting comparison, 2026
- Web search — Spring Boot 4.0 migration guides (Kotlin/Jackson/JSpecify breaking changes)
- App Store / Google Play listings for Matched, Movie Matchup, FlickFix, Netflip, Movie Swiper, cross-verified with independent news/review coverage (ABC 6, Android Police, MakeUseOf)
- Distributed-systems race-condition and optimistic-locking pattern sources (multiple independent cross-checks)

### Tertiary (LOW confidence)
- TMDB community forum posts on rate limits and image CDN connection limits — needs re-verification against current official docs
- Single-source marketing copy (MatchaFilm, Movie Matchup, FlickFix app descriptions) for feature-mechanics inference
- stompjs GitHub issue tracker — single primary-source report of duplicate-message-on-reconnect behavior

---
*Research completed: 2026-09-01*
*Ready for roadmap: yes*
