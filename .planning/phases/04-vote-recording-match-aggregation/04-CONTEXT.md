# Phase 4: Vote Recording & Match Aggregation - Context

**Gathered:** 2026-09-04
**Status:** Ready for planning

<domain>
## Phase Boundary

Participants submit swipes (like/pass) on movies in their session's deck; every vote is persisted immediately and idempotently (upsert, never duplicated); "has everyone finished" and "what's the match" are always computed live from the persisted vote table — correct even when a session's roster or activity changes mid-vote. This phase delivers vote recording and match-aggregation logic only — no WebSocket push (Phase 5), no frontend swipe UI (Phase 6), no ranked-list/tie-break "best match" selection (Phase 6's RSLT-02 concern; this phase only needs to identify which movie(s), if any, are unanimous and expose per-movie like counts per RSLT-03).

</domain>

<decisions>
## Implementation Decisions

### Deck stability (pinning) & filter locking
- **D-01:** The specific list of movies for a session is pinned (snapshotted) the first time a deck is generated for that session — every participant, including late joiners, votes on the exact same fixed movie list for the lifetime of that session. Without this, "unanimous match" is meaningless: a late joiner or a Phase 3 cache TTL refresh (6h) could otherwise hand different participants different candidate movies. — **Reversibility:** costly — once real sessions have pinned decks, removing pinning changes what "match" has meant for every session created under this scheme.
- **D-02:** Region, streaming providers, and genre are all locked together at the same moment the deck is pinned — read-only for the rest of that session's life. User's mental model: all config is set collaboratively *before* deck creation; once the deck exists, nothing about it can change. This makes the earlier Phase 3 open question ("what happens if filters change after votes exist") moot — there is no window after pinning in which they *can* change.
- **D-03:** Genre must become a session-level field (locked alongside region/providers), not a per-request query parameter as it is today (`DeckController.kt` currently takes `genre` as `@RequestParam`, not stored on `Session`). Without this, two participants could already be swiping different genre-filtered decks before pinning is even considered. Exact storage shape (new `Session` column vs. embedding in the deck-pin record) is Claude's discretion.
- **D-04:** Deck pinning is triggered lazily — on whichever request first needs a deck for that session (matches the existing Phase 3 lazy-cache-on-miss pattern; no separate "start voting" action, consistent with Phase 2's no-host-role decision).

### Abandoned / inactive participants
- **D-05:** A participant who joined but stops voting is handled via an **inactivity timeout**, not "wait forever" and not a manual exclude/kick action (the latter would reintroduce a permission concept Phase 2 deliberately avoided — no host role). — **Reversibility:** reversible — purely a live computation rule, no schema/contract implications.
- **D-06:** Timeout is **1 minute since the participant's last vote** (or since joining, if they have cast zero votes yet). Confirmed against the actual deck design: a fixed ~20-card deck showing only poster/title/genre/providers (no trailers, no synopsis — nothing that invites lingering), so a 1-minute gap is a reasonable abandonment signal, not aggressive, for this app's "decide what to watch tonight" use case.
- **D-07:** While idle past the timeout, a participant drops out of **both** checks: the "has everyone finished" completion count, and the "liked by all" unanimity requirement for match computation — so the rest of the group is never blocked waiting on someone who's gone. This is recomputed live on every check, never a stored/sticky flag: casting a new vote immediately re-includes the participant in both checks again. Votes they already cast before going idle remain persisted and continue to count normally toward any movie's like tally (RSLT-03) — only the *requirement* that they must have liked a given movie for it to count as unanimous is dropped while they're idle.

### Vote editing (out of scope for v1)
- **D-08:** There is no "go back and change a vote" capability in v1 — once a participant swipes a movie, that choice is final from their perspective. This matches the roadmap's own v2 deferral of `VOTE-06` (undo last swipe). The upsert-on-conflict mechanism already built (`VoteRepository.upsertVote`, required by `VOTE-03`) remains purely an **idempotency safeguard** against duplicate submission of the *same* swipe action (e.g. a network retry), not a user-facing revise/undo feature. Because there is no revise path, "does re-voting reopen a finished/results state" is a non-issue — there is no way to trigger it.

### Claude's Discretion
- Exact storage shape for the pinned deck (movie ID list) and the now-session-scoped genre field — new columns/table vs. embedding in an existing structure — as long as it's queryable and survives restart like every other session field.
- The concurrency mechanism for the "has everyone finished" atomic check (optimistic locking with a version column vs. a single transactional/locked SQL count vs. `SELECT ... FOR UPDATE`) — flagged by prior research (`PITFALLS.md` Pitfall 2) as the highest-risk logic in this phase, given the two-concurrent-clients success criterion (ROADMAP Phase 4 success criterion 4). This is a technical implementation choice, not a product decision — left to research/planning, scoped per-session per `PITFALLS.md`'s guidance (never a broad table lock).
- Exact response shape/status codes for vote-submission and status endpoints — implementation detail.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements & roadmap
- `.planning/REQUIREMENTS.md` — VOTE-01 through VOTE-05, RSLT-03 (this phase's requirement set); `VOTE-06` (undo) confirmed v2-deferred, directly informing D-08
- `.planning/ROADMAP.md` §"Phase 4: Vote Recording & Match Aggregation" — goal, 5 success criteria (esp. #3 unanimity, #4 live-roster + concurrent-finish race), dependency on Phases 1-3
- `.planning/PROJECT.md` — Core Value, Active Requirements, Key Decisions table

### Prior phase context (for consistency)
- `.planning/phases/02-session-lobby-flow/02-CONTEXT.md` D-03, D-04 — no host/creator role; late joiners allowed anytime, no session lock — directly informs D-04 (lazy pinning, no "start voting" gate) and D-05 (no manual-exclude option)
- `.planning/phases/03-tmdb-integration-catalog-caching/03-CONTEXT.md` D-01, D-02, D-09, D-10 — region/provider are session-level, editable by any participant, at any time; explicitly deferred "what happens if filters change after votes exist" to this phase — resolved by D-01/D-02 (pin + lock, no post-pin edit window)

### Architecture & research
- `.planning/research/ARCHITECTURE.md` §"Package Structure" (`voting/` package: `VoteController.kt`, `VoteService.kt`, `MatchAggregationService.kt`), §"Pattern 1" (DB-as-source-of-truth aggregation), §"Pattern 2" (upsert-on-conflict vote writes), §"Pattern 3" (fresh-DB-read before broadcast — relevant to Phase 5, not this phase, but shapes how `MatchAggregationService` should be callable)
- `.planning/research/PITFALLS.md` §"Pitfall 2" (race condition in "has everyone finished") — directly informs the Claude's Discretion concurrency item above; §"Pitfall 3" (fixed participant count captured at session start breaks late-join/leave — do not do this); §Anti-Pattern table row "Broad table/row locking" — scope any lock to the specific session's rows only

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `Vote` entity, `VoteRepository.upsertVote` (native `ON CONFLICT ... DO UPDATE`, `src/main/kotlin/org/example/muvimatchr/voting/`) — already built in Phase 1, satisfies VOTE-02/VOTE-03's idempotent-write requirement as-is; this phase adds the service/controller layer on top, not a new persistence mechanism.
- `V3__create_vote.sql` — unique constraint `(session_id, participant_id, movie_id)` and index on `session_id` already in place.
- `Session` entity (`src/main/kotlin/org/example/muvimatchr/session/Session.kt`) — `region`/`providerIds` already `var` (mutable) fields per Phase 3; D-03 requires adding a `genre` field here (or an equivalent session-scoped store) plus a way to represent "deck is pinned" and the pinned movie ID list.
- `DeckController.kt` (`src/main/kotlin/org/example/muvimatchr/catalog/`) — currently accepts `genre` as `@RequestParam` (line ~30) and calls `movieCatalogService.getDeck(genre, session.providerIds, session.region)` fresh each time; this phase's pinning logic (D-01, D-04) needs to intercept this — first call resolves and stores the pinned deck, subsequent calls return the stored list regardless of query params.
- `PostgresTestSupport.kt` (Phase 1) — reusable Testcontainers Postgres fixture for any new repository/service tests.

### Established Patterns
- Flyway owns all DDL; `ddl-auto=validate` — new columns/tables (pinned deck, session genre, any inactivity-tracking fields) ship as new Flyway migrations (V8+), continuing the per-change migration pattern from Phases 1-3.
- Package-by-feature (`session/`, `voting/`, `catalog/`) — this phase's new logic belongs in `voting/` (`VoteController`, `VoteService`, `MatchAggregationService` per ARCHITECTURE.md), not new layer-based packages.
- Native queries use named `@Param` bindings exclusively (established by `VoteRepository.upsertVote`) — apply to any new unanimity/count queries.

### Integration Points
- `MovieVoteController.kt` (`src/main/kotlin/org/example/muvimatchr/controller/`) — legacy prototype voting logic (in-memory, the exact bug class this phase fixes per `ARCHITECTURE.md` Anti-Pattern 1). Not reused; superseded entirely by this phase's `voting/` package additions.
- `Participant` entity/repository (Phase 1/2) — already has a `createdAt` timestamp usable as the "joined at" basis for D-06's timeout when a participant has zero votes.

</code_context>

<specifics>
## Specific Ideas

No specific UI/UX references — this is a backend/data phase (swipe UI is Phase 6). The one concrete product mental model volunteered during discussion: configuration (region/provider/genre) happens collaboratively *before* the deck exists; the deck, once generated, is a frozen, shared artifact for that session — matches D-01/D-02 exactly.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope. (Vote-undo/revise was raised and explicitly resolved as *not* v1 scope — see D-08 — consistent with the already-existing `VOTE-06` v2 deferral in REQUIREMENTS.md, not a new deferral.)

</deferred>

---

*Phase: 4-Vote Recording & Match Aggregation*
*Context gathered: 2026-09-04*
