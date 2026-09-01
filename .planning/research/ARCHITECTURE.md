# Architecture Research

**Domain:** Group real-time voting / "swipe to match" web app (session-based, no auth)
**Researched:** 2026-09-01
**Confidence:** MEDIUM-HIGH (component design and data model are well-established patterns cross-checked against official docs and community sources; specific rate limits/config details are MEDIUM confidence web-sourced and should be re-verified against current TMDB/Spring docs at implementation time)

## Standard Architecture

### System Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Client (SPA)                                 │
│   Lobby/Join UI · Swipe Deck UI · Waiting Screen · Results UI        │
│   - REST calls (join, submit vote, fetch state)                      │
│   - WebSocket/STOMP subscription (live session status)               │
└───────────────┬───────────────────────────────┬─────────────────────┘
                │ HTTP (REST, JSON)              │ WS (STOMP over SockJS)
┌───────────────▼───────────────┐   ┌────────────▼─────────────────────┐
│   Session/Lobby Controller     │   │   Realtime Notification Layer     │
│   (create, join, participant   │   │   (WebSocketConfig + STOMP        │
│    roster, session status)     │◄──┤    broker; SimpMessagingTemplate  │
└───────────────┬────────────────┘   │    publishes to /topic/session/   │
                │                     │    {code})                        │
                │                     └────────────▲─────────────────────┘
