# Pitfalls Research

**Domain:** Swipe-based group-decision app / multi-user async-voting system with real-time notification (Kotlin + Spring Boot + TMDB)
**Researched:** 2026-09-01
**Confidence:** MEDIUM (patterns cross-checked across multiple independent sources; a few provider-specific numbers are single-sourced from community forums and flagged below)

This document is written with direct knowledge of MuviMatchr's own prior-attempt history: the existing prototype in this repo got single-user swiping working but never finished cross-user vote aggregation, and stored everything in in-memory maps that don't survive a restart or a second server instance. Several pitfalls below are not generic — they are literally what already happened in this codebase once, described precisely so the rebuild doesn't repeat them.

## Critical Pitfalls

### Pitfall 1: In-memory state as the source of truth for sessions/votes

**What goes wrong:**
Session, participant, and vote data lives only in a `ConcurrentHashMap` (or similar) inside a singleton service bean. Everything appears to work in manual testing, then a server restart (deploy, crash, host recycling a free-tier dyno) silently wipes every in-progress and completed session. If the app ever runs more than one instance (even accidentally, e.g. a rolling deploy briefly running two), each instance has a different view of the world and users get inconsistent results depending on which instance handled their request.

**Why it happens:**
`ConcurrentHashMap` is thread-safe for individual operations, which makes it *feel* like it solves the concurrency problem — but thread-safety per-key is not the same as correctness for multi-step operations (read-check-then-write across the whole map), and it has no persistence, no TTL, and no cross-instance visibility. This is exactly the failure mode already present in this repo's prior prototype.

**How to avoid:**
Persist session, participant, and vote records to a real database from the first phase that introduces any of them — do not build an in-memory version "to get the flow working first" and plan to swap it later. Use the database as the single source of truth; any in-memory structure (e.g. a cached deck) must be a derived/rebuildable cache, never the only copy of state that represents user intent (votes, membership, results).

