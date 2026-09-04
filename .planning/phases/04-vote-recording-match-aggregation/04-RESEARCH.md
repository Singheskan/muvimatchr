# Phase 4: Vote Recording & Match Aggregation - Research

**Researched:** 2026-09-04
**Domain:** Concurrent vote aggregation on Postgres via Spring Data JPA (Kotlin), deck-pinning schema design
**Confidence:** MEDIUM-HIGH (concurrency mechanism and query patterns are well-established, cross-checked against official Spring Data docs and independent sources; project-specific schema/integration recommendations are HIGH confidence — grounded in direct reads of this repo's existing code this session)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Deck stability (pinning) & filter locking**
- **D-01:** The specific list of movies for a session is pinned (snapshotted) the first time a deck is generated for that session — every participant, including late joiners, votes on the exact same fixed movie list for the lifetime of that session. Without this, "unanimous match" is meaningless: a late joiner or a Phase 3 cache TTL refresh (6h) could otherwise hand different participants different candidate movies. — **Reversibility:** costly — once real sessions have pinned decks, removing pinning changes what "match" has meant for every session created under this scheme.
- **D-02:** Region, streaming providers, and genre are all locked together at the same moment the deck is pinned — read-only for the rest of that session's life. User's mental model: all config is set collaboratively *before* deck creation; once the deck exists, nothing about it can change. This makes the earlier Phase 3 open question ("what happens if filters change after votes exist") moot — there is no window after pinning in which they *can* change.
- **D-03:** Genre must become a session-level field (locked alongside region/providers), not a per-request query parameter as it is today (`DeckController.kt` currently takes `genre` as `@RequestParam`, not stored on `Session`). Without this, two participants could already be swiping different genre-filtered decks before pinning is even considered. Exact storage shape (new `Session` column vs. embedding in the deck-pin record) is Claude's discretion.
- **D-04:** Deck pinning is triggered lazily — on whichever request first needs a deck for that session (matches the existing Phase 3 lazy-cache-on-miss pattern; no separate "start voting" action, consistent with Phase 2's no-host-role decision).

**Abandoned / inactive participants**
- **D-05:** A participant who joined but stops voting is handled via an **inactivity timeout**, not "wait forever" and not a manual exclude/kick action (the latter would reintroduce a permission concept Phase 2 deliberately avoided — no host role). — **Reversibility:** reversible — purely a live computation rule, no schema/contract implications.
- **D-06:** Timeout is **1 minute since the participant's last vote** (or since joining, if they have cast zero votes yet). Confirmed against the actual deck design: a fixed ~20-card deck showing only poster/title/genre/providers (no trailers, no synopsis — nothing that invites lingering), so a 1-minute gap is a reasonable abandonment signal, not aggressive, for this app's "decide what to watch tonight" use case.
- **D-07:** While idle past the timeout, a participant drops out of **both** checks: the "has everyone finished" completion count, and the "liked by all" unanimity requirement for match computation — so the rest of the group is never blocked waiting on someone who's gone. This is recomputed live on every check, never a stored/sticky flag: casting a new vote immediately re-includes the participant in both checks again. Votes they already cast before going idle remain persisted and continue to count normally toward any movie's like tally (RSLT-03) — only the *requirement* that they must have liked a given movie for it to count as unanimous is dropped while they're idle.

**Vote editing (out of scope for v1)**
- **D-08:** There is no "go back and change a vote" capability in v1 — once a participant swipes a movie, that choice is final from their perspective. This matches the roadmap's own v2 deferral of `VOTE-06` (undo last swipe). The upsert-on-conflict mechanism already built (`VoteRepository.upsertVote`, required by `VOTE-03`) remains purely an **idempotency safeguard** against duplicate submission of the *same* swipe action (e.g. a network retry), not a user-facing revise/undo feature. Because there is no revise path, "does re-voting reopen a finished/results state" is a non-issue — there is no way to trigger it.

### Claude's Discretion
- Exact storage shape for the pinned deck (movie ID list) and the now-session-scoped genre field — new columns/table vs. embedding in an existing structure — as long as it's queryable and survives restart like every other session field.
- The concurrency mechanism for the "has everyone finished" atomic check (optimistic locking with a version column vs. a single transactional/locked SQL count vs. `SELECT ... FOR UPDATE`) — flagged by prior research (`PITFALLS.md` Pitfall 2) as the highest-risk logic in this phase, given the two-concurrent-clients success criterion (ROADMAP Phase 4 success criterion 4). This is a technical implementation choice, not a product decision — left to research/planning, scoped per-session per `PITFALLS.md`'s guidance (never a broad table lock).
- Exact response shape/status codes for vote-submission and status endpoints — implementation detail.

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope. (Vote-undo/revise was raised and explicitly resolved as *not* v1 scope — see D-08 — consistent with the already-existing `VOTE-06` v2 deferral in REQUIREMENTS.md, not a new deferral.)
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| VOTE-01 | Participant swipes right (like) / left (pass) on each movie | Pattern 1's `VoteController`/`VoteService.recordVote` flow; existing `VoteRepository.upsertVote` reused as-is |
| VOTE-02 | Every vote is persisted immediately (survives disconnect/refresh/restart) | Already satisfied at the repository layer by Phase 1's `Vote` entity + Postgres persistence (`[VERIFIED: RestartSurvivalTest.kt exists]`); this phase's service/controller layer adds no new durability risk since it writes through the same `upsertVote` call |
| VOTE-03 | Re-voting on a movie updates the existing vote rather than duplicating it | Already proven at the repository layer (`VoteRepositoryTest.kt`, `[VERIFIED]`); D-08 confirms no new revise/undo UX is layered on top |
| VOTE-04 | A movie is a "match" only if every currently-joined participant liked it (unanimous) | Pattern 2's active-roster-scoped `GROUP BY movie_id HAVING COUNT(DISTINCT participant_id) = :activeCount` query |
| VOTE-05 | "Has everyone finished?" is computed live against currently-joined participants, not a snapshot from session start | Pattern 2's active-roster query (per-participant `is_active`/`is_finished` computed fresh on every call, D-06/D-07) |
| RSLT-03 | Vote data is stored per-movie (counts, not just a boolean) so a ranked list can be added later without a data model change | Pattern 2's dedicated per-movie like-count query, deliberately unscoped from the unanimity/active-roster filter |
</phase_requirements>

## Summary

This phase adds a `VoteController` → `VoteService` → `MatchAggregationService` stack on top of the already-existing, already-tested `Vote` entity and `VoteRepository.upsertVote` native upsert. The single highest-risk piece — explicitly flagged by `04-CONTEXT.md` and `PITFALLS.md` Pitfall 2 — is making "did this vote just complete the session" and "is movie X unanimous" atomic and race-free under two concurrent final-vote submissions.

**Primary recommendation:** Wrap vote recording in a single `@Transactional` service method that (1) first acquires a `SELECT id FROM session WHERE id = :sessionId FOR UPDATE` row lock (native query, one line, mirrors the existing native-query-with-`@Param` convention), (2) upserts the vote via the existing `VoteRepository.upsertVote`, then (3) runs the live completion/unanimity queries — all inside that one locked transaction. This serializes concurrent finishers *for the same session only* (never a broader lock, per `PITFALLS.md`'s anti-pattern table), and because Postgres re-evaluates locked-row visibility per statement under READ COMMITTED, the second transaction to acquire the lock is guaranteed to see the first transaction's already-committed vote. Do **not** use `@Version` optimistic locking for this check — the completion condition is a cross-row aggregate (over `vote`/`participant`, not a single `Session` field), so optimistic locking would require touching `Session` on every vote purely to get a version bump, adding an `OptimisticLockException`-retry loop for no correctness benefit over the simpler row lock at this project's contention level (single small deployment, small session sizes).

For the unanimous-match query, extend `04-CONTEXT.md`'s D-07 (live, non-sticky, activity-scoped roster) into a native query that computes an "active" participant set (last vote — or join time if zero votes — within 1 minute) and joins it against a `GROUP BY movie_id HAVING COUNT(DISTINCT participant_id) = :activeCount` — directly extending `ARCHITECTURE.md`'s Pattern 1, scoped to the live active roster instead of the full roster.

For schema, extend `Session` directly (not a new join table) with three nullable columns — `genre INT`, `pinned_deck JSONB`, `deck_pinned_at TIMESTAMPTZ` — added in a new `V8` Flyway migration. Storing the **full pinned movie snapshot** (not just an ID list) in `pinned_deck` is recommended over an ID-only list: it reuses `DeckCacheEntry`'s exact JSONB + `ObjectMapper` serialization pattern already proven in this codebase, and it directly closes the gap `04-CONTEXT.md`'s own D-01 rationale calls out — a Phase 3 cache TTL refresh must never change what a mid-session participant sees, which an ID-only approach re-opens (looking up "current" movie details for an old ID could still return TMDB data that has since changed).

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Vote submission (record like/pass) | API / Backend (`voting/VoteController`, `VoteService`) | Database / Storage (`vote` table, existing unique constraint) | Business rule (idempotent upsert) lives in the service; durability lives in Postgres — matches `ARCHITECTURE.md`'s existing `voting/` package split |
| "Has everyone finished" computation | API / Backend (`voting/MatchAggregationService`) | Database / Storage (live aggregate query) | Pure read-model over persisted state per `ARCHITECTURE.md` Pattern 1 — no in-memory counters, no JVM-held state |
| "Is movie X a match" computation | API / Backend (`voting/MatchAggregationService`) | Database / Storage (live `GROUP BY`/`HAVING` query) | Same as above; RSLT-03's per-movie counts and VOTE-04's unanimity check are two projections of the same underlying query family |
| Deck pinning (snapshot movie list + lock filters) | API / Backend (`catalog/DeckController`, `session/SessionService`) | Database / Storage (`session.pinned_deck`, `session.deck_pinned_at`) | Pinning is a one-time state transition triggered by an existing request path (`GET .../deck`), not a new tier boundary — stays in the packages that already own deck-serving and session mutation |
| Concurrency control for the completion race | Database / Storage (Postgres row lock) | API / Backend (`@Transactional` boundary) | The lock itself is a Postgres primitive (`SELECT ... FOR UPDATE`); the transaction boundary that holds it is owned by the Spring service layer — this is the one capability where DB and backend are genuinely co-owners, not primary/secondary |

## Standard Stack

No new external libraries are required for this phase. Every mechanism below (`SELECT ... FOR UPDATE`, `GROUP BY ... HAVING`, JSONB columns, Flyway migrations) uses dependencies already present in `build.gradle.kts` — `spring-boot-starter-data-jpa`, `flyway-database-postgresql`, `org.postgresql:postgresql` — all `[VERIFIED: build.gradle.kts]` (read this session).

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.springframework.boot:spring-boot-starter-data-jpa` | already present | Repository layer, native `@Query`/`@Param` bindings, `@Transactional` | Already used identically by `VoteRepository`/`DeckCacheRepository` `[VERIFIED: VoteRepository.kt, DeckCacheRepository.kt]` |
| `org.flywaydb:flyway-database-postgresql` | already present | New `V8` migration for `session` schema changes | Existing per-change migration convention (`V1`–`V7`) `[VERIFIED: src/main/resources/db/migration/]` |
| `org.postgresql:postgresql` (JDBC driver) | already present | Native SQL execution incl. `FOR UPDATE` | No new driver capability needed — `FOR UPDATE` is standard SQL |

### Supporting
None — no new supporting libraries needed.

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `SELECT ... FOR UPDATE` on the `session` row | `pg_advisory_xact_lock(hashtext(session_id::text))` | Advisory lock avoids touching the `session` row at all, useful if you don't already read/write it in the same transaction — but this phase's flow already needs to read `session` (for `pinned_deck`/`deck_pinned_at`), so the row lock is strictly simpler and needs no extra Postgres function call |
| `SELECT ... FOR UPDATE` | `@Version` optimistic locking on `Session` | Simpler code per single-row update, but doesn't fit a cross-row aggregate check (see Summary) and adds retry-loop complexity with no correctness gain here |
| Full pinned-movie JSONB snapshot | ID-only list + re-resolve details on each deck read | Fewer bytes stored, but reopens the exact "TTL refresh changes what participants see" risk D-01 exists to close; requires building a new "fetch movies by ID list" path that doesn't currently exist anywhere in `catalog/` |
| CSV-VARCHAR + `AttributeConverter` for any array-like field | Native Postgres `BIGINT[]`/`INT[]` column with GIN index | Native arrays are more idiomatic Postgres and indexable, but this codebase has an explicit, documented precedent (`IntListConverter.kt`) choosing CSV-VARCHAR over arrays/JSONB "to keep the schema to simple column types" `[VERIFIED: IntListConverter.kt:6-9]` — the pinned deck is a full-object JSONB anyway (see below), so this tradeoff only applies if a bare movie-ID list is chosen instead |

**Installation:** None required — no `build.gradle.kts` changes.

## Package Legitimacy Audit

Not applicable — this phase introduces no new external packages. No `package-legitimacy check` run was needed.

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### System Architecture Diagram

```
Client submits swipe
    │
    ▼ POST /api/sessions/{id}/votes  { movieId, choice }  (Authorization: Bearer <token>)
┌─────────────────────────────────────────────────────────────────┐
│  VoteController (voting/)                                        │
│  - Resolves @CurrentParticipant from bearer token (existing       │
│    CurrentParticipantArgumentResolver, unchanged)                 │
│  - Verifies participant.session.id == path sessionId               │
│  - Verifies movieId ∈ session.pinnedDeck (else 400)                │
└───────────────────────────┬─────────────────────────────────────┘
                             ▼ calls, inside one @Transactional method
┌─────────────────────────────────────────────────────────────────┐
│  VoteService.recordVote()                                          │
│  1. sessionRepository.lockForUpdate(sessionId)   -- SELECT ...     │
│     FOR UPDATE, session-row-scoped                                 │
│  2. voteRepository.upsertVote(...)               -- existing        │
│     native ON CONFLICT DO UPDATE                                    │
│  3. matchAggregationService.computeStatus(sessionId) -- live read   │
│     (still inside the same locked transaction)                      │
└───────────────────────────┬─────────────────────────────────────┘
                             ▼ reads, same transaction
┌─────────────────────────────────────────────────────────────────┐
│  MatchAggregationService (voting/)                                  │
│  - activeParticipants(sessionId): last-vote-or-join within 1 min     │
│  - isEveryoneFinished: every active participant's vote count           │
│    == pinned deck size                                                  │
│  - unanimousMovies: GROUP BY movie_id HAVING COUNT(DISTINCT             │
│    participant_id) = activeCount, scoped to LIKE + active participants │
│  - perMovieLikeCounts (RSLT-03): GROUP BY movie_id, all participants,   │
│    no unanimity filter -- separate query, no activity/roster filter     │
└───────────────────────────┬─────────────────────────────────────┘
                             ▼
                    vote / participant / session tables (Postgres)
                    -- sole source of truth, no in-memory aggregation
```

### Recommended Project Structure
```
src/main/kotlin/org/example/muvimatchr/voting/
├── Vote.kt                      # existing, unchanged
├── VoteRepository.kt            # existing upsertVote + new native aggregate queries
├── VoteController.kt            # new: POST .../votes, GET .../votes/status
├── VoteService.kt               # new: recordVote() -- owns the @Transactional + lock boundary
└── MatchAggregationService.kt   # new: pure read-model, no mutable state (per ARCHITECTURE.md)
```

### Pattern 1: Session-scoped pessimistic lock around the completion check

**What:** A native `SELECT id FROM session WHERE id = :sessionId FOR UPDATE` issued as the *first* statement inside the same `@Transactional` method that upserts the vote and computes completion status.
**When to use:** Every vote-submission call. Never widen the lock beyond one session's row (`PITFALLS.md` anti-pattern table: "broad table/row locking... blocks unrelated sessions").
**Why FOR UPDATE and not `@Lock(LockModeType.PESSIMISTIC_WRITE)`:** Spring Data JPA's `@Lock` annotation is only honored on JPQL-derived or `@Query`-with-JPQL methods — it is **not applied to native queries** `[CITED: multiple independent sources cross-checking the same limitation, e.g. devonfw/devon4j#478 discussion and Baeldung's transaction-locks guide]`. Since this repo's established convention is native `@Param`-bound queries (`VoteRepository.upsertVote`, `DeckCacheRepository.upsertDeck`, both `[VERIFIED]` this session), the lock must be written directly into the native SQL text (`... FOR UPDATE`), not via the annotation.

**Example:**
```kotlin
// SessionRepository.kt -- new method, following the existing native-query-with-@Param style
@Query(value = "SELECT id FROM session WHERE id = CAST(:sessionId AS uuid) FOR UPDATE", nativeQuery = true)
fun lockForUpdate(@Param("sessionId") sessionId: UUID): UUID?
```
```kotlin
// VoteService.kt -- new
@Service
class VoteService(
    private val sessionRepository: SessionRepository,
    private val voteRepository: VoteRepository,
    private val matchAggregationService: MatchAggregationService,
) {
    @Transactional
    fun recordVote(sessionId: UUID, participantId: UUID, movieId: Long, choice: VoteChoice): SessionStatus {
        // Every concurrent recordVote() call for THIS session blocks here until the previous
        // call's transaction commits/rolls back. Calls for other sessions are unaffected --
        // this is a row lock, not a table lock.
        sessionRepository.lockForUpdate(sessionId)

        voteRepository.upsertVote(UUID.randomUUID(), sessionId, participantId, movieId, choice.name)

        // Runs inside the still-open, still-locked transaction: the SECOND concurrent caller's
        // read here is guaranteed to see the FIRST caller's already-committed vote (Postgres
        // re-evaluates visibility per-statement under READ COMMITTED once the lock is released).
        return matchAggregationService.computeStatus(sessionId)
    }
}
```
**Source:** `[CITED: multiple independent sources — Baeldung's transaction-locks guide + devonfw/devon4j#478 issue discussion — both confirming the same native-query/`@Lock` limitation]` + `[VERIFIED: VoteRepository.kt, DeckCacheRepository.kt]` (confirms this repo's existing native-query-with-`@Param` convention this pattern extends). A single-fetch check of the official `docs.spring.io` locking reference page confirmed `@Lock` exists as an annotation but did not itself cover the native-query limitation in the excerpt retrieved — that single, non-cross-verified fetch is `[ASSUMED]`-tier per this session's confidence classification and is not relied on alone for the native-query claim above.

**Critical caveat — the lock only holds if the whole sequence shares one transaction:** if `recordVote`'s three steps are not wrapped in a single `@Transactional` boundary, each repository call opens and commits its own transaction (Spring Data JPA repository method default), and the `FOR UPDATE` lock is released the instant the lock statement's own auto-committed transaction ends — before the vote upsert even runs. This defeats the entire mechanism silently (no error, just an unlocked window) `[ASSUMED — standard JPA/Spring transaction-propagation behavior, not itself found in a single dedicated source, but consistent with documented `@Transactional` propagation semantics]`.

### Pattern 2: Live active-roster query (D-06/D-07)

**What:** A native query computing, per participant, whether they count as "active" (last vote — or `created_at` if zero votes — within 1 minute) before applying either completion or unanimity logic. Recomputed fresh on every call; nothing is cached or sticky, per D-07.
**When to use:** Both the "has everyone finished" check and the "is movie X unanimous" check — they share the same active-roster subquery, differing only in what they count once the roster is scoped.

**Example:**
```sql
-- Active roster + per-participant finished flag, in one pass.
-- :deckSize is the pinned deck's movie count, passed in by the caller (Session.pinnedDeck.size)
-- rather than computed in SQL -- keeps this query a plain COUNT/JOIN, no JSON-array-length logic.
SELECT
    p.id AS participant_id,
    (lv.last_voted_at IS NOT NULL AND lv.last_voted_at > now() - interval '1 minute')
        OR (lv.last_voted_at IS NULL AND p.created_at > now() - interval '1 minute') AS is_active,
    (COALESCE(lv.vote_count, 0) = :deckSize) AS is_finished
FROM participant p
LEFT JOIN LATERAL (
    SELECT MAX(v.voted_at) AS last_voted_at, COUNT(*) AS vote_count
    FROM vote v
    WHERE v.participant_id = p.id AND v.session_id = CAST(:sessionId AS uuid)
) lv ON true
WHERE p.session_id = CAST(:sessionId AS uuid)
```
```sql
-- Unanimous match query -- extends ARCHITECTURE.md Pattern 1's
-- "GROUP BY movie_id HAVING COUNT(DISTINCT participant_id) = total" to the ACTIVE roster only.
-- :activeParticipantCount is computed from the query above (or an equivalent scalar subquery).
SELECT v.movie_id
FROM vote v
WHERE v.session_id = CAST(:sessionId AS uuid)
  AND v.choice = 'LIKE'
  AND v.participant_id IN (:activeParticipantIds)
GROUP BY v.movie_id
HAVING COUNT(DISTINCT v.participant_id) = :activeParticipantCount
```
```sql
-- RSLT-03: per-movie like counts, deliberately NOT scoped to the active roster or to unanimity --
-- this is the "supports a future ranked list without a schema change" query. Idle participants'
-- existing votes still count here per D-07 ("votes they already cast... continue to count
-- normally toward any movie's like tally").
SELECT v.movie_id, COUNT(*) AS like_count
FROM vote v
WHERE v.session_id = CAST(:sessionId AS uuid) AND v.choice = 'LIKE'
GROUP BY v.movie_id
ORDER BY like_count DESC
```
**Source:** `[ASSUMED — the GROUP BY/HAVING/COUNT(DISTINCT) aggregate shape rests on a single, non-cross-verified WebSearch call this session plus standard SQL training knowledge; it is standard textbook SQL, not project-specific, but is not independently re-verified here]`. Roster/activity logic is a direct implementation of D-06/D-07, both `[VERIFIED: 04-CONTEXT.md:24-25]` (quoted: *"Timeout is 1 minute since the participant's last vote (or since joining, if they have cast zero votes yet)"* and *"drops out of both checks... recomputed live on every check, never a stored/sticky flag"*).

### Pattern 3: Deck pinning as a side effect of the existing deck-read path (D-04)

**What:** `DeckController.getDeck` — already reading `session` and calling `movieCatalogService.getDeck(...)` on every request — gains a branch: if `session.deckPinnedAt == null`, resolve the deck as today, persist the full result (movies + genre) onto the session row, and set `deckPinnedAt`; if already pinned, skip `MovieCatalogService` entirely and return the stored snapshot, ignoring the incoming `genre` query parameter.
**When to use:** This endpoint only — no new "start voting" endpoint should be added (per D-04's explicit "no separate start voting action", consistent with Phase 2's no-host-role decision, both `[VERIFIED: 04-CONTEXT.md:20]`).

**Example (illustrative shape, not exhaustive):**
```kotlin
fun getDeck(sessionId: UUID, genre: Int?, participant: Participant): DeckResponse {
    val session = sessionRepository.findById(sessionId).orElseThrow { /* 404 */ }
    if (session.deckPinnedAt != null) {
        // Already pinned: session.pinnedDeck + session.genre are the only source of truth from
        // here on. The incoming `genre` param is intentionally ignored -- see D-03/D-02's "read-only
        // afterward" and the existing precedent in this exact file for region/providerIds
        // ("Region and provider selection come only from the session row -- never from a request
        // parameter", DeckController.kt:45-50).
        return DeckResponse.from(session)
    }
    catalogReferenceService.requireKnownGenre(genre)   // existing validation, reused as-is
    val result = movieCatalogService.getDeck(genre, session.providerIds, session.region)
    sessionService.pinDeck(sessionId, genre, result.movies)   // new: sets pinnedDeck + genre + deckPinnedAt
    return DeckResponse.from(result)
}
```
**Source:** `[VERIFIED: DeckController.kt:27-93]` (existing method this pattern modifies, read in full this session) + `[VERIFIED: 04-CONTEXT.md:20]` (D-04 lazy-pin trigger).

### Anti-Patterns to Avoid

- **Locking the whole `vote` or `participant` table (or using an unscoped advisory lock key):** Blocks unrelated sessions' vote submissions for no reason. `PITFALLS.md`'s Anti-Pattern table names this directly: *"Broad table/row locking... Vote submissions from unrelated sessions block each other... Scope any lock/transactional check to the specific session's rows only."* `[VERIFIED: PITFALLS.md:163]`.
- **Fixed/cached participant or deck-size counts:** Per Pitfall 3, do not snapshot `totalParticipants` at session start, and do not snapshot "deck size" anywhere except as the length of the one-time pinned deck (which itself is intentionally frozen — this is a deliberate exception the roadmap explicitly carves out, not a contradiction of Pitfall 3, since the *deck* pinning once is a product decision (D-01) while the *participant roster* must stay live per VOTE-05).
- **Computing "unanimous" over the full roster instead of the active roster:** Silently reintroduces the exact block-forever failure D-05/D-07 exist to prevent — an abandoned participant's absence from the active set must exclude them from both denominators, not just the "finished" one.
- **Relying on `@Lock` with `nativeQuery = true`:** Silently ignored by Spring Data JPA — the lock never actually applies, and there is no compile-time or obvious runtime signal that it didn't `[CITED: cross-verified sources, see Pattern 1 above]`.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Atomic "did I just complete the session" detection | A custom in-JVM counter/flag (`ConcurrentHashMap`, `AtomicInteger` per session) | Postgres `SELECT ... FOR UPDATE` + a live aggregate query | This is Anti-Pattern 1 from `ARCHITECTURE.md`, restated: any JVM-held counter reintroduces the exact "lost on restart / wrong across instances" failure mode this whole rebuild exists to fix `[VERIFIED: ARCHITECTURE.md:229-233]` |
| Fetching "current" movie details for a pinned deck | A new `MovieCatalogService.getMoviesByIds(...)` lookup path against the TTL-driven cache | A full-object JSONB snapshot taken once at pin time (mirrors `DeckCacheEntry.moviesJson`) | Building a by-ID lookup against a cache whose entries expire and get re-fetched on a 6h TTL reopens exactly the risk D-01 exists to close; a point-in-time snapshot needs no new lookup logic at all |
| Serializing the pinned deck to a column | Hand-rolled string/CSV movie encoding | `@JdbcTypeCode(SqlTypes.JSON)` + `ObjectMapper`, exactly as `DeckCacheEntry.kt` already does | Directly copy the proven pattern — same Hibernate/Jackson version, same `jsonb` column type, zero new serialization code to write or test |

**Key insight:** Every "don't hand-roll" item in this phase has an exact precedent already merged into this repo from Phase 1 or Phase 3. The correct move in each case is to extend an existing pattern one level, not invent a new one.

## Common Pitfalls

### Pitfall A: `@Lock` silently no-ops on native queries
**What goes wrong:** A developer adds `@Lock(LockModeType.PESSIMISTIC_WRITE)` above a `@Query(nativeQuery = true)` method expecting it to add `FOR UPDATE` semantics. It compiles, runs, and returns rows normally — with no actual row lock ever acquired.
**Why it happens:** `@Lock` is implemented at the JPQL-translation layer; native SQL bypasses that layer entirely `[CITED: cross-verified — Baeldung's transaction-locks guide and the devonfw/devon4j#478 issue discussion independently confirm the same limitation]`.
**How to avoid:** Write `FOR UPDATE` directly into the native SQL string (see Pattern 1).
**Warning signs:** A "concurrency-safe" test passes even when the `@Lock` annotation is commented out — that's the tell that it was never doing anything.

### Pitfall B: Lock acquired but transaction boundary too narrow
**What goes wrong:** The `FOR UPDATE` query runs in its own auto-committed transaction (no enclosing `@Transactional`), releasing the lock before the vote upsert that was supposed to be protected by it even executes.
**Why it happens:** Spring Data JPA repository methods are individually transactional by default (`REQUIRED` propagation) unless called from within an already-open transaction — exactly the same class of subtlety already documented in this repo's own `SessionService.createSession` comment about *not* wrapping a retry loop in `@Transactional` `[VERIFIED: SessionService.kt:21-24]` (the inverse mistake to the one this pitfall describes, but the same underlying propagation mechanic).
**How to avoid:** Put `@Transactional` on the *service* method that calls lock → upsert → aggregate-read, not on the repository methods individually (they already inherit the enclosing transaction once one exists).
**Warning signs:** A concurrency integration test is flaky (passes most runs, occasionally shows a stale read) rather than reliably correct or reliably wrong — a classic symptom of a lock that's held for a shorter window than intended.

### Pitfall C: Testcontainers + concurrent-thread integration test starves the connection pool
**What goes wrong:** A two-concurrent-clients test spins up two threads each calling `recordVote` through the full Spring context. If the test's HikariCP pool is sized at 1 (or the default is exhausted by other concurrently-running tests via the shared singleton container pattern this repo already uses, `[VERIFIED: PostgresTestSupport.kt]`), the second thread can block waiting for a *connection*, not for the row lock — the test then "passes" for the wrong reason (serialization happened at the pool level, not the DB-lock level it's meant to verify), or hangs if the pool is undersized relative to the thread count.
**Why it happens:** Spring Boot's default HikariCP `maximumPoolSize` is 10, generally enough headroom for a 2-thread test, but this is easy to shrink inadvertently in test config, and the effect (pool exhaustion vs. row-lock contention) is indistinguishable from the outside without deliberately checking `[ASSUMED — HikariCP's default pool size is well-documented general knowledge, not verified against this repo's specific (absent) test datasource config this session; no `application-test.properties`/Hikari override was found in `src/test/resources`]`.
**How to avoid:** In the concurrency test, explicitly assert the pool has at least `N+1` connections available for `N` concurrent test threads, or use two independently-constructed `RestTemplate`/`MockMvc` calls with real thread parallelism (`ExecutorService`, `CountDownLatch` to synchronize the "fire both at once" moment) and confirm via a third assertion (e.g., a `pg_stat_activity` check for a `FOR UPDATE` wait state) that the serialization actually happened inside Postgres, not the pool.
**Warning signs:** The concurrency test only exercises `N=2` and passes even after the `FOR UPDATE` clause is deliberately removed — that means the pool (or JUnit's own thread scheduling) is accidentally serializing the calls, not the lock.

### Pitfall D: `deckSize` mismatch after pinning
**What goes wrong:** "Has everyone finished" is computed by comparing a participant's vote count to a hardcoded or re-derived deck size (e.g., re-querying `MovieCatalogService` fresh), which can silently diverge from the actual pinned list if the pinning write and the deck-size read aren't sourced from the exact same `session.pinnedDeck` value.
**Why it happens:** Deck size is derived data (`pinnedDeck.size`), not stored as its own column — a second, independent computation of "how many movies are in the deck" is one place this could quietly drift from the frozen list.
**How to avoid:** Always derive `deckSize` from `session.pinnedDeck.size` (or from `session.pinnedMovieIds.size` if the ID-only variant is chosen instead) at the point of use, never from a separate query against `MovieCatalogService`/TMDB.
**Warning signs:** A completion check that behaves correctly for the first session tested but is off-by-N once TMDB's `/discover` result count changes between test runs.

## Code Examples

### New Flyway migration (V8)
```sql
-- V8__add_session_deck_pin_and_genre.sql
-- Nullable by design: null deck_pinned_at is the "not yet pinned" state (D-04's lazy trigger);
-- null genre means "no genre filter was set at pin time" (same nullable-optional-filter shape
-- CatalogReferenceService.requireKnownGenre(genreId: Int?) already expects).
ALTER TABLE session ADD COLUMN genre INT;
ALTER TABLE session ADD COLUMN pinned_deck JSONB;
ALTER TABLE session ADD COLUMN deck_pinned_at TIMESTAMPTZ;
```
Source pattern: `[VERIFIED: V6__add_session_region_and_providers.sql]` (plain `ALTER TABLE ... ADD COLUMN`, no default needed for a nullable column) and `[VERIFIED: V5__create_deck_cache_entry.sql]` (`movies JSONB NOT NULL` column precedent — here `NULL`-able since it's absent pre-pin).

### `Session.kt` entity additions
```kotlin
// Mirrors DeckCacheEntry.kt's exact JSONB pattern -- same Hibernate JdbcTypeCode, same nullability
// shape (deck_cache_entry.movies is NOT NULL because a cache row only exists once fetched; here
// pinnedDeck is nullable because the Session row exists BEFORE the deck does).
@Column(name = "genre")
var genre: Int? = null,

@Column(name = "pinned_deck", columnDefinition = "jsonb")
@JdbcTypeCode(SqlTypes.JSON)
var pinnedDeckJson: String? = null,

@Column(name = "deck_pinned_at")
var deckPinnedAt: Instant? = null,
```
Source: `[VERIFIED: DeckCacheEntry.kt:25-27]` (exact annotation combination this reuses).

### D-02 enforcement point in `SessionService.replaceFilters`
```kotlin
@Transactional
fun replaceFilters(sessionId: UUID, region: String?, providerIds: List<Int>): Session {
    val session = sessionRepository.findById(sessionId)
        .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No session with id $sessionId") }
    // D-02: region/providers/genre are locked together the moment the deck is pinned -- there is
    // no window after pinning in which any of the three can change (04-CONTEXT.md D-02).
    if (session.deckPinnedAt != null) {
        throw ResponseStatusException(HttpStatus.CONFLICT, "Session filters are locked once the deck is pinned")
    }
    session.region = region ?: DEFAULT_REGION
    session.providerIds = providerIds
    return sessionRepository.save(session)
}
```
Source: `[VERIFIED: SessionService.kt:51-58]` (exact existing method this modifies, read in full this session — the `if` block above is the only new logic; everything else is unchanged from the current file).

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| N/A — no prior voting/aggregation implementation exists in this phase's scope (the legacy `MovieVoteController.kt` in-memory prototype predates this rebuild and is explicitly not reused, per `04-CONTEXT.md`'s Integration Points section) | N/A | — | — |

No "state of the art" drift applies here — this is greenfield logic within an already-modern stack (Spring Boot 4.1.1, Hibernate via Spring Data JPA, Postgres 18). No deprecated API usage risk identified.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Spring Data JPA repository methods called without an enclosing `@Transactional` each open/commit their own transaction, releasing any `FOR UPDATE` lock immediately | Pattern 1 / Pitfall B | If wrong, the specific "why this bug happens" explanation is inaccurate, though the *fix* (wrap the sequence in one `@Transactional` service method) is correct regardless — this is standard, widely-documented Spring transaction propagation behavior, not project-specific |
| A2 | HikariCP's default `maximumPoolSize` (10) is unmodified in this project's test configuration | Pitfall C | If a test-scoped override reduces the pool below the concurrency test's thread count, the test could hang or falsely "pass" for pool-contention reasons rather than lock-contention reasons — no `application-test.properties`/Hikari override file was found in `src/test/resources` this session, but its absence doesn't prove Boot's autoconfigured default is what's actually in effect at test time |
| A3 | Storing the full pinned movie snapshot (not just an ID list) in `pinned_deck` is the better default given D-01's stated rationale | Summary / Don't Hand-Roll | This is Claude's-discretion territory per `04-CONTEXT.md` explicitly — if the planner disagrees and picks ID-only, they'll need to also design a "fetch movie details by ID, insulated from TTL refresh" mechanism this research does not otherwise cover |
| A4 | The `@Lock` annotation is not honored on `@Query(nativeQuery = true)` methods in the Spring Data JPA version this project uses (Spring Boot 4.1.1 / Spring Data JPA parent 4.x) | Pattern 1 / Anti-Patterns / Pitfall A | Cross-verified across two independent community sources (Baeldung, devon4j issue tracker) rather than the project's own official-docs version, and not empirically confirmed against this exact Spring Data JPA version — if wrong, the recommendation to write `FOR UPDATE` directly into native SQL is still safe (it works regardless of whether `@Lock` would also have worked), so the risk is redundancy, not incorrectness |
| A5 | Postgres `GROUP BY ... HAVING COUNT(DISTINCT ...)` and `SELECT ... FOR UPDATE` semantics (READ COMMITTED per-statement visibility, lock-then-read ordering) behave as described against Postgres 18 specifically | Pattern 1, Pattern 2 | These are longstanding, version-stable Postgres behaviors (not new in Postgres 18), so risk is low, but this was not independently re-verified against a Postgres 18 changelog this session — `[VERIFIED: PostgresTestSupport.kt]` only confirms the test container image version, not that this specific locking behavior was checked against it |

## Open Questions

1. **Should the "everyone finished" transition itself be recorded as a persisted, exactly-once event (e.g. a `completed_at` timestamp set via a guarded `UPDATE ... WHERE completed_at IS NULL`), or is a purely computed-on-read status sufficient for this phase?**
   - What we know: `MatchAggregationService`'s completion/unanimity checks are pure, side-effect-free reads (per D-07, "recomputed live... never a stored/sticky flag"). Running them twice concurrently is not itself incorrect — both concurrent requests will read consistent state once the `FOR UPDATE` lock serializes the underlying writes.
   - What's unclear: The phase's own success criterion 4 uses the phrase "triggers match computation exactly once," which could imply an expectation of a discrete, loggable/auditable transition event, not just consistent read results.
   - Recommendation: Treat the row-lock + live-read approach above as sufficient to satisfy the *stated* success criterion (consistent, race-free reads under concurrent final votes) without adding a stored completion-timestamp column. If the planner or a later verification pass wants an explicit single-writer-wins signal (useful groundwork for Phase 5's WebSocket broadcast trigger), the guarded `UPDATE session SET completed_at = now() WHERE id = :id AND completed_at IS NULL` pattern — executed inside the same already-locked transaction — is a low-cost addition that can be deferred to Phase 5 without any schema rework now (it would just be one more nullable column).

2. **Exact response shape for a vote-status/"waiting on N of M" endpoint.**
   - What we know: `04-CONTEXT.md` explicitly defers "exact response shape/status codes for vote-submission and status endpoints" to Claude's discretion.
   - What's unclear: Whether Phase 4 needs its own dedicated `GET .../status` endpoint at all, or whether the vote-submission response body alone (returning the freshly computed status) is sufficient until Phase 5 adds the WebSocket layer and RTIME-01/RTIME-03's polling fallback.
   - Recommendation: Add a `GET /api/sessions/{id}/votes/status` endpoint now (cheap, and RTIME-03's "reconnecting participant always sees correct current status" will need exactly this REST GET per `ARCHITECTURE.md` Pattern 4) returning `{ finishedCount, activeCount, isComplete, matchedMovieIds }` — but this is a planning-level decision, not a blocking research gap.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker (for Testcontainers Postgres) | Any repository/integration test touching `session`/`vote` tables, incl. the required two-concurrent-clients test | ✓ | Docker CLI present; daemon reachable directly in this session's probe | `DOCKER_HOST=unix:///Users/psrg/.colima/default/docker.sock` also confirmed reachable, matching `STATE.md`'s documented Colima quirk `[VERIFIED: STATE.md:111]` (quoted: *"Any test touching Testcontainers additionally needs `DOCKER_HOST=unix:///Users/psrg/.colima/default/docker.sock`... and `TESTCONTAINERS_RYUK_DISABLED=true`"*) |
| `JAVA_HOME` pointed at JDK 21 | `./gradlew` (Gradle 8.14.3's daemon) | Requires manual export | corretto-21.0.4 | `STATE.md`'s documented value: `JAVA_HOME=/Users/psrg/Library/Java/JavaVirtualMachines/corretto-21.0.4/Contents/Home` `[VERIFIED: STATE.md:111]` — this session's default `java -version` resolved to JDK 25, confirming the mismatch is still present |
| `psql` CLI | Not required — all schema inspection in this phase happens through JPA/Flyway, not manual `psql` sessions | ✗ | — | Not needed; no fallback required |

**Missing dependencies with no fallback:** none.
**Missing dependencies with fallback:** `JAVA_HOME`/`DOCKER_HOST`/`TESTCONTAINERS_RYUK_DISABLED` all have documented, working fallback exports already established in `STATE.md` from prior phases — carry them forward for this phase's test runs too.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (`kotlin-test-junit5`) + Spring Boot Test + Testcontainers, all `[VERIFIED: build.gradle.kts]` |
| Config file | none dedicated — `PostgresTestSupport.kt` base class (`[VERIFIED: src/test/kotlin/org/example/muvimatchr/support/PostgresTestSupport.kt]`), singleton-container pattern |
| Quick run command | `./gradlew test --tests "org.example.muvimatchr.voting.*"` |
| Full suite command | `./gradlew test` (with `JAVA_HOME`/`DOCKER_HOST`/`TESTCONTAINERS_RYUK_DISABLED` exported per Environment Availability above) |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| VOTE-01 | Participant submits like/pass, vote persisted | integration (Testcontainers) | `./gradlew test --tests "*VoteControllerTest*"` | ❌ Wave 0 |
| VOTE-02 | Vote survives restart | integration, extends existing `RestartSurvivalTest` pattern | `./gradlew test --tests "*RestartSurvivalTest*"` | ✅ (extend existing file, `[VERIFIED: src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt]` exists from Phase 1) |
| VOTE-03 | Re-vote updates in place, no duplicate | unit/integration | `./gradlew test --tests "*VoteRepositoryTest*"` | ✅ already covers this for the repository layer `[VERIFIED: VoteRepositoryTest.kt]` — new tests needed only at the service/controller layer |
| VOTE-04 | Unanimous-only match | integration (Testcontainers) | `./gradlew test --tests "*MatchAggregationServiceTest*"` | ❌ Wave 0 |
| VOTE-05 | Live roster, late joiner flips completion back | integration (Testcontainers) | `./gradlew test --tests "*MatchAggregationServiceTest*"` | ❌ Wave 0 |
| RSLT-03 | Per-movie like counts queryable | unit/integration | `./gradlew test --tests "*VoteRepositoryTest*"` | ❌ Wave 0 (new test method) |
| (roadmap success criterion 4) | Two-concurrent-clients exactly-once completion | integration, `ExecutorService`/`CountDownLatch`-synchronized concurrent threads against Testcontainers Postgres | `./gradlew test --tests "*VoteServiceConcurrencyTest*"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "org.example.muvimatchr.voting.*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd-verify-work`, including the concurrency test run at least once with `-Dtest.repeat=5`-style repetition or equivalent manual repeated invocation (concurrency bugs are probabilistic — a single green run is weaker evidence than for the rest of this phase's tests).

### Wave 0 Gaps
- [ ] `src/test/kotlin/org/example/muvimatchr/voting/VoteControllerTest.kt` — covers VOTE-01, VOTE-02 (integration)
- [ ] `src/test/kotlin/org/example/muvimatchr/voting/MatchAggregationServiceTest.kt` — covers VOTE-04, VOTE-05, RSLT-03
- [ ] `src/test/kotlin/org/example/muvimatchr/voting/VoteServiceConcurrencyTest.kt` — covers the two-concurrent-clients exactly-once completion criterion specifically
- [ ] Extend `src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt` — add a vote-survives-restart assertion alongside the existing session/participant assertions
- [ ] Framework install: none — Testcontainers Postgres, JUnit 5, and `PostgresTestSupport` base class already exist and are reused as-is

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no (no change) | Existing bearer-token-via-`@CurrentParticipant` mechanism, unchanged `[VERIFIED: CurrentParticipantArgumentResolver.kt]` |
| V3 Session Management | no (no change) | N/A — no cookie/HTTP-session state involved |
| V4 Access Control | yes | Replicate the existing `participant.session.id != sessionId` membership guard (identical pattern in `DeckController.kt:33-35` and `SessionController.kt:43-45,60-61`, both `[VERIFIED]`) in the new `VoteController` |
| V5 Input Validation | yes | Validate `movieId` is a member of `session.pinnedDeck`/`pinnedMovieIds` before upserting a vote — reject with 400 otherwise; validate `choice` deserializes to a known `VoteChoice` enum value (Jackson's existing enum handling covers this) |
| V6 Cryptography | no (no change) | No new secrets or hashing introduced by this phase |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Vote submitted for a `movieId` never actually shown to that participant (deck tampering) | Tampering | Validate `movieId ∈ session.pinnedDeck` server-side before every upsert — do not trust the client to only submit IDs it was shown |
| Cross-session vote injection (participant token from session A used to vote in session B) | Elevation of Privilege / Tampering | Existing `participant.session.id != sessionId` guard pattern, replicated into the new controller — already proven correct in `DeckController`/`SessionController` `[VERIFIED]` |
| SQL injection via native queries | Tampering | Continue the established named-`@Param`-binding-only convention for every new native query in this phase — no string interpolation, matching `VoteRepository.upsertVote`'s existing style `[VERIFIED]` |
| Denial of service via long-held session lock | Denial of Service | Keep the `@Transactional` block that holds the `FOR UPDATE` lock as short as possible — lock, upsert, aggregate-read, return; no external calls (e.g., no TMDB calls) inside this transaction. This phase's flow never needs one, since deck pinning (the one operation that calls `MovieCatalogService`/TMDB) happens on a separate request path (`GET .../deck`), not inside `recordVote` |

## Sources

### Primary (HIGH confidence)
- `[VERIFIED]` Direct reads this session of: `Vote.kt`, `VoteRepository.kt`, `VoteRepositoryTest.kt`, `Session.kt`, `SessionService.kt`, `SessionController.kt`, `Participant.kt`, `ParticipantRepository.kt`, `DeckController.kt`, `MovieCatalogService.kt`, `DeckCacheEntry.kt`, `DeckCacheRepository.kt`, `IntListConverter.kt`, `CatalogReferenceService.kt`, `CurrentParticipantArgumentResolver.kt`, `PostgresTestSupport.kt`, all Flyway migrations V1–V7, `build.gradle.kts`, `.planning/STATE.md`, `.planning/REQUIREMENTS.md`, `.planning/config.json`
- [docs.spring.io — Spring Data JPA Locking reference](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html) — official docs, confirms `@Lock` mechanics

### Secondary (MEDIUM confidence — cross-verified via `classify-confidence --provider websearch --verified`, two or more independent sources agreeing)
- [Baeldung — Enabling Transaction Locks in Spring Data JPA](https://www.baeldung.com/java-jpa-transaction-locks) + [devonfw/devon4j GitHub issue #478](https://github.com/devonfw/devon4j/issues/478) — independently confirm the same `@Lock`-not-applying-to-`nativeQuery=true` limitation this research's central recommendation (Pattern 1) rests on

### Tertiary (LOW confidence — single WebSearch/WebFetch call, not independently cross-verified this session; classified via `classify-confidence --provider websearch` = LOW, tagged `[ASSUMED]` in-body)
- [docs.spring.io — Spring Data JPA Locking reference](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html) — single fetch, confirmed `@Lock` exists but the retrieved excerpt did not cover native-query behavior or `PESSIMISTIC_WRITE` specifics
- Optimistic (`@Version`) vs. pessimistic (`FOR UPDATE`) locking tradeoffs — single WebSearch call per query; directionally consistent with standard JPA-specification behavior (training knowledge) but not independently cross-verified in this session
- PostgreSQL `GROUP BY`/`HAVING`/`COUNT(DISTINCT)` aggregate query pattern — single WebSearch call; standard textbook SQL, not project-specific, not independently cross-verified in this session
- HikariCP default `maximumPoolSize` value (10) — general Spring Boot ecosystem knowledge, not verified against this repo's specific (absent) test-scoped datasource configuration this session; flagged in Assumptions Log (A2)
- Postgres array vs. JSONB general performance tradeoffs (storage/indexing) — single WebSearch call; irrelevant to the final recommendation either way since the schema recommendation uses neither a bare array nor bare JSONB-of-IDs, but a full-object JSONB snapshot

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependencies; every mechanism reuses libraries already verified present in `build.gradle.kts`
- Architecture / concurrency mechanism: MEDIUM-HIGH — the `FOR UPDATE`-in-native-query + enclosing-`@Transactional` recommendation is well-established Spring Data JPA/Postgres practice, cross-checked against official docs and multiple independent community sources, but this project's exact concurrency test has not been run to empirically confirm the "exactly once" behavior (that's this phase's own verification work, not something research can pre-validate)
- Schema design: HIGH — grounded directly in this repo's own existing, already-shipped precedents (`IntListConverter`, `DeckCacheEntry`'s JSONB pattern, `V1`–`V7` migration style), not external research
- Pitfalls: MEDIUM — Pitfalls A, B, D are grounded in verified Spring/JPA mechanics and this repo's own code; Pitfall C (HikariCP pool sizing) is flagged LOW/ASSUMED per its entry in the Assumptions Log

**Research date:** 2026-09-04
**Valid until:** 30 days (stable stack — Spring Boot/Postgres locking semantics do not change on a fast cadence; re-verify only if `build.gradle.kts` versions change before planning executes)
