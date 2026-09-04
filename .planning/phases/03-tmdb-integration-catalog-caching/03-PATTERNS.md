# Phase 3: TMDB Integration & Catalog Caching - Pattern Map

**Mapped:** 2026-09-04
**Files analyzed:** 15
**Analogs found:** 13 / 15

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|--------------------|------|-----------|-----------------|----------------|
| `catalog/CatalogWebClientConfig.kt` | config | request-response | none (no existing HTTP-client bean in codebase) | no analog |
| `catalog/MovieCatalogClient.kt` | service (external API client) | request-response | `session/SessionService.kt` (service w/ retry-style loop) + `service/MovieService.kt` (legacy, superseded — do NOT copy its pattern, only note as anti-pattern) | partial |
| `catalog/MovieCatalogService.kt` | service | CRUD + request-response (cache-aware) | `session/SessionService.kt` | role-match |
| `catalog/DeckController.kt` | controller | request-response | `session/ParticipantController.kt` | exact |
| `catalog/DeckCacheEntry.kt` | model (JPA entity) | CRUD | `session/Session.kt` | exact |
| `catalog/DeckCacheRepository.kt` | model (JPA repository, upsert) | CRUD | `voting/VoteRepository.kt` | exact |
| `catalog/Genre.kt` / `GenreRepository.kt` | model | CRUD | `session/Session.kt` / `session/SessionRepository.kt` | exact |
| `catalog/WatchProvider.kt` / `WatchProviderRepository.kt` | model | CRUD | `session/Session.kt` / `session/SessionRepository.kt` | exact |
| `catalog/tmdb/TmdbDiscoverResponse.kt`, `TmdbMovie.kt`, `TmdbGenreListResponse.kt`, `TmdbWatchProviderResponse.kt` | utility (DTO) | transform | `session/ParticipantController.kt` request/response data classes (nested `data class` convention) | role-match |
| `session/Session.kt` (modified: add `region`, `providers`) | model | CRUD | itself (existing file — extend in place) | exact |
| `db/migration/V5__*.sql` (Session region/providers), `V6__*.sql` (deck_cache_entry, genre, watch_provider) | migration | schema | `db/migration/V4__add_participant_token.sql` | exact |
| `application.properties` (TMDB config additions) | config | — | itself (existing file — extend in place) | exact |
| `build.gradle.kts` (webclient/coroutines-reactor/mockwebserver3 deps) | config | — | itself (existing file — extend in place) | exact |
| `test/catalog/MovieCatalogClientTest.kt` | test | request-response | none — new pattern (mockwebserver3 has no prior analog); structure test class like `VoteRepositoryTest.kt` (plain JUnit5, no MockMvc) | no analog |
| `test/catalog/MovieCatalogServiceTest.kt` | test | CRUD/cache | `voting/VoteRepositoryTest.kt` | role-match |
| `test/catalog/DeckControllerTest.kt` | test | request-response | `session/ParticipantControllerTest.kt` | exact |

## Pattern Assignments

### `catalog/DeckController.kt` (controller, request-response)

**Analog:** `src/main/kotlin/org/example/muvimatchr/session/ParticipantController.kt`

**Imports pattern** (lines 1-16):
```kotlin
package org.example.muvimatchr.session

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.example.muvimatchr.auth.CurrentParticipant
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
```
For `DeckController.kt`, use `@RequestMapping("/api/sessions")` and add a nested path `/{sessionId}/deck` mapping with `@RequestParam` for optional `genre`/`provider` query params, mirroring how `ParticipantController` handles `@PathVariable sessionId` + validates the participant belongs to that session.

**Core request-response + 404 pattern** (lines 38-44):
```kotlin
@GetMapping("/{sessionId}/participants/me")
fun me(@PathVariable sessionId: UUID, @CurrentParticipant participant: Participant): ParticipantResponse {
    if (participant.session.id != sessionId) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such participant in this session")
    }
    return ParticipantResponse(participant.id!!, participant.session.id!!, participant.displayName)
}
```
Copy this shape for `DeckController.getDeck(sessionId, genre?, provider?)`: look up `Session` by id, throw `ResponseStatusException(HttpStatus.NOT_FOUND, ...)` if missing, delegate to `MovieCatalogService.getDeck(...)`, and use a `data class` response DTO exactly like `ParticipantResponse`/`JoinResponse` below it in the same file (lines 53-61).

**Response DTO pattern** (lines 53-61):
```kotlin
data class JoinResponse(
    val participantId: UUID,
    val sessionId: UUID,
    val displayName: String,
    val token: String,
    val resumeUrl: String,
)

data class ParticipantResponse(val participantId: UUID, val sessionId: UUID, val displayName: String)
```
Use this same "plain `data class` below the controller in the same file" convention for `DeckResponse`, `DeckMovieResponse`, and the "not enough movies" envelope DTO.

