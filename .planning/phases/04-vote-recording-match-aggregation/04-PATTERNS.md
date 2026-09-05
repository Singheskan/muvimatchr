# Phase 4: Vote Recording & Match Aggregation - Pattern Map

**Mapped:** 2026-09-04
**Files analyzed:** 10 (new/modified)
**Analogs found:** 10 / 10

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|--------------------|------|-----------|-----------------|----------------|
| `src/main/resources/db/migration/V8__add_session_deck_pin_and_genre.sql` | migration | CRUD (DDL) | `src/main/resources/db/migration/V6__add_session_region_and_providers.sql` | exact |
| `src/main/kotlin/org/example/muvimatchr/session/Session.kt` (modify: add `genre`, `pinnedDeckJson`, `deckPinnedAt`) | model | CRUD | `src/main/kotlin/org/example/muvimatchr/catalog/DeckCacheEntry.kt` (for the JSONB column shape) | exact (field-level) |
| `src/main/kotlin/org/example/muvimatchr/session/SessionRepository.kt` (modify: add `lockForUpdate`) | model/repository | request-response (lock) | `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt` (native `@Query`+`@Param` convention) | role-match |
| `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt` (modify: add `pinDeck`, guard `replaceFilters`) | service | CRUD | `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt` (self — extend existing) | exact |
| `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt` (modify: branch on `deckPinnedAt`) | controller | request-response | `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt` (self — extend existing `getDeck`) | exact |
| `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt` (modify: add aggregate/roster native queries) | model/repository | CRUD + batch(aggregate) | `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt` (self — extend `upsertVote` convention) | exact |
| `src/main/kotlin/org/example/muvimatchr/voting/VoteService.kt` (new) | service | request-response (transactional write) | `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt` | role-match |
| `src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt` (new) | service | batch/aggregate (read-model) | `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt` (service composing repository reads; not read directly this session, structurally analogous to `SessionService`) | role-match |
| `src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt` (new) | controller | request-response | `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt` (membership guard + `@CurrentParticipant`) and `src/main/kotlin/org/example/muvimatchr/session/SessionController.kt` (request/response DTO shape) | exact |
| `src/test/kotlin/org/example/muvimatchr/voting/VoteControllerTest.kt`, `MatchAggregationServiceTest.kt`, `VoteServiceConcurrencyTest.kt` (new); `RestartSurvivalTest.kt` (modify) | test | request-response / event-driven(concurrency) | `src/test/kotlin/org/example/muvimatchr/voting/VoteRepositoryTest.kt` (Testcontainers + `PostgresTestSupport` + `newParticipant()` fixture pattern) | exact |

## Pattern Assignments

### `src/main/resources/db/migration/V8__add_session_deck_pin_and_genre.sql` (migration)

**Analog:** `src/main/resources/db/migration/V6__add_session_region_and_providers.sql` (full file, 2 lines) and `src/main/resources/db/migration/V5__create_deck_cache_entry.sql` (for the JSONB column precedent referenced in RESEARCH.md)

**Pattern** (V6, full file):
```sql
ALTER TABLE session ADD COLUMN region VARCHAR(2) NOT NULL DEFAULT 'DE';
ALTER TABLE session ADD COLUMN provider_ids VARCHAR(255) NOT NULL DEFAULT '';
```

Copy the plain `ALTER TABLE ... ADD COLUMN` style exactly, but note V8's columns must be **nullable, no default** (unlike V6's `NOT NULL DEFAULT`) — null `deck_pinned_at` is itself the "not yet pinned" sentinel per D-04. RESEARCH.md's Code Examples section already gives the exact target text:
```sql
ALTER TABLE session ADD COLUMN genre INT;
ALTER TABLE session ADD COLUMN pinned_deck JSONB;
ALTER TABLE session ADD COLUMN deck_pinned_at TIMESTAMPTZ;
```

---

### `src/main/kotlin/org/example/muvimatchr/session/Session.kt` (model, modify)

**Analog:** `src/main/kotlin/org/example/muvimatchr/catalog/DeckCacheEntry.kt` (lines 19-38, full file) for the JSONB field annotation combo; self (current file, lines 17-38) for the entity's own conventions (plain constructor-param fields, `val id: UUID? = null` at bottom).

**JSONB column pattern** (`DeckCacheEntry.kt` lines 25-27):
```kotlin
@Column(name = "movies", nullable = false, columnDefinition = "jsonb")
@JdbcTypeCode(SqlTypes.JSON)
var moviesJson: String,
```

**Apply to `Session.kt` as** (per RESEARCH.md Code Examples, nullable variant since the row exists before the deck does):
```kotlin
@Column(name = "genre")
var genre: Int? = null,

@Column(name = "pinned_deck", columnDefinition = "jsonb")
@JdbcTypeCode(SqlTypes.JSON)
var pinnedDeckJson: String? = null,

@Column(name = "deck_pinned_at")
var deckPinnedAt: Instant? = null,
```

