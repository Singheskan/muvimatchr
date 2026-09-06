# MuviMatchr

## What This Is

MuviMatchr is a group movie-picking app: a host creates a session, shares a code/link, and everyone who joins (starting with a couple, extensible to friend groups) picks a display name and swipes left/right through a deck of movies filtered by genre and streaming availability. Voting is async — people can swipe whenever suits them, with a live-updating waiting screen once they're done. When everyone in the session has finished, the app surfaces the single best mutual match (with room to later show a ranked list of runner-up matches too).

## Core Value

Two (or more) people with different tastes can independently pick movies they'd watch and get a fast, confident answer to "what do we actually both want to watch tonight" — without the back-and-forth debate.

## Requirements

### Validated

- ✓ Host can create a session and get a shareable code/link — Phase 2
- ✓ Participants join a session via code/link and pick a display name (no account/signup required) — Phase 2
- ✓ Session supports 2+ participants (couple use case is primary, groups supported from v1) — Phase 2
- ✓ Movie data (titles, posters, genres, streaming availability) comes from a real source (TMDB) — Phase 3
- ✓ Movie deck can be filtered by genre — Phase 3
- ✓ Movie deck can be filtered by streaming availability (which services a title is on) — Phase 3
- ✓ Participant can swipe left (pass) / right (like) through the deck — Phase 4
- ✓ Voting is async — a participant can leave and resume; progress and votes persist — Phase 4
- ✓ Results correctly aggregate votes across all participants (the prior app never finished this — this was a hard requirement, not a nice-to-have) — Phase 4, proven under simultaneous-finish concurrency (10 independent races, zero lost votes, exactly-one completion signal each) against real Postgres
- ✓ Data survives an app/server restart (session state, votes, pinned deck are persisted, not in-memory only) — Phase 1 (sessions/participants) + Phase 4 (votes, pinned deck snapshot)

### Active

- [ ] Participant who finishes early sees a live-updating "waiting on N people" screen (via WebSocket), not a static page they must refresh
- [ ] When all participants finish, session transitions to a results view automatically for anyone watching the waiting screen, and shows results immediately to anyone who returns later
- [ ] Results view shows the single best mutual match as the primary result — the backend now computes this (`matchedMovieIds`, live and race-free); remaining work is the frontend view itself

### Out of Scope

- Real user accounts / auth (email+password, OAuth) — code/link + display name is enough for v1; revisit if this becomes a product people return to across many separate sessions
- Ranked list of all mutual matches as the primary result — deferred to v2; v1 shows a single top pick but the data model should not preclude adding this later
- Live-simultaneous swiping UX (e.g. both swiping at the exact same moment with real-time deck sync) — async is the target UX, not simultaneous play
- Recommendation/personalization beyond genre + streaming filters (no ML-based taste modeling) — out of scope for v1

## Context

- This is a full rebuild of an earlier prototype in this same repo (Kotlin/Spring Boot + Thymeleaf + WebSocket, commits "Voting independently works, but adding together votes not yet"). That prototype is being replaced, not incrementally patched: it stored all state in-memory (lost on restart), never finished multi-user vote aggregation, and pulled movie data from a placeholder `MovieService` rather than a real catalog.
- Prior app's working pieces worth keeping conceptually: lobby/join flow, WebSocket-based live updates, swipe-per-movie voting loop. These ideas carry forward; the code will be substantially rewritten to add persistence and fix aggregation.

## Constraints