┌───────────────▼────────────────┐                 │ (server-side push,
│   Vote/Swipe Controller         │                 │  triggered by domain
│   (record participant's swipe   │                 │  events below)
│    on one movie)                │                 │
└───────────────┬────────────────┘                 │
                │ calls                              │
┌───────────────▼─────────────────────────────────────────────────────┐
│                        Application Service Layer                     │
│  SessionService · VoteService · MatchAggregationService              │
│  - Owns transactions, business rules, and emits domain events        │
│    ("participant joined", "vote recorded", "session completed")      │
│  - MatchAggregationService recomputes completion + match purely      │
│    from persisted state (no in-memory counters)                      │
└───────────────┬───────────────────────────────────────┬─────────────┘
                │                                        │
┌───────────────▼────────────────┐   ┌───────────────────▼─────────────┐
│   TMDB Integration/Cache Layer  │   │        Persistence Layer         │
│   MovieCatalogClient (HTTP)     │   │   Spring Data JPA repositories   │
│   + local cache tables for      │   │   over relational DB             │
│   genres, watch providers,      │   │   (sessions, participants,       │
│   and fetched movie pages       │   │    votes, cached movie refs)     │
└───────────────┬────────────────┘   └───────────────┬───────────────────┘
                │ HTTPS                                │ JDBC
┌───────────────▼────────────────┐   ┌───────────────▼───────────────────┐
│         TMDB API (external)     │   │   Postgres (or equivalent RDBMS)   │
└─────────────────────────────────┘   └─────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility | Typical Implementation |
|-----------|----------------|-------------------------|
| Session/Lobby Controller & Service | Create session (generate short join code), join by code (assign display name, no auth), track participant roster, expose session status | `@RestController` + `SessionService`; join code = short random string, e.g. 6 alphanumeric chars, unique-indexed |
| Vote/Swipe Controller & Service | Accept "participant X voted Y (like/pass) on movie M in session S", persist it, advance that participant's deck position | `@RestController` + `VoteService`; each call is a single upsert, not an in-memory map mutation |
| Match-Aggregation Service | Determine "has everyone finished?" and "what's the best mutual match?" purely by querying persisted votes; triggered after each vote write and on session status requests | Pure read-model service, no mutable state held in the JVM; recomputable at any time, including after restart |
| TMDB Integration/Caching Layer | Fetch movie deck (filtered by genre + streaming availability), fetch poster/watch-provider data, cache results locally | `MovieCatalogClient` (RestClient/WebClient) + cache tables (`cached_movie`, `genre`, `watch_provider`) refreshed on a schedule, not per-request |
| Real-time Notification Layer | Push "N of M finished" and "results ready" events to connected clients in a session | Spring `WebSocketConfig` + STOMP broker; server publishes to `/topic/session/{code}` whenever `MatchAggregationService` detects a state change |
| Persistence Layer | Durable source of truth for session, participant, vote, and computed-result state | Spring Data JPA + relational DB (Postgres in production; H2 acceptable only for local dev, never for the "survives restart" requirement) |

**Key architectural rule driving this whole design:** the database is the only source of truth for votes and session state. The WebSocket layer is a *notification* mechanism only — it never carries vote data as the record of truth, and it never holds aggregation state in memory. This directly targets the prior prototype's two failures (in-memory `HttpSession` state lost on restart, and vote aggregation that overwrote instead of merged).

## Recommended Project Structure

```
src/main/kotlin/org/example/muvimatchr/
├── session/                    # Session & participant lifecycle
│   ├── SessionController.kt    # REST: POST /sessions, POST /sessions/{code}/join
│   ├── SessionService.kt       # create/join/status business logic
│   ├── Session.kt              # JPA entity
│   └── Participant.kt          # JPA entity
├── voting/                     # Swipe recording + aggregation
│   ├── VoteController.kt       # REST: POST /sessions/{code}/votes
│   ├── VoteService.kt          # upsert vote, advance deck position
│   ├── MatchAggregationService.kt  # compute completion + best match from DB
│   └── Vote.kt                 # JPA entity
├── catalog/                    # TMDB integration
│   ├── MovieCatalogClient.kt   # HTTP client wrapper around TMDB
│   ├── MovieCatalogService.kt  # deck-building logic (genre + provider filters), cache-aware
│   ├── CachedMovie.kt          # JPA entity (cached TMDB response subset)
│   └── tmdb/                   # DTOs matching TMDB's JSON shape
├── realtime/                   # WebSocket/STOMP layer
│   ├── WebSocketConfig.kt      # STOMP endpoint + broker config
│   └── SessionEventPublisher.kt # thin wrapper around SimpMessagingTemplate
├── common/                     # Cross-cutting: join-code generation, error handling
└── MuviMatchrApplication.kt
```

### Structure Rationale

- **Package by feature (`session/`, `voting/`, `catalog/`, `realtime/`), not by layer (`controller/`, `service/`, `model/`)** — the prior prototype used layer-based packages and it obscured that `MovieVoteController` was doing session, vote, and aggregation logic all in one class. Feature packages keep each component's boundary honest and make "what talks to what" visible in the folder structure itself.
- **`realtime/` is deliberately thin** — it should only ever be called *by* `voting/` and `session/` services to push notifications; it must never own business state or be queried for vote data.
- **`catalog/` is isolated behind `MovieCatalogService`** — nothing outside this package should call TMDB directly or know TMDB's JSON shape; this makes it possible to swap or add a second data source later without touching voting/session logic.

## Architectural Patterns

### Pattern 1: Database-as-source-of-truth aggregation (no in-memory vote maps)

**What:** Every vote is a row in a `vote` table keyed by `(session_id, participant_id, movie_id)`. Aggregation ("has everyone finished," "what's the match") is always computed by querying this table — never by mutating an in-memory `Map` that lives in a controller or service field.
**When to use:** Always, for this project. This is the direct fix for the prior prototype's core bug (`aggregatedVotes[lobbyId] = userVotes` overwrote the whole session's votes with just the current viewer's votes on every page load, instead of merging across participants — see `MovieVoteController.kt:64` in the old code).
**Trade-offs:** Slightly more DB round-trips than an in-memory cache; irrelevant at this project's scale (single small deployment, low concurrent session count). Correctness and restart-survival far outweigh the minor query cost.

**Example:**
```kotlin
@Entity
@Table(uniqueConstraints = [UniqueConstraint(columnNames = ["session_id", "participant_id", "movie_id"])])
class Vote(
    @ManyToOne val session: Session,
    @ManyToOne val participant: Participant,
    val movieId: Long,          // TMDB movie id
    @Enumerated(EnumType.STRING) val choice: VoteChoice, // LIKE / PASS
    val votedAt: Instant = Instant.now(),
)

// Aggregation: participants who liked every movie the others also liked
fun findBestMatch(sessionId: UUID): MovieId? =
    voteRepository.findLikedByAllParticipants(sessionId) // pure SQL, GROUP BY movie_id HAVING COUNT(DISTINCT participant_id) = total participants
        .firstOrNull()
```

### Pattern 2: Upsert-on-conflict vote writes (idempotent, rejoin-safe)

**What:** Writing a vote is an upsert on the `(session_id, participant_id, movie_id)` unique key, not a blind insert. A participant who resubmits (double-tap, retry after dropped connection, or rejoins on a new device with the same participant identity) overwrites their own prior vote for that movie rather than creating a duplicate or crashing on a constraint violation.
**When to use:** Every vote-write endpoint.
**Trade-offs:** Requires a DB-level unique constraint plus either `INSERT ... ON CONFLICT DO UPDATE` (native query) or a read-then-write inside a transaction if staying in pure JPA. Native upsert is simpler and race-safe; prefer it over "find-or-create" application logic.

**Example:**
```kotlin
@Modifying
@Query(
  value = """
    INSERT INTO vote (id, session_id, participant_id, movie_id, choice, voted_at)
    VALUES (:id, :sessionId, :participantId, :movieId, :choice, now())
    ON CONFLICT (session_id, participant_id, movie_id)
    DO UPDATE SET choice = :choice, voted_at = now()
  """,
  nativeQuery = true
)
fun upsertVote(id: UUID, sessionId: UUID, participantId: UUID, movieId: Long, choice: String)
```

### Pattern 3: Event-driven notification, not state-carrying broadcast

**What:** After a vote is persisted, the service layer emits a lightweight domain signal (e.g. "session {code} status changed") and the realtime layer re-fetches current status from the DB before broadcasting it — the WebSocket payload is a fresh read, not a value threaded through from the write path. Clients treat every WS message as "go re-check status," not as the authoritative payload itself (though in practice you can include the freshly-read status directly for one less round trip).
**When to use:** Whenever a vote completes and might change "N of M finished" or trigger a completion transition.
**Trade-offs:** One extra DB read per notification vs. passing the in-memory result along — negligible at this scale, and it guarantees the broadcast always reflects durable state (not a value that could be stale if two votes race).

```kotlin
@Transactional
fun recordVote(sessionId: UUID, participantId: UUID, movieId: Long, choice: VoteChoice) {
    voteRepository.upsertVote(...)
    val status = matchAggregationService.computeStatus(sessionId) // fresh DB read
    sessionEventPublisher.broadcast(sessionId, status)            // WS push
}
```

### Pattern 4: Reconnect-safe client, not reconnect-safe server

**What:** The server never assumes a client is "connected" as part of correctness — WebSocket is purely a push convenience. On page load (including after a WS drop, browser refresh, or returning hours later), the client always does a REST GET for current session/vote/result status first, then subscribes to the topic for subsequent live updates. This is what makes "shows results immediately to anyone who returns later" and "server restart mid-session" work for free — there is no session-affinity or reconnect-replay logic to build.
**When to use:** Always, for this project's async-voting UX.
**Trade-offs:** None significant — this is strictly simpler than building WS reconnect/replay logic, and it's required anyway to satisfy the "resume later" and "restart survival" requirements.

## Data Flow

### Vote submission flow

```
Client swipes on movie
    ↓ POST /sessions/{code}/votes { participantId, movieId, choice }
VoteController → VoteService.recordVote()
    ↓ (transactional)
  1. upsert Vote row
  2. MatchAggregationService.computeStatus(sessionId)  ← reads Vote + Participant tables
  3. if status changed (progress or completion): SessionEventPublisher.broadcast(sessionId, status)
    ↓ (if broadcast happened)
STOMP /topic/session/{code} → all subscribed clients receive { finishedCount, totalCount, isComplete }
    ↓ (client-side)
Waiting-screen client updates count; on isComplete=true, client navigates to /results and does a REST GET for the computed match
```

### Session join / resume flow

```
Client → POST /sessions/{code}/join { displayName }
    ↓
SessionService creates/looks-up Participant row (session_id, display_name) → returns participantId
    ↓ stored client-side (localStorage), NOT server session/cookie state
Client → GET /sessions/{code}/status?participantId=... on every page load
    ↓
Server reads Session + Participant + Vote tables → returns { deckPosition, isComplete, ... }
    ↓
Client resumes exactly where it left off, subscribes to WS topic for live updates going forward
```

Note the deliberate absence of `HttpSession`/cookie-based identity (the prior prototype's approach): `participantId` is a durable value returned once at join time and held by the client, looked up against the DB on every request. This is what makes "leave and resume, including after a server restart" work — nothing participant-identifying lives only in server memory or a server-side session store.

### Movie deck / TMDB flow

```
Client requests deck (genre + streaming filters)
    ↓ GET /sessions/{code}/deck?participantId=...
MovieCatalogService checks local cache tables for matching filtered results
    ↓ cache miss or stale → MovieCatalogClient calls TMDB /discover/movie (with_genres, with_watch_providers, watch_region)
    ↓
Results cached locally (movie id, title, poster path, genres, providers) with a refresh timestamp
    ↓
Deck returned to client, minus movies this participant already voted on (join against Vote table)
```

## Scaling Considerations

| Scale | Architecture Adjustments |
|-------|---------------------------|
| Single small deployment (this project's actual target — friends/couples, low concurrent sessions) | Everything above as-is: single app instance, in-memory STOMP `SimpleBroker`, Postgres on the same small host/managed tier (Railway/Fly/Render). No message broker, no Redis needed. |
| Multiple app instances (only relevant if this ever needs horizontal scaling) | In-memory STOMP broker no longer works across instances — a WS client connected to instance A won't see a broadcast triggered by a vote written via instance B. Needs a broker relay (RabbitMQ/ActiveMQ) or Redis pub/sub fan-out. |
| High session churn / many concurrent sessions | Add a scheduled cleanup job for expired/abandoned sessions (see Pitfalls research for TTL policy); add indexes on `(session_id)` for Vote/Participant lookups (should exist from day one, not deferred). |

### Scaling Priorities

1. **Not a real concern at this project's stated scale** (small public deployment, friend-group use). Do not build for multi-instance WS fan-out or a message broker — it is unneeded complexity for v1 and actively works against the "keep it small and shippable" goal.
2. **The one thing worth building early even at small scale:** correct DB indexing (`session_id` on Participant/Vote, unique index on join code, unique constraint on `(session_id, participant_id, movie_id)`). These are cheap to add at schema-design time and expensive to retrofit.

## Anti-Patterns

### Anti-Pattern 1: In-memory maps as the vote/session store (the prior prototype's actual bug)

**What people do:** Keep votes and lobby state in `mutableMapOf<String, ...>` fields on a `@Service`/`@Controller`, or in `HttpSession` attributes. This is exactly what `MovieVoteController.kt` and `LobbyService.kt` did in this repo's prior attempt.
**Why it's wrong:** Two concrete failures result: (1) state is lost on every restart, directly violating the "survives a server restart" requirement; (2) aggregation logic that reads "my session's votes" and writes back `aggregatedVotes[lobbyId] = userVotes` overwrites the whole session's vote map with just the current viewer's own votes, silently discarding everyone else's — which is precisely why "adding together votes" never worked in the prior build.
**Do this instead:** Persist every vote as its own row via upsert (Pattern 1/2 above); compute aggregation via a query, never via accumulated in-memory state.

### Anti-Pattern 2: Treating WebSocket delivery as the record of truth

**What people do:** Push vote/result data over the WebSocket connection and have the client trust whatever arrives over the wire as current state, with no REST fallback.
**Why it's wrong:** WebSocket connections drop (network blips, backgrounded mobile browsers, server restarts). A client that only trusts pushed messages will show stale or blank state after a reconnect, and a client that was never connected when the broadcast fired (e.g. "returns later" per the requirements) never sees the result at all.
**Do this instead:** Pattern 4 — REST GET for current truth on load/reconnect; WebSocket only as a "something changed, go refetch (or here's the fresh value anyway)" signal.

### Anti-Pattern 3: Coupling TMDB response shapes directly into domain/UI models

**What people do:** Pass TMDB's raw JSON DTOs straight through controllers to the frontend, or store them ad hoc without a caching layer, hitting TMDB on every deck request.
**Why it's wrong:** Couples the whole app to TMDB's exact schema (breaks if TMDB changes fields), and repeated per-request calls for genre lists and watch-provider data (which change rarely) waste latency and risk hitting rate limits under any concurrent load.
**Do this instead:** `MovieCatalogService` translates TMDB DTOs into the app's own `CachedMovie` model at the integration boundary; genre and watch-provider reference data is fetched once and cached/refreshed on a schedule, not per deck request.

## Integration Points

### External Services

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| TMDB API | Server-side HTTP client (`RestClient`/`WebClient`) in `catalog/`, never called from the frontend directly (keeps API key server-side) | `/discover/movie` for filtered deck (genre + `with_watch_providers`/`watch_region`), `/movie/{id}/watch/providers` for per-title availability, `/genre/movie/list` cached long-term. Rate limiting was formally removed by TMDB in 2019 but CDN-level caps (~50 req/s) still apply — cache aggressively rather than relying on headroom. |
| Hosting platform (Railway/Fly.io/Render) | Managed Postgres add-on + single app instance/container | Confirms the "single instance, no distributed broker needed" scaling stance above. |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| Vote/Session services ↔ Realtime layer | Direct in-process method call (`SessionEventPublisher.broadcast(...)`), one-directional (services call realtime, never the reverse) | Keeps realtime layer a "dumb pipe" with zero business logic, per Pattern 3. |
| Vote/Session services ↔ Persistence layer | Spring Data JPA repositories, transactional | All aggregation reads/writes go through repositories — no component holds its own cache of vote state. |
| Catalog service ↔ Session/Vote services | Catalog exposes a `getDeckFor(sessionFilters, participantId)` method; session/vote code never talks to TMDB DTOs directly | Isolates the one component most likely to need future changes (new data source, different filters) from the correctness-critical voting core. |
| Client ↔ Server | REST for all state-changing and state-reading operations; WebSocket/STOMP strictly for push notifications of state changes | See Pattern 4 — this boundary is what makes restart/rejoin/resume-later all work without special-case logic. |

## Suggested Build Order

This maps directly to phase sequencing for the roadmap — each step's data model and correctness depend on the previous step existing first.

1. **Persistence schema first: Session, Participant, Vote entities + repositories.** Nothing else can be built correctly without this existing, since the hard requirement (correct aggregation, restart survival) lives entirely in this layer. Build and test the upsert + aggregation query in isolation (can be validated with a plain unit/integration test against an in-memory dataset of fake votes, no UI needed) before touching controllers.
2. **Session/lobby REST endpoints** (create, join, status) on top of the schema from step 1. This is the minimum needed to have multiple "participants" to test aggregation against.
3. **TMDB integration/caching layer**, built independently of voting (it only needs to produce a deck of movies with genre/provider metadata). Can be developed and tested in parallel with step 2 since it has no dependency on Session/Vote.
4. **Vote/swipe recording endpoint**, wired to both the schema (step 1) and the deck (step 3) — a vote references a `movieId` that must exist in the served deck.
5. **Match-aggregation logic**, built directly on top of the Vote table from step 1 — this is a read-only query layer and should be the most heavily tested component given it's the prior prototype's exact failure point. Write it and prove it correct (including via a server-restart test: write votes, restart the process, confirm the query still returns the right match) before wiring it to anything real-time.
6. **Real-time notification layer (WebSocket/STOMP)**, added last, as a thin push wrapper around the already-correct, already-tested REST/DB flow from steps 1-5. Because of Pattern 4 (client always does a REST fetch on load), the app should be fully correct and usable via REST/polling even before this step exists — WebSocket is additive UX polish, not a load-bearing correctness dependency. This ordering also means step 6 can't accidentally hide an aggregation bug behind "well it works over the socket," since the underlying correctness was already proven without it.
7. **Frontend SPA**, consuming the REST + WebSocket API surface established above. Frontend can begin against a stubbed/mocked API in parallel with steps 3-6, but should not be considered feature-complete until wired against the real endpoints.

## Sources

- [WebSocket.org — Spring Boot WebSocket: STOMP, Raw Handlers, Scaling](https://websocket.org/guides/frameworks/spring-boot/) — MEDIUM confidence
- [Spring Boot STOMP broadcast/session mechanics — Medium](https://medium.com/@AlexanderObregon/the-mechanics-behind-how-spring-boot-handles-websockets-with-stomp-messaging-0128ba5e600d) — MEDIUM confidence
- [algomaster.io — Idempotency in Distributed Systems](https://blog.algomaster.io/p/idempotency-in-distributed-systems) — MEDIUM confidence
- [bugfree.ai — System Design Deep Dive: Designing a Voting System](https://medium.com/@bugfreeai/system-design-deep-dive-designing-a-voting-system-bff917dbdcd2) — MEDIUM confidence
- [TMDB Developer Docs — Rate Limiting](https://developer.themoviedb.org/docs/rate-limiting) — HIGH confidence (official docs)
- [TMDB API surface overview — api-evangelist](https://github.com/api-evangelist/tmdb) — MEDIUM confidence
- [Postgres session/TTL cleanup patterns — pg_ttl_index docs](https://www.pg-ttl.online/docs/intro) — MEDIUM confidence
- [Kotlin/Spring persistence layer comparison (JPA/Hibernate vs Exposed vs jOOQ) — bol Techlab](https://techlab.bol.com/en/blog/bye-bye-hibernate-discovering-alternatives-to-hibernate-in-kotlin/) — MEDIUM confidence
- Direct inspection of this repo's prior prototype (`src/main/kotlin/org/example/muvimatchr/controller/MovieVoteController.kt`, `service/LobbyService.kt`, `model/Lobby.kt`) — HIGH confidence (primary source, confirms the exact in-memory/overwrite failure mode this architecture is designed to avoid)

---
*Architecture research for: group movie-matching swipe app (session-based, real-time, no auth)*
*Researched: 2026-09-01*
