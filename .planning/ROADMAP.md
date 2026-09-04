# Roadmap: MuviMatchr

## Overview

MuviMatchr is rebuilt as six horizontal layers rather than vertical feature slices — a deliberate choice because the prior prototype in this repo failed specifically at persistence and vote aggregation, not at UI. Each phase proves one layer of backend correctness before the next is built on top of it: durable storage first, then session/lobby identity, then the TMDB catalog, then vote recording and match aggregation (the highest-risk logic, and the prior prototype's exact failure point), then a thin real-time notification layer, and only then the real SPA frontend that ties the whole flow together for a human. By the time the frontend phase begins, every backend behavior it displays has already been proven correct via REST/DB-level tests — the UI can't hide an aggregation bug behind "it works over the socket."

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Persistence Foundation** - Session/Participant/Vote schema is durable, constraint-enforced, and proven to survive a restart before anything is built on top of it
- [x] **Phase 2: Session & Lobby Flow** - Hosts can create sessions and participants can join, get an unguessable token, and resume later without losing votes (completed 2026-09-03)
- [ ] **Phase 3: TMDB Integration & Catalog Caching** - The movie deck is sourced from real TMDB data, filterable by genre and region-aware streaming availability, cached server-side, with the API key never reaching the frontend
- [ ] **Phase 4: Vote Recording & Match Aggregation** - Votes are recorded idempotently and the "everyone finished" / "unanimous match" logic is computed correctly and race-free from live data
- [ ] **Phase 5: Real-Time Notification Layer** - Connected participants get live status updates over WebSocket, with reconnects always reconciled against the server as source of truth
- [ ] **Phase 6: Frontend SPA** - The full join-swipe-wait-results journey is usable end-to-end through a real React SPA, with results visible immediately to anyone who returns later

## Phase Details

### Phase 1: Persistence Foundation

**Goal**: Session, Participant, and Vote data is durably persisted to a real database via a correct, constraint-enforced schema, proven correct in isolation before any endpoint or UI is built on top of it.
**Depends on**: Nothing (first phase)
**Requirements**: RELI-01
**Success Criteria** (what must be TRUE):

  1. Session, Participant, and Vote rows written before a server restart are still present and correctly readable immediately after restart (a restart-mid-session test passes).
  2. The schema enforces a unique constraint on (session_id, participant_id, movie_id) for votes, and an upsert write path updates the existing row instead of erroring or creating a duplicate.
  3. The schema enforces a unique constraint on session join codes, rejecting a duplicate.
  4. Flyway migrations apply cleanly to a fresh database and are safe to reapply (schema is fully version-controlled, not manually applied or created via `ddl-auto`).

**Plans**: 3 plans

Plans:
**Wave 1**

- [x] 01-01-PLAN.md — Toolchain upgrade to Boot 4.1.1 / Kotlin 2.3.20 / Gradle 8.14.3, persistence dependency set, container runtime install, local Postgres 18 compose service

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 01-02-PLAN.md — Tracer: session table, entity and repository proven end-to-end by a full-context restart against real Postgres, plus the join-code unique constraint

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 01-03-PLAN.md — Participant and vote tables, entities and repositories, the race-safe native upsert, and the restart proof extended to all three entities

### Phase 2: Session & Lobby Flow

**Goal**: A host can create a session and share it, and any number of participants can join with just a display name, get a real identity token, and leave/resume without losing progress.
**Depends on**: Phase 1
**Requirements**: SESH-01, SESH-02, SESH-03, SESH-04, SESH-05
**Success Criteria** (what must be TRUE):

  1. Creating a session returns a unique, shareable join code/link.
  2. Joining a session with a valid code and a display name returns a server-issued, unguessable participant token (not just an echo of the display name); requests without a valid token cannot act as that participant.
  3. A single session can be joined by 3+ distinct participants, confirming group support isn't hardcoded to exactly 2.
  4. A participant who returns later using their previously issued link/token is recognized as the same participant (no duplicate participant row is created), and any votes they already cast are still attributed to them.

**Plans**: 2/2 plans executed

Plans:
**Wave 1**

- [x] 02-01-PLAN.md — Tracer: session creation, participant join with SecureRandom+SHA-256 token issuance, and bearer-token resolve/resume, wired end-to-end

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 02-02-PLAN.md — Bean Validation on the join request, multi-participant (3+) proof, and join-code distinctness proof

### Phase 3: TMDB Integration & Catalog Caching

**Goal**: The movie deck participants swipe through is sourced from real, current TMDB data, filterable and cached, with TMDB credentials never exposed to the client.
**Depends on**: Phase 1
**Requirements**: CTLG-01, CTLG-02, CTLG-03, CTLG-04, CTLG-05
**Success Criteria** (what must be TRUE):

  1. A deck-fetch endpoint returns real TMDB titles, posters, genres, and streaming providers — not placeholder or mock data.
  2. Requesting the deck with a genre filter returns only movies matching that genre.
  3. Requesting the deck with a region + streaming-provider filter returns only movies available on that provider in that region.
  4. A second deck request for the same filters within the cache TTL does not trigger a new upstream TMDB call (verified via call-count or log assertion), confirming server-side caching is working.
  5. No response reaching the frontend, and no frontend-bundled code, ever contains the TMDB API key — all TMDB calls are backend-proxied.

**Plans**: 4/5 plans executed

Plans:
**Wave 1**

- [x] 03-01-PLAN.md — Tracer: a genre-filtered deck wired end-to-end from TMDB through a filter-keyed Postgres JSONB cache to an authenticated endpoint, with the credential proven never to reach the caller

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 03-02-PLAN.md — Session region and multi-select streaming-provider selection, optional at creation and replaceable by any participant

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 03-03-PLAN.md — Genre and per-region watch-provider reference caches with a long TTL, plus rejection of unknown filter ids before they reach an outbound URL or stored state

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 03-04-PLAN.md — Region-aware provider filtering driven by the session row, and per-movie streaming availability resolved under a bounded concurrency budget on the refresh path only

**Wave 5** *(blocked on Wave 4 completion)*

- [ ] 03-05-PLAN.md — Outage degradation (retry, then serve stale labelled as stale, then fail explicitly) and the explicit insufficient-results response for sparse filter combinations

*Note: the five plans run in five sequential waves. Plan 03-02 shares no source file with 03-01 and would otherwise be parallelisable, but each of 03-01, 03-02 and 03-03 adds a Flyway migration, and a lower-numbered migration landing after a higher-numbered one has already run is the out-of-order condition Flyway refuses on a persistent database. Plans 03-03 through 03-05 additionally share `MovieCatalogClient.kt`, `MovieCatalogService.kt` and `DeckController.kt`.*

### Phase 4: Vote Recording & Match Aggregation

**Goal**: Participants' swipes are recorded correctly and exactly once each, and "has everyone finished" / "what's the match" are always computed live and correctly, even under concurrent or late-joining conditions — the exact class of bug that broke the prior prototype.
**Depends on**: Phase 1, Phase 2, Phase 3
**Requirements**: VOTE-01, VOTE-02, VOTE-03, VOTE-04, VOTE-05, RSLT-03
**Success Criteria** (what must be TRUE):

  1. A participant can submit a swipe (like/pass) for a movie via an endpoint, and the vote is persisted immediately — queryable right after the call returns, and still present after a server restart.
  2. Submitting a second vote for the same participant+movie updates the existing vote rather than creating a duplicate row.
  3. A movie is reported as a match only when every currently-joined participant has liked it; a movie liked by all-but-one participant is never reported as a match.
  4. "Has everyone finished" is computed against the live/current participant roster: a participant who joins mid-session after others already finished correctly flips the state back to "not everyone finished" until they finish too, and a two-concurrent-clients test where two participants submit their final vote at the same moment triggers match computation exactly once (no double-trigger, no missed trigger).
  5. A query against the vote table can return per-movie like counts (not just a single winner flag), confirming the data model supports a future ranked list without a schema change.

**Plans**: TBD

### Phase 5: Real-Time Notification Layer

**Goal**: Participants who are online see live status without refreshing, and reconnecting participants always land on the server's true current state rather than something stale.
**Depends on**: Phase 4
**Requirements**: RTIME-01, RTIME-02, RTIME-03
**Success Criteria** (what must be TRUE):

  1. A participant who finishes voting before others receives live "waiting on N of M" updates over WebSocket as other participants finish, with no page refresh required.
  2. When the last participant finishes, all currently-connected participants automatically transition to the results view with no manual action.
  3. A participant who disconnects and reconnects (e.g., closes and reopens the tab, or drops network briefly) always sees correct current status on reconnect, reconciled via a REST status fetch — never a stale cached WebSocket state.

**Plans**: TBD

### Phase 6: Frontend SPA

**Goal**: Users experience the full MuviMatchr flow — create/join, swipe, wait, results — through a real SPA, and results are correctly visible to everyone including people who weren't connected when voting finished.
**Depends on**: Phase 2, Phase 3, Phase 4, Phase 5
**Requirements**: RSLT-01, RSLT-02
**Success Criteria** (what must be TRUE):

  1. A user can go from opening the app to seeing results entirely through the React SPA — create/join a session, enter a display name, swipe through the deck, watch a live waiting screen, and land on results — with no Thymeleaf server-rendered page anywhere in the flow.
  2. A participant who opens the session link after everyone has finished, without having been connected when voting completed, is taken directly to the results view showing the match — no dead end, blank screen, or manual refresh needed.
  3. The results view displays the single best mutual match's poster, title, and where to watch it.
  4. The swipe deck UI responds to left/right drag gestures and visually reflects each pass/like before advancing to the next card.

**Plans**: TBD
**UI hint**: yes

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5 → 6
(Phase 2 and Phase 3 have no dependency on each other and can be built in either order or in parallel.)

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Persistence Foundation | 3/3 | Complete | 2026-09-02 |
| 2. Session & Lobby Flow | 2/2 | Complete    | 2026-09-03 |
| 3. TMDB Integration & Catalog Caching | 4/5 | In Progress|  |
| 4. Vote Recording & Match Aggregation | 0/TBD | Not started | - |
| 5. Real-Time Notification Layer | 0/TBD | Not started | - |
| 6. Frontend SPA | 0/TBD | Not started | - |