**Warning signs:**
- Service classes hold `mutableMapOf`/`ConcurrentHashMap` fields for session/vote/participant data.
- No repository/DAO layer exists for sessions, participants, or votes.
- Restarting the app locally during manual testing loses an in-progress session (test this explicitly, don't assume).

**Phase to address:**
Foundational data/persistence phase — must land before or alongside the first voting flow, not as a later hardening pass.

---

### Pitfall 2: Race condition in "has everyone finished" / result-computation logic

**What goes wrong:**
Two participants submit their last vote within milliseconds of each other. Both requests independently read "N of M participants finished," both see themselves as the one completing the session, and either (a) both trigger result computation and broadcast, causing duplicate/racy WebSocket messages, or (b) a check-then-act gap means neither request sees `finished == total` at the moment it checks, and the session never transitions to "results ready" until someone happens to refresh.

**Why it happens:**
"Check if everyone is done" is usually implemented as two separate steps — read the current finished count, then decide whether to trigger completion — executed outside a transaction or lock. Under concurrent access this is a classic read-then-write race (the same class of bug as the general "lost update" problem in concurrent database writes), not something that shows up in single-user manual testing, which is why it's easy to ship believing "voting works" when only the aggregation step is broken (this is precisely the state the prior prototype was left in).

**How to avoid:**
Make "did this vote complete the session" an atomic, transactional check against the database (e.g., a single query that counts distinct finished participants against the persisted session/participant count and locks or uses `SELECT ... FOR UPDATE` / an equivalent transactional read, or an atomic counter/flag update guarded by optimistic locking with a version column so only one concurrent writer wins the "trigger completion" race). Whoever wins that race is the only one that computes and broadcasts the result; everyone else's completion check is a no-op.

**Warning signs:**
- The "is session complete" check is a separate DB read followed by a separate decision in application code, with no transaction/lock spanning both.
- No test exists that fires two "finish voting" requests concurrently for the same session.
- Result computation logic is not idempotent (running it twice produces two different result records or two broadcasts).

**Phase to address:**
Vote aggregation / matching logic phase.

---

### Pitfall 3: Off-by-one / stale denominator in "everyone finished" checks

**What goes wrong:**
The count of "how many participants need to finish" is captured once (e.g., at session start) and never revisited, but participants can join after the session starts (async voting is a core requirement here), leave without finishing, or reconnect. A late joiner means the session may show "waiting on 0 more" and reveal results before the new joiner has even had a chance to vote, or conversely a participant who joined and then abandoned the tab forever means the session can wait indefinitely for a vote that will never come.

**Why it happens:**
It's tempting to snapshot `totalParticipants` once and compare against a running `finishedCount`, because it's simpler than continuously recomputing "who counts as still-active" — but participant membership is not static in an async, join-anytime model, so any fixed denominator will eventually be wrong.

**How to avoid:**
Compute "has everyone finished" as a live query against currently-joined participants at the moment of each check (`COUNT(participants) == COUNT(participants with a completed vote pass)`), not against a cached total. Decide explicitly and document what happens if someone joins after voting has started for others (recommended: they get their own full deck and are added to the pending set; results wait for them) and what happens if a participant never finishes (recommended: either a host-triggered "finish without them" override, or a session-level timeout — but pick one, don't leave it undefined, since an undefined case here is exactly what silently breaks results).

**Warning signs:**
- `totalParticipants` (or similar) is stored as a fixed field on the session set once at creation.
- No handling exists for "participant joins after others have already finished voting."
- No handling exists for "a participant who joined never comes back."

**Phase to address:**
Session/participant lifecycle phase and vote aggregation phase (the two are coupled — the join flow determines what the aggregation check must account for).

---

### Pitfall 4: Non-idempotent vote submission causes double-counting on rejoin/retry

**What goes wrong:**
A participant's swipe is submitted, the network blips or the client retries automatically (or the user double-taps, or resumes a session on a second device/tab), and the same like/pass gets recorded twice for the same movie. Depending on how aggregation counts votes, this can silently inflate a movie's match score, or throw a constraint violation that surfaces as a crash instead of a clean no-op.

**Why it happens:**
The "async voting — a participant can leave and resume" requirement means the same client will hit the vote endpoint from a fresh page load with no client-side memory of exactly which requests already succeeded. Without an idempotency mechanism, "resume" and "duplicate resubmission" are indistinguishable to the server.

**How to avoid:**
Enforce a database-level unique constraint on `(session_id, participant_id, movie_id)` for votes, and make the vote-submission endpoint an upsert (insert-or-ignore / insert-or-update on conflict) rather than a plain insert. This makes retries and rejoin-and-resubmit naturally safe without needing a separate idempotency-key header scheme (which is the general-purpose solution but is more machinery than this app needs given votes are already naturally keyed by participant+movie).

**Warning signs:**
- Vote table/collection has no unique constraint spanning participant + movie (+ session).
- Vote submission is a plain `INSERT` with no conflict handling.
- No test exists for "submit the same vote twice" or "resume a session and re-swipe an already-voted movie."

**Phase to address:**
Vote aggregation / matching logic phase, enforced at the persistence-schema level (foundational data phase should define the constraint even before aggregation logic is built on top of it).

---

### Pitfall 5: WebSocket messages missed during disconnect leave clients permanently stale

**What goes wrong:**
A participant's phone locks, or their browser tab is backgrounded, or WiFi drops for a few seconds. The "everyone has finished, here are your results" broadcast fires while they're disconnected. When they reconnect, nothing re-sends that message, so their waiting screen sits there forever showing "waiting on 1 more person" even though results have been ready for an hour — this is indistinguishable from a bug to the user and is one of the most common WebSocket-based app failures.

**Why it happens:**
WebSocket push is inherently fire-and-forget from the server's perspective unless explicitly designed with delivery guarantees; a plain broadcast to "currently connected sockets" silently drops anyone not connected at that instant, and naive reconnect logic only re-establishes the connection without re-syncing state.

**How to avoid:**
Treat the WebSocket channel as a "wake up and go check" signal, not the sole carrier of truth. On every WebSocket (re)connection — including the very first connection and any reconnect after a drop — the client should fetch current session status via a REST call (`GET /sessions/{code}/status`) rather than relying purely on a push message it may have missed. This makes missed messages a non-issue: worst case, the user sees the correct state a moment later than they would have via push, but they never get stuck. The push message becomes an optimization (avoid needing to poll) rather than a requirement for correctness.

**Warning signs:**
- Client-side state ("waiting" vs "results ready") is only ever set by handling an incoming WebSocket message, never by an initial/reconnect REST fetch.
- No reconnection logic exists at all in the frontend WebSocket client (connection drop just leaves the UI frozen).
- Manual test of "close laptop lid mid-wait, reopen later" isn't part of verification.

**Phase to address:**
Real-time/WebSocket phase — the reconnect-then-reconcile pattern should be designed in from the start, not bolted on after the happy path works.

---

### Pitfall 6: TMDB API key exposed in the SPA frontend

**What goes wrong:**
Since the frontend is being rebuilt as a proper SPA (not server-rendered Thymeleaf), there's a natural temptation to call TMDB directly from the browser for snappier UX. Any API key shipped in frontend JS is visible to anyone who opens dev tools, gets scraped by bots, and can be used to run up TMDB usage against your account or get your key rate-limited/revoked.

**Why it happens:**
Calling a third-party JSON API directly from the client is the path of least resistance, and TMDB's API is easy to call with just a bearer token, so it's easy to skip the "why do I need a backend proxy for this" step.

**How to avoid:**
All TMDB calls go through the Spring Boot backend, which holds the API key server-side (config/env var, never committed, never sent to the client) and exposes MuviMatchr's own endpoints (e.g. `/api/movies/deck?genre=...&provider=...`) that the SPA calls instead. This also gives you a single point to add caching (see Pitfall 7) and to shape the response to exactly what the deck UI needs.

**Warning signs:**
- Any TMDB API key or bearer token appears in frontend source, `.env` files bundled into the client build, or network requests visible in browser dev tools.
- Frontend code imports a TMDB SDK or constructs `api.themoviedb.org` URLs directly.

**Phase to address:**
TMDB integration phase — the backend-proxy shape should be the very first version built, not retrofitted after a "quick direct call" prototype.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|-----------------|------------------|
| In-memory map for session/vote state | Fastest way to see the swipe flow working end-to-end | Exactly the prior prototype's failure: lost on restart, breaks with 2+ instances, race-prone | Never for anything beyond a local, throwaway spike — this project's core requirement is restart-durable state |
| Plain `INSERT` for votes, no unique constraint | Simpler schema, one less migration to write | Silent double-counting on retry/rejoin, hard to detect after the fact | Never — the constraint costs almost nothing to add up front and is expensive to retrofit once duplicate rows exist in production |
| Direct TMDB calls from frontend | Fewer backend endpoints to write initially | API key exposure, no server-side caching, no shielding from TMDB downtime/rate limits | Never for anything deployed publicly; acceptable only for a local non-shared dev spike with a throwaway key |
| Polling instead of WebSocket push for "waiting" screen | Simpler than managing socket lifecycle/reconnect | Fails the explicit "live-updating, not a page they must refresh" requirement; also wastes requests | Acceptable as a temporary fallback *layered under* WebSocket push (poll-on-reconnect, see Pitfall 5) — not acceptable as the only mechanism |
| Fixed participant count captured at session creation | Simpler aggregation query | Breaks the "leave and resume"/late-join model this app explicitly requires | Only acceptable if the product decision is made explicit: sessions lock participation once voting starts (would need to update PROJECT.md requirements if chosen) |

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|-----------------|-------------------|
| TMDB API | Calling `discover`/`watch/providers` endpoints fresh on every deck load per user | Cache genre lists, provider lists, and discover results server-side with a TTL (TMDB's own data changes infrequently; genre/provider catalogs are effectively static for weeks at a time) |
| TMDB API | Ignoring the `watch_region` parameter, assuming watch-provider data is global | Always pass the participant's/session's region explicitly; watch-provider availability is region-specific (data is sourced from JustWatch per TMDB's docs) and silently wrong without it |
| TMDB API | Treating the image CDN (`image.tmdb.org`) as unlimited | Community reports describe a connection-count limit (reported around 20 simultaneous connections per IP) on the image CDN — don't fire dozens of poster requests in parallel per deck load; let the browser's normal connection pooling and lazy-loading handle it, and prefer smaller image size variants for deck thumbnails rather than full-res posters |
| TMDB API | No fallback when TMDB is slow/down/rate-limited | Backend should catch TMDB failures and degrade gracefully (cached/stale data, or a clear "movie data temporarily unavailable" state) rather than a 500 that breaks the whole deck-loading flow |
| WebSocket (STOMP) | Assuming the default in-memory `SimpleBroker` will "just scale" if the app ever runs more than one instance | The simple in-memory broker only fans out to clients connected to *that* instance; if you ever scale beyond one instance, you need an external broker relay (RabbitMQ/ActiveMQ) or Redis pub/sub bridging instances — decide this consciously rather than discovering it in production. Given this project's stated single small-instance hosting target, it's fine to defer, but document it as a known limitation, not an oversight |
| WebSocket (STOMP client libraries, e.g. stompjs) | Naive auto-reconnect that re-subscribes without clearing prior subscription state | Known issue class in STOMP client libraries: reconnecting without properly tearing down old subscriptions can cause duplicate message delivery. Ensure reconnect logic unsubscribes/resets before resubscribing, and design message handlers to be safe against occasional duplicates (idempotent client-side state updates) |

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|-----------------|
| Fetching the movie deck (with poster images) synchronously from TMDB on every session/participant load | Deck feels slow to open, TMDB latency directly becomes app latency | Pre-fetch and cache the filtered deck server-side per (genre, provider, region) combination when a session's filters are set, not per participant | Noticeable even with a handful of concurrent users, since it's per-page-load latency, not a scale problem |
| Recomputing full vote aggregation (scanning all votes) inside a lock on every single vote event | Increasing latency on the vote-submit endpoint as a session's vote count grows | Use a targeted, indexed query (e.g., count of finished participants, and a per-movie "liked by all" check only run once completion is detected) rather than re-scanning and re-joining all vote rows on every write | Not a real risk at this app's expected scale (small groups, dozens of movies) — avoid over-engineering here, just don't do something needlessly O(all votes) inside a critical section |
| Broad table/row locking for the "is everyone done" check | Vote submissions from unrelated sessions block each other | Scope any lock/transactional check to the specific session's rows only (e.g., lock/select scoped by `session_id`) | Would only surface with many concurrent simultaneous sessions — worth getting the scoping right from the start since it's no extra effort, not worth building infrastructure for the "many sessions" case given this project's scale |

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Treating a client-supplied display name as identity, with no server-issued session/participant token | Anyone who can guess or observe another participant's display name can submit votes as them, or read their in-progress state, since there's no accounts/auth per the explicit "no accounts" requirement | On join, issue a server-generated, unguessable participant token (e.g., stored in a cookie or returned to the SPA and held in memory/localStorage) that authorizes subsequent vote/status calls and the WebSocket subscription for that participant — display name is a label, the token is the actual identity within the session |
| Guessable/sequential session codes | Outsiders could enumerate codes and join or read someone else's session | Generate session codes with enough entropy to resist brute-force guessing within a session's realistic lifetime (short-lived, human-shareable codes are fine, but avoid small sequential integers) |
| No origin/handshake validation on the WebSocket endpoint | Another site could open a WebSocket connection against your backend and subscribe to session channels if the session code is known or guessable | Validate the participant token during the WebSocket handshake/subscription (not just on initial HTTP calls), and scope topic subscriptions so a client can only subscribe to the session channel it has a valid token for |
| Logging TMDB API keys, participant tokens, or session codes in application logs | Credential/session leakage via log aggregation, especially on a hosted free-tier platform where logs may be less tightly controlled | Scrub secrets and tokens from log statements; log session codes only at a level appropriate for debugging, not in a way that lets anyone with log access hijack a live session |

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-------------------|
| Waiting screen has no visible reconnect/connection-lost indicator | User assumes the app is broken or results will never come, when really their WebSocket just silently dropped | Show a subtle "reconnecting..." state when the socket disconnects, and always reconcile against a REST status fetch on reconnect (see Pitfall 5) so the UI self-heals |
| No handling for "the deck ran out" (participant swiped every movie) | Dead-end screen or crash when a participant finishes their whole deck before others | Treat "swiped every movie in the deck" as equivalent to "finished voting" and transition to the waiting screen, same as any other completion |
| No clear state for "you're the only one who's voted so far" vs. general waiting | Feels the same as being stuck, no sense of progress | Show how many participants have finished vs. total (e.g., "2 of 3 done") rather than a generic spinner |
| Result reveal has a jarring delay or feels inconsistent between "the person who triggered it" and "people already waiting" | Perceived as a bug ("it worked for my partner but not for me") | Ensure the result-ready transition is broadcast to all connected participants at the same logical moment, and that anyone who loads/reconnects after the fact immediately sees results via REST fetch, not a stale waiting screen |

## "Looks Done But Isn't" Checklist

- [ ] **"Voting works"**: Often verified only with one browser tab/one user — verify with two+ concurrent clients (different browsers or devices) racing to finish at nearly the same time, since this is precisely where the prior prototype broke.
- [ ] **"Real-time waiting screen works"**: Often verified only on the happy path with both clients staying connected — verify by actually killing WiFi or backgrounding a mobile browser mid-wait, then reconnecting, and confirming the correct state appears without a manual refresh.
- [ ] **"Persistence added"**: Often verified by checking that DB rows exist, not that the *running app* correctly rehydrates and continues to function after a restart mid-session (e.g., a session with 1-of-2 participants finished, restart the server, confirm the second participant can still finish and results still compute correctly).
- [ ] **"TMDB integration works"**: Often verified only when TMDB responds successfully — verify what the deck-loading UI does when TMDB is slow, rate-limited, or returns an error (should degrade gracefully, not crash the whole flow).
- [ ] **"Session join flow works"**: Often verified only for the "code exists, session is open" happy path — verify joining with a nonexistent code, joining a session where everyone has already finished, and joining after voting has already started for others.
- [ ] **"Results are correct"**: Often verified with matching being obvious (everyone likes the same one movie) — verify the actual mutual-match logic with a case that has zero mutual matches, and a case where multiple movies tie for "everyone liked it" (what does "single best match" mean when there's a tie?).

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|----------------|-----------------|
| Duplicate vote rows discovered after the fact (no unique constraint originally) | MEDIUM | Add the unique constraint via migration; before applying it, run a de-duplication pass (keep the earliest or latest vote per participant+movie, per an explicit decision) and re-verify affected sessions' results |
| In-memory state shipped and then lost on a restart | LOW | Since this is caught by the "data survives restart" requirement itself, treat any in-memory-only implementation found during review as incomplete, not shipped — no production recovery needed if caught before real users depend on it |
| WebSocket broadcasts found to be dropped for disconnected clients in production | LOW–MEDIUM | Add the REST-based reconcile-on-connect fallback (Pitfall 5); no data is lost since votes/results already live in the database — this is a display-sync fix, not a data-integrity fix |
| TMDB API key found exposed in a shipped frontend bundle | MEDIUM | Rotate the key immediately in TMDB's dashboard, move calls behind the backend proxy, and redeploy before the old key is abused |
| Session codes found to be guessable/enumerable in production | MEDIUM | Increase code entropy/length, invalidate outstanding short codes if actively exploited, and add rate limiting on the join endpoint |

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|-------------------|---------------|
| In-memory state as source of truth | Foundational data/persistence phase | Kill the server mid-session locally; confirm session/votes/results survive and the app continues correctly on restart |
| Race condition in "everyone finished" check | Vote aggregation / matching logic phase | Automated or manual test firing two simultaneous "final vote" submissions for the same session; confirm exactly one result computation/broadcast occurs |
| Off-by-one / stale participant denominator | Session/participant lifecycle phase + vote aggregation phase | Test: join a new participant after others have already finished voting; confirm results wait for them (or confirm the explicit alternative product decision is implemented consistently) |
| Non-idempotent vote submission | Foundational data/persistence phase (schema constraint) + vote aggregation phase (upsert logic) | Submit the same vote twice (double-tap, resume-and-reswipe); confirm no duplicate counting and no crash |
| Missed WebSocket messages on reconnect | Real-time/WebSocket phase | Disconnect a client mid-wait (airplane mode / close tab), reconnect after results are ready elsewhere; confirm correct state appears without manual refresh |
| TMDB API key exposure | TMDB integration phase | Inspect frontend build output and browser network tab; confirm no TMDB key/token is ever visible client-side |
| TMDB rate limiting / caching staleness | TMDB integration phase | Confirm genre/provider/discover data is cached server-side with a TTL, not re-fetched from TMDB on every deck load |
| Session/participant identity without accounts | Session/join flow phase | Attempt to submit a vote or read status using a guessed display name without the corresponding server-issued token; confirm it's rejected |
| WebSocket scaling beyond one instance | Deployment/infrastructure phase (or explicitly deferred with documented limitation) | Confirm broker choice matches actual deployment instance count; if single-instance is the deliberate target, document it rather than leaving it undiscovered |

## Sources

- [Spring session/cache mechanics and multi-instance session loss](https://medium.com/@AlexanderObregon/the-mechanics-of-session-management-in-spring-boot-f7928e683867) — MEDIUM (community, cross-checked against Spring's own WebSocket scaling docs)
- [Spring Boot WebSocket: STOMP, Raw Handlers, Scaling](https://websocket.org/guides/frameworks/spring-boot/) — MEDIUM
- [ConcurrentHashMap eviction/TTL limitations](https://oneuptime.com/blog/post/2026-01-29-multi-level-caching-spring-boot/view) — LOW-MEDIUM (single blog source, but consistent with well-established Java collections behavior)
- [Database race conditions / lost update problem](https://medium.com/@C0l0red/database-race-conditions-f459d94ee2d0) — MEDIUM (concept cross-checked against multiple independent sources in the same search set)
- [Optimistic locking for concurrent updates](https://smarttechdevs.in/blog/laravel-optimistic-locking-database-concurrency) — MEDIUM (standard, widely-documented pattern, framework-agnostic)
- [TMDB rate limit discussions](https://www.themoviedb.org/talk/6558fa627f054018d5168d91) and [image CDN connection limits](https://www.themoviedb.org/talk/5c0e747d0e0a2638bc0c15c7) — LOW (official TMDB community forum, not formal documentation — treat specific numeric limits as indicative, verify against current TMDB API docs during the TMDB integration phase)
- [TMDB API overview / watch providers sourcing](https://github.com/api-evangelist/tmdb) — MEDIUM
- [WebSocket reconnection: state sync and recovery patterns](https://websocket.org/guides/reconnection/) — MEDIUM
- [STOMP client duplicate-message-on-reconnect issue](https://github.com/stomp-js/stompjs/issues/213) — MEDIUM (primary source: library's own issue tracker)
- [Spring WebSocket STOMP broker relay scaling with RabbitMQ](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/configuration-performance.html) — HIGH (official Spring documentation)
- [Backend-for-Frontend pattern for hiding API keys](https://blog.gitguardian.com/stop-leaking-api-keys-the-backend-for-frontend-bff-pattern-explained/) — MEDIUM
- [Idempotency keys / idempotent API design for preventing duplicate operations](https://cloud.google.com/discover/idempotency) — HIGH (official Google Cloud documentation, general pattern verified against multiple independent sources)
- General distributed-systems reasoning on stale/fixed denominators in group-completion checks — MEDIUM (first-principles software engineering knowledge, not tied to a single source; no MuviMatchr-specific or exact-match public post-mortem was found for this precise scenario)

---
*Pitfalls research for: swipe-based group-decision / multi-user async-voting app*
*Researched: 2026-09-01*