- **Tech stack**: Kotlin + Spring Boot backend, paired with a proper SPA frontend (not server-rendered Thymeleaf) — decided so the swipe UI can feel snappy and native-app-like. Frontend framework choice (React/Vue/etc.) still open, to be settled during stack research.
- **Movie data**: TMDB (The Movie Database) API for titles, posters, genres, and streaming/watch-provider availability — chosen for real, current data instead of a hardcoded list.
- **Persistence**: Needs a real database (not in-memory maps) — sessions, participants, votes, and results must survive a server restart. Specific DB choice open, to be settled during stack research.
- **Hosting**: Targeting a small public deployment (e.g. Railway/Fly.io/Render free tier) — reachable by a link for friends to join, not a scaled public product.
- **Real-time**: WebSocket (or equivalent) needed for the live-updating waiting screen.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Rebuild rather than patch existing code | Vote aggregation was never finished and state model is in-memory-only; core data model needs to change anyway | Confirmed — Phase 1 (persistence) and Phase 2 (session/lobby) built clean, tested from scratch |
| Keep Kotlin/Spring Boot, add SPA frontend | User knows the backend stack; wants a real frontend instead of Thymeleaf for a better swipe UX | — Pending (frontend is Phase 6) |
| TMDB for movie data | Real titles/posters/genres/streaming availability instead of a placeholder list | Shipped — Phase 3 (`MovieCatalogClient`/`MovieCatalogService`), verified against the live TMDB API, not just mocked tests |
| Async voting with live waiting screen | Matches actual usage pattern (people swipe on their own time), while still feeling live when others are online | Vote recording/aggregation shipped — Phase 4; live waiting screen — Pending (Phase 5) |
| Server-side TTL cache for TMDB responses, keyed by filter combination | Bounds outbound TMDB call volume regardless of caller volume; TMDB rate limits are per-app, not per-user | Shipped — Phase 3 (`deck_cache_entry`, 6h TTL deck / 168h TTL reference data), proven via request-count-delta tests |
| Retry-then-stale-then-503 degradation ladder for TMDB outages | A transient TMDB outage shouldn't turn into a hard failure if a usable (if slightly stale) cached deck already exists | Shipped — Phase 3 (`MovieCatalogService.getDeck`), live-verified against a genuine connection-refused failure (retry engaged, correct stale-serve and 503-no-cache outcomes) |
| Merge flatrate/rent/buy/ads into one "where to watch" list, no monetization-type filter sent to TMDB | App doesn't distinguish subscription from rental anywhere in the UI; sending the scoping param would silently narrow results to TMDB's undocumented flatrate-only default | Confirmed — Phase 3, live-verified against TMDB that omitting the param returns the broadest match |
| Group sessions from v1, single-best-match result for v1 | Support couples and friend groups without over-scoping the results UI; ranked list deferred to v2 | Confirmed (group support) — Phase 2 proves 3+ distinct participants can join one session, not hardcoded to 2 |
| No accounts — code/link + display name | Keeps friction low for a casual "watch party" tool | Confirmed — Phase 2 ships join-code + display-name + server-issued bearer token, no account/signup |
| Server-issued 256-bit token, SHA-256 hash-at-rest, no session/JWT machinery | Simplest scheme that still prevents un-authorized impersonation of a participant; avoids pulling in Spring Security for a single-token-per-participant model | Shipped — Phase 2 (`TokenService`, `CurrentParticipantArgumentResolver`) |
| Join-code collision retry uses per-attempt `save()`, no shared `@Transactional` | Postgres aborts the entire transaction after one failed statement, so a shared-transaction retry would break on the second collision attempt | Shipped — Phase 2 (`SessionService.createSession()`) |
| Session-scoped pessimistic row lock (`SELECT ... FOR UPDATE` written directly into native SQL) as the single serialization primitive for vote recording, deck pinning, and filter replacement | Spring Data's `@Lock` annotation is silently ignored on native queries; a real DB-level lock is what the prior prototype's vote-loss bug needed and never had | Shipped — Phase 4 (`VoteService.recordVote`, `SessionService.pinDeck`/`replaceFilters`), proven via `VoteServiceConcurrencyTest`/`DeckPinConcurrencyTest` (10 independent races each) |
| Live read-model with zero cached/sticky completion state — `activeCount`/`finishedCount`/`isComplete`/matched movies recomputed from the database on every call | This is the exact defect that broke the prior prototype: an in-JVM progress counter that drifted from the database under concurrency | Shipped — Phase 4 (`MatchAggregationService`) |
| Deck pinned exactly once, lazily, on a session's first successful deck read; genre/region/providers locked together at that same moment | "Unanimous match" is only meaningful if every participant votes against one identical, immutable deck and filter set | Shipped — Phase 4 (`SessionService.pinDeck`, `Session.deckPinnedAt`/`pinnedDeckJson`) |
| Inactivity-based live roster (`COALESCE(last_voted_at, joined_at)` within a rolling window), not a static participant list, determines both who must finish and who counts toward unanimity | A participant who goes idle must not block the group forever, but exclusion must be non-sticky and never destructive to their already-cast votes | Shipped — Phase 4 (`VoteRepository.findActiveParticipantIds`), proven for late-join, idle-dropout, and idle-rejoin cases |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-09-06 after Phase 4*