**Existing mutable-field convention** (`Session.kt` lines 23-30 — `region`/`providerIds` are already `var`, matching D-02's "editable until pinned" requirement):
```kotlin
@Column(name = "region", nullable = false, length = 2)
var region: String = DEFAULT_REGION,

@Convert(converter = IntListConverter::class)
@Column(name = "provider_ids", nullable = false, length = 255)
var providerIds: List<Int> = emptyList(),
```

---

### `src/main/kotlin/org/example/muvimatchr/session/SessionRepository.kt` (modify — add `lockForUpdate`)

**Analog:** `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt` (lines 1-30, full file) — the native `@Query`/`@Param` convention this new method must follow exactly.

**Imports pattern** (`VoteRepository.kt` lines 1-8):
```kotlin
package org.example.muvimatchr.voting

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
```

**Native query + `@Param` binding pattern** (`VoteRepository.kt` lines 12-29):
```kotlin
@Modifying
@Transactional
@Query(
    value = """
        INSERT INTO vote (id, session_id, participant_id, movie_id, choice, voted_at)
        VALUES (:id, CAST(:sessionId AS uuid), CAST(:participantId AS uuid), :movieId, :choice, now())
        ON CONFLICT (session_id, participant_id, movie_id)
        DO UPDATE SET choice = EXCLUDED.choice, voted_at = now()
    """,
    nativeQuery = true,
)
fun upsertVote(
    @Param("id") id: UUID,
    @Param("sessionId") sessionId: UUID,
    @Param("participantId") participantId: UUID,
    @Param("movieId") movieId: Long,
    @Param("choice") choice: String,
)
```

**New method to add to `SessionRepository.kt`** (exact text given in RESEARCH.md Pattern 1, follows the same named-`@Param` style — note this one is a plain `@Query` without `@Modifying`, since it's a `SELECT ... FOR UPDATE` not a write):
```kotlin
@Query(value = "SELECT id FROM session WHERE id = CAST(:sessionId AS uuid) FOR UPDATE", nativeQuery = true)
fun lockForUpdate(@Param("sessionId") sessionId: UUID): UUID?
```

Never use `@Lock(LockModeType.PESSIMISTIC_WRITE)` here — RESEARCH.md Pitfall A confirms `@Lock` is silently ignored on `nativeQuery = true` methods.

---

### `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt` (modify — add `pinDeck`, guard `replaceFilters`)

**Analog:** self (current file, lines 1-63, full file already read).

**`@Transactional` service-method pattern** (lines 51-58, `replaceFilters` — the method `pinDeck` and the `replaceFilters` guard both extend):
```kotlin
@Transactional
fun replaceFilters(sessionId: UUID, region: String?, providerIds: List<Int>): Session {
    val session = sessionRepository.findById(sessionId)
        .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No session with id $sessionId") }
    session.region = region ?: DEFAULT_REGION
    session.providerIds = providerIds
    return sessionRepository.save(session)
}
```

**D-02 guard to add** (exact text from RESEARCH.md Code Examples, insert as the first statement inside `replaceFilters` after the `findById`):
```kotlin
if (session.deckPinnedAt != null) {
    throw ResponseStatusException(HttpStatus.CONFLICT, "Session filters are locked once the deck is pinned")
}
```

**Constructor/DI + class declaration pattern** (line 18):
```kotlin
@Service
class SessionService(private val sessionRepository: SessionRepository) {
```
`pinDeck` should be added as a new `@Transactional` method on this same class, following the identical `findById(...).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, ...) }` → mutate fields → `sessionRepository.save(session)` shape.

---

### `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt` (modify — pinning branch)

**Analog:** self (current file, lines 1-94, full file already read).

**Existing membership guard + structure to extend** (lines 27-51):
```kotlin
@GetMapping("/{sessionId}/deck")
fun getDeck(
    @PathVariable sessionId: UUID,
    @RequestParam(required = false) genre: Int?,
    @CurrentParticipant participant: Participant,
): DeckResponse {
    if (participant.session.id != sessionId) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such participant in this session")
    }
    val session = sessionRepository.findById(sessionId).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "No such session")
    }
    catalogReferenceService.requireKnownGenre(genre)
    val result = movieCatalogService.getDeck(genre, session.providerIds, session.region)
```

**New branch to insert** (RESEARCH.md Pattern 3 illustrative shape, adapt to real `DeckResponse` construction):
```kotlin
if (session.deckPinnedAt != null) {
    // Already pinned: session.pinnedDeck + session.genre are the only source of truth from
    // here on. Incoming genre query param is intentionally ignored (D-02/D-03).
    return DeckResponse.from(session)
}
```
Insert this immediately after the membership guard and before the `catalogReferenceService.requireKnownGenre(genre)` call; on the not-yet-pinned path, after computing `result`, call a new `sessionService.pinDeck(sessionId, genre, result.movies)` before returning, per D-04's lazy-trigger requirement.

---

### `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt` (modify — new aggregate queries)

**Analog:** self (current file, lines 1-30) for the `@Param`-bound native-query convention.

Add three new native `@Query` methods (no `@Modifying`, these are reads) using the exact SQL text given in RESEARCH.md Pattern 2: active-roster query, unanimous-movie-ids query, and per-movie like-count query (RSLT-03). Follow the same `nativeQuery = true` + named `@Param` style as `upsertVote` — never string-interpolate `sessionId` etc.

---

### `src/main/kotlin/org/example/muvimatchr/voting/VoteService.kt` (new)

**Analog:** `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt` (lines 1-62, full file) for `@Service`/constructor-injection/`@Transactional` conventions.

**Core pattern** — RESEARCH.md Pattern 1 gives the exact target shape (adapt directly):
```kotlin
@Service
class VoteService(
    private val sessionRepository: SessionRepository,
    private val voteRepository: VoteRepository,
    private val matchAggregationService: MatchAggregationService,
) {
    @Transactional
    fun recordVote(sessionId: UUID, participantId: UUID, movieId: Long, choice: VoteChoice): SessionStatus {
        sessionRepository.lockForUpdate(sessionId)
        voteRepository.upsertVote(UUID.randomUUID(), sessionId, participantId, movieId, choice.name)
        return matchAggregationService.computeStatus(sessionId)
    }
}
```

**Critical constraint** (RESEARCH.md Pitfall B, echoing `SessionService.kt` lines 21-24's own commented-out-transaction caveat for `createSession`): `@Transactional` must be on this service method, not on the repository methods individually — otherwise the `FOR UPDATE` lock releases before the vote upsert runs.

---

### `src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt` (new)

**Analog:** No direct existing analog (first pure aggregate/read-model service in this codebase) — closest structural precedent is `SessionService.kt`'s `@Service` + constructor-injected-repository shape; the aggregate SQL itself is fully specified in RESEARCH.md Pattern 2 (three query shapes: active-roster, unanimous-movie-ids, per-movie like counts).

**Constraint:** No mutable/cached state — every method is a pure read against `voteRepository`/`participantRepository`, computed fresh on every call (ARCHITECTURE.md Pattern 1, D-07 "never a stored/sticky flag").

---

### `src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt` (new)

**Analog:** `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt` (lines 16-38) for the `@CurrentParticipant` + membership-guard pattern; `src/main/kotlin/org/example/muvimatchr/session/SessionController.kt` (lines 20-70, 97-117) for request/response DTO shape and `@RestController`/`@RequestMapping` conventions.

**Membership guard pattern** (`DeckController.kt` lines 24-35):
```kotlin
@GetMapping("/{sessionId}/deck")
fun getDeck(
    @PathVariable sessionId: UUID,
    @RequestParam(required = false) genre: Int?,
    @CurrentParticipant participant: Participant,
): DeckResponse {
    if (participant.session.id != sessionId) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such participant in this session")
    }
```
Reuse verbatim for `POST /{sessionId}/votes` and `GET /{sessionId}/votes/status`.

**Request/response DTO pattern** (`SessionController.kt` lines 97-117):
```kotlin
data class CreateSessionRequest(
    @field:Pattern(regexp = "^[A-Z]{2}$")
    val region: String? = null,
    val providerIds: List<Int> = emptyList(),
)

data class SessionFiltersResponse(val sessionId: UUID, val region: String, val providerIds: List<Int>)
```
Model `VoteRequest(movieId: Long, choice: VoteChoice)` and `VoteStatusResponse(finishedCount: Int, activeCount: Int, isComplete: Boolean, matchedMovieIds: List<Long>)` (per RESEARCH.md Open Question 2 recommendation) the same way — plain data classes below the controller class, `@field:` validation annotations where needed.

**V5 Input Validation addition (movieId must be in pinned deck)** — no direct existing analog; new logic, validate `movieId ∈ session.pinnedDeck` before calling `voteService.recordVote`, returning 400 otherwise (mirrors the existing `catalogReferenceService.requireKnownGenre`/`requireKnownProviders` pre-validation style already used in both `DeckController` and `SessionController`).

---

### Test files (new: `VoteControllerTest.kt`, `MatchAggregationServiceTest.kt`, `VoteServiceConcurrencyTest.kt`; modify: `RestartSurvivalTest.kt`)

**Analog:** `src/test/kotlin/org/example/muvimatchr/voting/VoteRepositoryTest.kt` (full file, 97 lines).

**Base class + fixture pattern** (lines 1-36):
```kotlin
class VoteRepositoryTest : PostgresTestSupport() {

    @Autowired
    lateinit var sessionRepository: SessionRepository

    @Autowired
    lateinit var participantRepository: ParticipantRepository

    @Autowired
    lateinit var voteRepository: VoteRepository

    private fun newParticipant(): Participant {
        val joinCode = UUID.randomUUID().toString().take(16)
        val session = sessionRepository.save(Session(joinCode = joinCode))
        return participantRepository.save(
            Participant(session = session, displayName = "Voter", tokenHash = UUID.randomUUID().toString())
        )
    }
}
```

**Test method pattern** (lines 56-66):
```kotlin
@Test
fun `upsertVote for a new tuple inserts exactly one row`() {
    val participant = newParticipant()
    val sessionId = participant.session.id!!
    val participantId = participant.id!!
    val movieId = 550L

    voteRepository.upsertVote(UUID.randomUUID(), sessionId, participantId, movieId, VoteChoice.LIKE.name)

    assertEquals(1, voteCount(sessionId, participantId, movieId))
}
```

For `VoteServiceConcurrencyTest.kt`, extend this fixture pattern with `ExecutorService`/`CountDownLatch`-synchronized concurrent threads (RESEARCH.md Pitfall C) — no existing concurrency-test analog in this codebase; this is genuinely new test infrastructure built on top of the `PostgresTestSupport` base.

For `RestartSurvivalTest.kt`, read the existing session/participant assertions in that file and add a parallel vote-survives-restart assertion using the same `newParticipant()`-style fixture plus `voteRepository.upsertVote`.

---

## Shared Patterns

### Membership/access-control guard
**Source:** `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt` lines 33-35, identical in `src/main/kotlin/org/example/muvimatchr/session/SessionController.kt` lines 43-45, 60-61
**Apply to:** `VoteController.kt` (both new endpoints)
```kotlin
if (participant.session.id != sessionId) {
    throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such participant in this session")
}
```

### Native `@Query` + `@Param` convention (no string interpolation, ever)
**Source:** `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt` lines 12-29; `src/main/kotlin/org/example/muvimatchr/catalog/DeckCacheRepository.kt` lines 25-42 (identical upsert-on-conflict shape)
**Apply to:** `SessionRepository.lockForUpdate`, all new `VoteRepository` aggregate queries
```kotlin
@Query(value = "...", nativeQuery = true)
fun someQuery(@Param("x") x: UUID): ...
```

### `@Transactional` service-method boundary (lock + write + read must share one transaction)
**Source:** `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt` lines 51-58 (`@Transactional` on `replaceFilters`), and the explicit *inverse*-case comment at lines 21-24 (`createSession` deliberately has NO `@Transactional`, explaining why)
**Apply to:** `VoteService.recordVote` — must be `@Transactional` at the service-method level (not on individual repository calls), per RESEARCH.md Pitfall B.

### JPA entity JSONB column shape
**Source:** `src/main/kotlin/org/example/muvimatchr/catalog/DeckCacheEntry.kt` lines 25-27
**Apply to:** `Session.pinnedDeckJson`
```kotlin
@Column(name = "movies", nullable = false, columnDefinition = "jsonb")
@JdbcTypeCode(SqlTypes.JSON)
var moviesJson: String,
```
(nullable variant for `Session`, since the field is absent pre-pin)

### Flyway migration style
**Source:** `src/main/resources/db/migration/V6__add_session_region_and_providers.sql` (full file)
**Apply to:** `V8__add_session_deck_pin_and_genre.sql`
```sql
ALTER TABLE session ADD COLUMN <name> <type>;
```

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt` | service | batch/aggregate (read-model) | First pure-read aggregation service in this codebase — no prior file composes multiple `GROUP BY`/`HAVING` native queries into a stateless read-model. Use RESEARCH.md Pattern 2's fully-specified SQL directly; structural service shape borrowed from `SessionService.kt`. |
| `src/test/kotlin/org/example/muvimatchr/voting/VoteServiceConcurrencyTest.kt` | test | event-driven (concurrency) | No existing concurrent-thread integration test in this codebase (`PostgresTestSupport`-based tests so far are all single-threaded). Build on `VoteRepositoryTest.kt`'s fixture pattern plus `ExecutorService`/`CountDownLatch`, per RESEARCH.md Pitfall C guidance. |

## Metadata

**Analog search scope:** `src/main/kotlin/org/example/muvimatchr/{voting,session,catalog}/`, `src/main/resources/db/migration/`, `src/test/kotlin/org/example/muvimatchr/{voting,support}/`
**Files scanned:** 41 main + 19 test Kotlin files, 7 Flyway migrations
**Pattern extraction date:** 2026-09-04