---

### `catalog/DeckCacheEntry.kt`, `Genre.kt`, `WatchProvider.kt` (model, CRUD)

**Analog:** `src/main/kotlin/org/example/muvimatchr/session/Session.kt`

**Entire file** (lines 1-24):
```kotlin
package org.example.muvimatchr.session

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "session")
class Session(
    @Column(name = "join_code", nullable = false, length = 16)
    val joinCode: String,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null
}
```

**Established entity conventions to replicate exactly:**
- Plain Kotlin `class` (never `data class`) for JPA entities — required by `kotlin("plugin.jpa")`'s no-arg/all-open bytecode generation.
- `@Id @GeneratedValue(strategy = GenerationType.UUID)` on a nullable `val id: UUID? = null`, always last in the primary constructor position (see `Vote.kt` lines 49-51 for identical shape with a `@ManyToOne` entity too).
- `@Column(name = "snake_case_name", nullable = false)` explicit on every column — never rely on Hibernate's default naming strategy alone.
- Table name declared explicitly via `@Table(name = "...")`.

For `DeckCacheEntry`: `cacheKey: String` (unique), `movies: String` (JSONB — see note below on JSONB mapping, no existing JSONB column in this codebase, so this is a new pattern; use `@Column(columnDefinition = "jsonb")` with a `String` field and let Jackson/native SQL handle (de)serialization, OR use Hibernate's `@JdbcTypeCode(SqlTypes.JSON)` — Claude's Discretion per CONTEXT.md), `totalResults: Int`, `fetchedAt: Instant = Instant.now()` (mirrors `Session.createdAt`'s default-instant convention).

For `Genre`/`WatchProvider`: simple flat entities, `tmdbId: Int` (unique), `name: String`, following the exact `Session` shape above.

---

### `catalog/DeckCacheRepository.kt` (model, atomic upsert)

**Analog:** `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt`

**Entire file** (lines 1-30):
```kotlin
package org.example.muvimatchr.voting

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface VoteRepository : JpaRepository<Vote, UUID> {

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
}
```
This is the **exact pattern** PITFALLS.md/RESEARCH.md Pitfall 3 calls out for `DeckCacheRepository.upsert`: a single native `@Modifying @Query` with `INSERT ... ON CONFLICT (cache_key) DO UPDATE SET movies = EXCLUDED.movies, total_results = EXCLUDED.total_results, fetched_at = now()`, wrapped in `@Transactional`. Copy this file structure verbatim, substituting the `deck_cache_entry` table/columns and a JSONB-cast parameter for `movies` (e.g. `CAST(:movies AS jsonb)`).

`GenreRepository`/`WatchProviderRepository` are simpler — plain `JpaRepository<Genre, UUID>` with a `findByTmdbId` lookup, following `SessionRepository.kt`'s shape:
```kotlin
// session/SessionRepository.kt, entire file
package org.example.muvimatchr.session

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SessionRepository : JpaRepository<Session, UUID> {
    fun findByJoinCode(joinCode: String): Session?
}
```

---

### `catalog/MovieCatalogService.kt` (service, cache-aware orchestration)

**Analog:** `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt`

**Imports + class shape pattern** (lines 1-14):
```kotlin
package org.example.muvimatchr.session

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.security.SecureRandom

@Service
class SessionService(private val sessionRepository: SessionRepository) {
    private val random = SecureRandom()
```
Constructor-inject `DeckCacheRepository`, `GenreRepository`, `WatchProviderRepository`, and `MovieCatalogClient` the same way — plain constructor DI, `@Service` annotation, no field injection.

**Retry-loop-with-explicit-comment-on-transaction-boundary convention** (lines 17-31):
```kotlin
// Deliberately NO @Transactional here: PostgreSQL aborts the entire transaction after any
// failed statement, so a shared-transaction retry loop would break on the second attempt.
// Each retry calls the plain `save()` Spring Data method, which is individually transactional,
// so each attempt gets its own transaction.
fun createSession(): Session {
    repeat(MAX_JOIN_CODE_ATTEMPTS) {
        val candidate = generateJoinCode()
        try {
            return sessionRepository.save(Session(joinCode = candidate))
        } catch (e: DataIntegrityViolationException) {
            // collision on uq_session_join_code — retry with a new candidate
        }
    }
    throw IllegalStateException("Could not allocate a unique join code after $MAX_JOIN_CODE_ATTEMPTS attempts")
}
```
This codebase's established convention is: **document non-obvious transactional/concurrency reasoning inline with a comment**, exactly like D-04's retry-then-serve-stale and Pitfall 3's upsert race need documenting in `MovieCatalogService.getDeck`. Follow this precedent — add a comment above the cache-check block explaining the stale-serve-on-failure fallback (D-04) and why the upsert (not check-then-insert) avoids the race (Pitfall 3).

---

### `catalog/MovieCatalogClient.kt` (WebClient wrapper) — no direct analog, use RESEARCH.md Pattern 2/3 verbatim

No existing file in this codebase constructs an outbound HTTP client — `service/MovieService.kt` is the closest by role (external API caller) but is explicitly flagged in CONTEXT.md as **legacy to replace, not extend** (uses deprecated blocking `RestTemplate`, hardcoded OMDb key, mock data). Do not copy its pattern; it exists only as the anti-pattern this phase supersedes.

**Anti-pattern reference** (`src/main/kotlin/org/example/muvimatchr/service/MovieService.kt`, lines 1-25) — for awareness only, not to copy:
```kotlin
package org.example.muvimatchr.service

import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class MovieService {
    private val apiKey = "7a1b329"
    private val baseUrl = "http://www.omdbapi.com/"
    private val restTemplate = RestTemplate()
    // ...
}
```
Everything about this file is what NOT to do: hardcoded secret, blocking `RestTemplate`, no error handling, no caching, wrong provider (OMDb not TMDB).

**Use instead:** RESEARCH.md Pattern 2 (`CatalogWebClientConfig` bean) and Pattern 3 (`discoverMovies` suspend function with `Retry.backoff`) verbatim — these are the correct, project-appropriate patterns already fully specified in `03-RESEARCH.md` lines 201-260, since no in-repo precedent exists yet.

---

### `db/migration/V5__*.sql`, `V6__*.sql` (migration)

**Analog:** `src/main/resources/db/migration/V4__add_participant_token.sql`

**Entire file** (lines 1-2):
```sql
ALTER TABLE participant ADD COLUMN token_hash VARCHAR(64) NOT NULL;
CREATE UNIQUE INDEX uq_participant_token_hash ON participant (token_hash);
```
Established convention: one focused ALTER/CREATE per migration file, unique index created as a separate explicit statement (not inline `UNIQUE` in the column def) when it needs a name. For `V5__add_session_region_and_providers.sql`:
```sql
ALTER TABLE session ADD COLUMN region VARCHAR(8);
ALTER TABLE session ADD COLUMN providers VARCHAR(255);
```
(Nullable, app-level default to `DE`/empty — matches D-01's "optional at creation" plus this codebase's existing preference for simple column types over arrays, consistent with `token_hash VARCHAR(64)` above.) For `V6__create_catalog_tables.sql`, use RESEARCH.md Pattern 1's exact DDL (deck_cache_entry with `UNIQUE INDEX uq_deck_cache_entry_key`), plus `genre`/`watch_provider` tables following the same `id UUID PRIMARY KEY` + `CREATE UNIQUE INDEX` shape.

---

### `test/catalog/DeckControllerTest.kt` (test, request-response)

**Analog:** `src/test/kotlin/org/example/muvimatchr/session/ParticipantControllerTest.kt`

**Class setup pattern** (lines 1-33):
```kotlin
package org.example.muvimatchr.session

import org.example.muvimatchr.support.PostgresTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper

@AutoConfigureMockMvc
class ParticipantControllerTest : PostgresTestSupport() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper
```
Extend `PostgresTestSupport()` (reuses the singleton-container fixture — see Shared Patterns below), `@AutoConfigureMockMvc` at class level, `MockMvc`/`ObjectMapper` autowired. For CTLG-05's "no token in response" assertion, mock `MovieCatalogClient`'s underlying calls with `mockwebserver3` (see RESEARCH.md Supporting Libraries) rather than a real TMDB call, and assert via `jsonPath`/raw body string search that no `Authorization`/Bearer token substring leaks into the JSON response, mirroring how `ParticipantControllerTest`'s `` `me with an unrecognized bearer token returns 401` `` (lines 145-155) asserts on response status/headers.

---

### `test/catalog/MovieCatalogServiceTest.kt` / cache-hit tests (test, CRUD/cache)

**Analog:** `src/test/kotlin/org/example/muvimatchr/voting/VoteRepositoryTest.kt`

**Upsert-idempotency test pattern** (lines 68-80):
```kotlin
@Test
fun `upsertVote for an existing tuple updates the choice in place instead of duplicating`() {
    val participant = newParticipant()
    val sessionId = participant.session.id!!
    val participantId = participant.id!!
    val movieId = 551L

    voteRepository.upsertVote(UUID.randomUUID(), sessionId, participantId, movieId, VoteChoice.LIKE.name)
    voteRepository.upsertVote(UUID.randomUUID(), sessionId, participantId, movieId, VoteChoice.PASS.name)

    assertEquals(1, voteCount(sessionId, participantId, movieId))
    assertEquals(VoteChoice.PASS.name, voteChoice(sessionId, participantId, movieId))
}
```
Use this exact "call upsert twice, assert row count still 1, assert latest value wins" shape for `DeckCacheRepositoryTest`'s cache-key uniqueness test, and adapt the row-count-via-`jdbcTemplate.queryForObject` pattern (lines 38-45) for CTLG-04's "second request doesn't re-hit TMDB" assertion — but assert the mockwebserver3 request count instead of a DB row count for that specific test.

---

## Shared Patterns

### Package-by-feature, plain-class JPA entities
**Source:** `src/main/kotlin/org/example/muvimatchr/session/Session.kt`, `src/main/kotlin/org/example/muvimatchr/voting/Vote.kt`
**Apply to:** All new `catalog/` entities (`DeckCacheEntry`, `Genre`, `WatchProvider`)
- New package `catalog/` (feature-named, not `model/`/`entity/`) — sibling to `session/`, `voting/`.
- Every entity: plain `class` (not `data class`), `@Id @GeneratedValue(strategy = GenerationType.UUID)` nullable `val id`, explicit `@Column(name = "snake_case", nullable = false)`.

### Atomic upsert via native `@Modifying @Query`, never check-then-insert
**Source:** `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt` lines 12-29
**Apply to:** `DeckCacheRepository.upsert(...)` — required per RESEARCH.md Pitfall 3 to avoid the concurrent-refresh race on the same `cache_key`.

### Flyway-only DDL, one migration per logical change
**Source:** `src/main/resources/db/migration/V1__create_session.sql` through `V4__add_participant_token.sql`
**Apply to:** `V5__*.sql` (Session columns), `V6__*.sql` (new catalog tables) — `spring.jpa.hibernate.ddl-auto=validate` means Hibernate never auto-creates schema; every new column/table must ship as a numbered Flyway migration.

### Controller: `@RestController` + `@RequestMapping("/api/sessions")`, path-scoped sub-resources, `ResponseStatusException` for 404s
**Source:** `src/main/kotlin/org/example/muvimatchr/session/ParticipantController.kt` lines 1-45, `session/SessionController.kt` lines 1-22
**Apply to:** `DeckController.kt` — same base path prefix, plain `data class` response DTOs declared in the same file below the controller class.

### Testcontainers Postgres via shared singleton base class
**Source:** `src/test/kotlin/org/example/muvimatchr/support/PostgresTestSupport.kt`
**Apply to:** Any new test needing a real Postgres (`DeckCacheRepositoryTest`, `MovieCatalogServiceTest`, `DeckControllerTest`) — extend `PostgresTestSupport()`, never manage a container manually (see the file's own comment on why per-class `@Testcontainers` breaks across multiple test classes).

### Config additions to existing files, not new files
**Source:** `src/main/resources/application.properties` (current 7 lines), `build.gradle.kts`
**Apply to:** Add `tmdb.api.base-url`, `tmdb.api.read-access-token=${TMDB_API_TOKEN}`, `tmdb.cache.deck-ttl-hours=6` to the existing `application.properties`; add `spring-boot-starter-webclient`, `kotlinx-coroutines-reactor:1.11.0` (implementation) and `mockwebserver3:5.5.0` (testImplementation) to the existing `dependencies { }` block in `build.gradle.kts`, following the existing convention of an inline `//` comment explaining each dependency's purpose (see every existing line in that block).

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `catalog/CatalogWebClientConfig.kt` | config | request-response | No existing `@Bean WebClient`/HTTP-client config anywhere in the codebase — first outbound HTTP integration. Use RESEARCH.md Pattern 2 verbatim (already fully specified and verified against TMDB/Boot docs). |
| `catalog/MovieCatalogClient.kt` | service (API client) | request-response | `service/MovieService.kt` is same role but is legacy/anti-pattern (blocking `RestTemplate`, hardcoded key, wrong provider) — explicitly flagged in CONTEXT.md as "replace, not extend." Use RESEARCH.md Pattern 3 (`WebClient` + coroutines + `Retry.backoff`) verbatim. |
| `test/catalog/MovieCatalogClientTest.kt` | test | request-response (mocked) | No prior use of `mockwebserver3` or WebClient testing in this codebase — new dependency and pattern, fully specified in RESEARCH.md Supporting Libraries + Pitfall 2. |

## Metadata

**Analog search scope:** `src/main/kotlin/org/example/muvimatchr/` (all packages: `session/`, `voting/`, `service/`, `controller/`, `auth/`, `config/`), `src/test/kotlin/org/example/muvimatchr/` (all test classes), `src/main/resources/` (application.properties, db/migration/), `build.gradle.kts`
**Files scanned:** 19 main + 5 test + build.gradle.kts + application.properties + 4 migrations
**Pattern extraction date:** 2026-09-04
