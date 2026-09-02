# Phase 2: Session & Lobby Flow - Pattern Map

**Mapped:** 2026-09-02
**Files analyzed:** 15 (10 new, 2 modified, 3 new tests)
**Analogs found:** 13 / 15 (2 have no direct analog — first REST controllers/config in this codebase)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `session/Participant.kt` (MODIFIED — add `tokenHash`) | model | CRUD | `session/Participant.kt` (itself, Phase 1 shape) / `voting/Vote.kt` for a 2nd column-add reference | exact (self) |
| `session/ParticipantRepository.kt` (MODIFIED — add `findByTokenHash`) | model/repository | CRUD | `voting/VoteRepository.kt` | role-match |
| `db/migration/V4__add_participant_token.sql` | migration | CRUD (DDL) | `db/migration/V2__create_participant.sql`, `V1__create_session.sql` | exact |
| `session/SessionService.kt` | service | CRUD (create + retry) | none in-repo (`service/LobbyService.kt` is in-memory, not reusable) — use RESEARCH.md Pattern 1 | no analog |
| `session/ParticipantService.kt` | service | CRUD (create + lookup) | none in-repo — use RESEARCH.md Pattern 2/3 | no analog |
| `session/SessionController.kt` | controller | request-response (REST) | none in-repo (`controller/LobbyController.kt` is `@Controller`+`HttpSession`, explicitly NOT reusable) — use RESEARCH.md Architecture Patterns | no analog |
| `session/ParticipantController.kt` | controller | request-response (REST) | none in-repo — same as above | no analog |
| `auth/TokenService.kt` | utility/service | transform (crypto) | none in-repo — use RESEARCH.md Pattern 2 verbatim | no analog |
| `auth/CurrentParticipant.kt` | utility (annotation) | — | none in-repo — use RESEARCH.md Pattern 3 verbatim | no analog |
| `auth/CurrentParticipantArgumentResolver.kt` | middleware | request-response | none in-repo — use RESEARCH.md Pattern 3 verbatim | no analog |
| `config/WebMvcConfig.kt` | config | — | `config/WebSocketConfig.kt` (only existing `@Configuration` class — shows package/annotation conventions) | partial (structural only) |
| `session/SessionServiceTest.kt` | test | CRUD | `test/session/SessionRepositoryTest.kt` | exact (structure) |
| `session/ParticipantControllerTest.kt` | test | request-response | `test/session/SessionRepositoryTest.kt` + `test/voting/VoteRepositoryTest.kt` (for multi-row/uniqueness assertions) | role-match |
| `CurrentParticipantArgumentResolverTest.kt` (or folded into ParticipantControllerTest) | test | request-response | `test/session/SessionRepositoryTest.kt` | role-match |

## Pattern Assignments

### `session/Participant.kt` (MODIFIED — add `tokenHash` column)

**Analog:** itself (`src/main/kotlin/org/example/muvimatchr/session/Participant.kt`, current Phase 1 shape) plus `voting/Vote.kt` as a second reference for constructor-param-style columns with a `UniqueConstraint`.

**Current full file** (`src/main/kotlin/org/example/muvimatchr/session/Participant.kt` lines 1-32):
```kotlin
package org.example.muvimatchr.session

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "participant")
class Participant(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    val session: Session,

    @Column(name = "display_name", nullable = false, length = 100)
    val displayName: String,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null
}
```

**How to modify:** add a new constructor param following the exact same `@Column(name = ..., nullable = false, length = N)` shape used for `displayName` above:
```kotlin
@Column(name = "token_hash", nullable = false, length = 64)
val tokenHash: String,
```
Keep it a plain Kotlin class (not `data class`) — this project's `kotlin("plugin.jpa")` handles the no-arg constructor; do not add `equals`/`hashCode`/`copy`.

**Uniqueness-via-constraint reference** — `voting/Vote.kt` lines 20-29 shows the `@Table(uniqueConstraints = [UniqueConstraint(...)])` idiom this project uses when a column combination (or, here, a single column) must be enforced unique at the entity-annotation level in addition to the migration's `CREATE UNIQUE INDEX`:
```kotlin
@Table(
    name = "vote",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_vote_session_participant_movie",
            columnNames = ["session_id", "participant_id", "movie_id"],
        )
    ],
)
```
(For `tokenHash`, the migration's `CREATE UNIQUE INDEX uq_participant_token_hash` is the source of truth per Phase 1's established DB-owns-DDL convention — an entity-level `@Table(uniqueConstraints=...)` is optional/redundant since Flyway, not Hibernate, creates the constraint; `Session.joinCode` did NOT add one at the entity level for its own unique index, so follow that same precedent and omit it on `Participant` too, for consistency.)

---

### `session/ParticipantRepository.kt` (MODIFIED — add `findByTokenHash`)

**Analog:** `voting/VoteRepository.kt` (shows both the plain-interface style used by `SessionRepository`/current `ParticipantRepository`, and a derived-query pattern).

**Current full file** (lines 1-7):
```kotlin
package org.example.muvimatchr.session

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ParticipantRepository : JpaRepository<Participant, UUID>
```

**How to modify** — add a Spring-Data-derived query method (no `@Query`/native SQL needed since this is a simple equality lookup on an indexed column; RESEARCH.md confirms the project's "named `@Param`, never string interpolation" rule only applies to `@Query(nativeQuery = true)`, which `findByTokenHash` does not need):
```kotlin
interface ParticipantRepository : JpaRepository<Participant, UUID> {
    fun findByTokenHash(tokenHash: String): Participant?
}
```

**Native-query discipline reference** (only relevant if a future query in this phase needs raw SQL) — `voting/VoteRepository.kt` lines 12-29, showing named `@Param` bindings, never string interpolation:
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

---

### `db/migration/V4__add_participant_token.sql` (migration)

**Analog:** `db/migration/V2__create_participant.sql` (lines 1-7) and `V1__create_session.sql` (lines 1-6) — both show this project's migration idioms: plain DDL, `UUID PRIMARY KEY`, a separate `CREATE UNIQUE INDEX` statement (not an inline `UNIQUE` column constraint) for uniqueness.

**`V2__create_participant.sql` full file:**
```sql
CREATE TABLE participant (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES session (id),
    display_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_participant_session_id ON participant (session_id);
```

**`V1__create_session.sql` full file** (shows the `CREATE UNIQUE INDEX` idiom to mirror for `token_hash`):
```sql
CREATE TABLE session (
    id UUID PRIMARY KEY,
    join_code VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_session_join_code ON session (join_code);
```

**New V4 file, following the exact same shape** (per RESEARCH.md, `VARCHAR(64)` exactly fits a hex-encoded SHA-256 digest; `NOT NULL` is safe with no default/backfill since no production data exists yet):
```sql
ALTER TABLE participant ADD COLUMN token_hash VARCHAR(64) NOT NULL;
CREATE UNIQUE INDEX uq_participant_token_hash ON participant (token_hash);
```

**Critical constraint (Pitfall 3 from RESEARCH.md):** create a NEW `V4__*.sql` file — do NOT edit `V2__create_participant.sql` in place. Flyway's checksum tracking (proven working in Phase 1's `RestartSurvivalTest`) will fail on any environment that already applied V1-V3.

---

### `session/SessionService.kt` (service, CRUD — NEW, no in-repo analog)

**No analog** — `service/LobbyService.kt` and `service/MovieService.kt` are in-memory/legacy-prototype and explicitly not reusable per CONTEXT.md. Use RESEARCH.md's own Pattern 1 as the source of truth, since it is itself derived from and verified against this project's already-proven `SessionRepositoryTest` behavior:

```kotlin
// Source: RESEARCH.md Pattern 1 — mirrors Phase 1's already-proven join_code uniqueness behavior
private val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // verify exact char count when implementing
private val random = SecureRandom()

fun generateJoinCode(length: Int = 6): String =
    (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

@Transactional
fun createSession(): Session {
    repeat(MAX_JOIN_CODE_ATTEMPTS) {
        val candidate = generateJoinCode()
        try {
            return sessionRepository.saveAndFlush(Session(joinCode = candidate))
        } catch (e: DataIntegrityViolationException) {
            // collision on uq_session_join_code — retry with a new candidate
        }
    }
    throw IllegalStateException("Could not allocate a unique join code after $MAX_JOIN_CODE_ATTEMPTS attempts")
}
```

**Constructor-injection convention reference** — `voting/VoteRepository.kt`/entity files show plain constructor-param style (not field injection); apply the same style to `SessionService(private val sessionRepository: SessionRepository)`.

**DB-level collision retry, never pre-check with SELECT** — proven by `test/session/SessionRepositoryTest.kt` lines 26-33:
```kotlin
@Test
fun `duplicate join code is rejected by the database on flush`() {
    sessionRepository.saveAndFlush(Session(joinCode = "DUPE01"))

    assertThrows(DataIntegrityViolationException::class.java) {
        sessionRepository.saveAndFlush(Session(joinCode = "DUPE01"))
    }
}
```

---

### `session/ParticipantService.kt` (service, CRUD — NEW, no in-repo analog)

**No analog.** Use RESEARCH.md Pattern 2 (token issuance) composed with a simple `ParticipantRepository.save(...)` call, following the same constructor-injection convention as above. Core shape: look up `Session` by `joinCode` via `SessionRepository` (404 if absent per RESEARCH.md Open Question 2 recommendation), call `TokenService.issue()`, construct `Participant(session, displayName, tokenHash)`, save, return `(participant, rawToken)` to the controller for the one-time response.

---

### `auth/TokenService.kt` (utility/service, transform — NEW, no in-repo analog)

**No analog** — first crypto/token code in this codebase. Use RESEARCH.md Pattern 2 verbatim:
```kotlin
@Component
class TokenService {
    private val random = SecureRandom()

    fun issue(): IssuedToken {
        val bytes = ByteArray(32) // 256 bits
        random.nextBytes(bytes)
        val rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return IssuedToken(rawToken, hash(rawToken))
    }

    fun hash(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } // 64 hex chars
    }
}

data class IssuedToken(val rawToken: String, val tokenHash: String)
```
Note: `IssuedToken` is an acceptable `data class` use (a plain transport-value tuple, not a JPA entity) — the "no data class" rule in this codebase applies specifically to `@Entity` classes per CONTEXT.md's "Established Patterns," not to plain DTOs/value holders.

---

### `session/SessionController.kt` / `session/ParticipantController.kt` (controller, request-response — NEW, no in-repo analog)

**No reusable analog exists.** `controller/LobbyController.kt` (full file read, 110 lines) is the only controller precedent in the repo and is explicitly called out in CONTEXT.md as **not reusable** — it is `@Controller` (view-returning) not `@RestController`, uses `HttpSession`-backed in-memory maps (`mutableMapOf<String, MutableMap<String, Boolean>>()`), and models participants as bare strings, none of which fit this phase's persisted/token-based design. Do not adapt patterns from it beyond "it exists and stays in the repo untouched."

Use RESEARCH.md's Architecture Patterns / system diagram as the authoritative source for endpoint shapes:
```kotlin
// POST /api/sessions -> 201 Created, Location: /api/sessions/{id}, body: { sessionId, joinCode }
// POST /api/sessions/{joinCode}/participants { displayName } -> 201 Created,
//   body: { participantId, sessionId, displayName, token, resumeUrl }
// GET /api/sessions/{sessionId}/participants/me  (Authorization: Bearer <token>) -> 200,
//   body: { participantId, sessionId, displayName }
```
Use `@RestController`, constructor-injected services (matching this project's constructor-injection convention seen in `VoteRepository`/entities), Jakarta Bean Validation (`@Valid @RequestBody`, `@NotBlank @Size(max = 100)` on the DTO's `displayName` per `Participant.kt` line 22's `length = 100`), and `ResponseStatusException(HttpStatus.NOT_FOUND, ...)` for unknown join codes (RESEARCH.md Open Question 2).

---

### `auth/CurrentParticipant.kt` + `auth/CurrentParticipantArgumentResolver.kt` (middleware — NEW, no in-repo analog)

**No analog.** Use RESEARCH.md Pattern 3 verbatim — this is the authoritative, already-designed source:
```kotlin
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentParticipant

@Component
class CurrentParticipantArgumentResolver(
    private val participantRepository: ParticipantRepository,
    private val tokenService: TokenService,
) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter) =
        parameter.hasParameterAnnotation(CurrentParticipant::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        val header = webRequest.getHeader(HttpHeaders.AUTHORIZATION)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Authorization header")
        val token = header.removePrefix("Bearer ").trim()
        val hash = tokenService.hash(token)
        return participantRepository.findByTokenHash(hash)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or unrecognized token")
    }
}
```

---

### `config/WebMvcConfig.kt` (config — partial analog)

**Analog:** `config/WebSocketConfig.kt` (full file, lines 1-22) — the only existing `@Configuration` class in the repo; useful for package placement (`org.example.muvimatchr.config`) and the `@Configuration class X : SomeConfigurer { override fun ... }` structural idiom, but the interface implemented and the specific override differ completely (WebSocket vs MVC).

```kotlin
package org.example.muvimatchr.config

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig : WebSocketMessageBrokerConfigurer {

    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic")
        config.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws").withSockJS()
    }
}
```

**Apply the same shape to the new file** (per RESEARCH.md Pattern 3):
```kotlin
package org.example.muvimatchr.config

@Configuration
class WebMvcConfig(
    private val currentParticipantArgumentResolver: CurrentParticipantArgumentResolver,
) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(currentParticipantArgumentResolver)
    }
}
```

---

### `session/SessionServiceTest.kt` (test, CRUD)

**Analog:** `src/test/kotlin/org/example/muvimatchr/session/SessionRepositoryTest.kt` (full file, 35 lines) — direct structural template: extend `PostgresTestSupport`, `@Autowired lateinit var` the repository/service under test, assert with JUnit 5 `Assertions`.

```kotlin
package org.example.muvimatchr.session

import org.example.muvimatchr.support.PostgresTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException

class SessionRepositoryTest : PostgresTestSupport() {

    @Autowired
    lateinit var sessionRepository: SessionRepository

    @Test
    fun `saved session is found by generated id with matching join code`() {
        val saved = sessionRepository.save(Session(joinCode = "ROUND1"))

        val found = sessionRepository.findById(saved.id!!)

        assertTrue(found.isPresent)
        assertEquals("ROUND1", found.get().joinCode)
    }

    @Test
    fun `duplicate join code is rejected by the database on flush`() {
        sessionRepository.saveAndFlush(Session(joinCode = "DUPE01"))

        assertThrows(DataIntegrityViolationException::class.java) {
            sessionRepository.saveAndFlush(Session(joinCode = "DUPE01"))
        }
    }
}
```
**For `SessionServiceTest.kt`:** same base class/style, but `@Autowired lateinit var sessionService: SessionService`; assert two rapid `createSession()` calls never collide (SESH-01) — no need to catch `DataIntegrityViolationException` yourself since the service already retries internally; assert the returned `Session.joinCode` is non-blank and within `VARCHAR(16)`.

---

### `session/ParticipantControllerTest.kt` (test, request-response — covers SESH-02/03/04/05)

**Analog:** `test/session/SessionRepositoryTest.kt` (base-class/style) combined with `test/voting/VoteRepositoryTest.kt` (for multi-row/uniqueness-style assertions relevant to "3+ distinct participants, each with a distinct token"). Per RESEARCH.md, this test should layer `@AutoConfigureMockMvc` on top of `PostgresTestSupport` (real Postgres integration style, not repository mocking) — matching Phase 1's established "real DB, not mocks" convention rather than a `@WebMvcTest` slice test.

**Pitfall 4 (RESEARCH.md) applies directly here:** do NOT set `spring.jpa.hibernate.ddl-auto=create` or `spring.flyway.enabled=false` in this test class — continue relying on `PostgresTestSupport`'s singleton container + `ddl-auto=validate` + real Flyway migrations (V1-V4).

---

## Shared Patterns

### Test base class (Testcontainers Postgres, singleton pattern)
**Source:** `src/test/kotlin/org/example/muvimatchr/support/PostgresTestSupport.kt`
**Apply to:** All new test classes this phase (`SessionServiceTest`, `ParticipantControllerTest`, `CurrentParticipantArgumentResolverTest`)
```kotlin
@SpringBootTest
abstract class PostgresTestSupport {
    companion object {
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18").apply { start() }
    }
}
```
Extend this class directly; do not introduce a second Testcontainers configuration. Critical: it is deliberately NOT `@Testcontainers`/`@Container`-annotated (see in-file comment) — that JUnit5 lifecycle breaks when multiple test classes share the container. Copy the pattern of extension, not the underlying JUnit annotation approach.

### Flyway-owns-DDL / no Hibernate auto-DDL
**Source:** `db/migration/V1__create_session.sql`, `V2__create_participant.sql`, `V3__create_vote.sql` (all plain `CREATE TABLE`/`CREATE [UNIQUE] INDEX`, no ORM-generated DDL)
**Apply to:** `V4__add_participant_token.sql` — the only new migration this phase adds. Never edit V1-V3 in place (Pitfall 3).

### Plain Kotlin JPA entities (not data class)
**Source:** `session/Session.kt`, `session/Participant.kt`, `voting/Vote.kt` — all `@Entity class X(...)` with `@Id @GeneratedValue(strategy = GenerationType.UUID) val id: UUID? = null` as the last property
**Apply to:** The modified `Participant.kt` only (no new entities this phase) — relies on `kotlin("plugin.jpa")` for no-arg constructor; do not add manual `allOpen`/`noArg` blocks or make it a `data class`.

### Constructor injection, no field injection
**Source:** every existing repository/entity file (implicit — no `@Autowired`-on-field usage anywhere in `session/`/`voting/`)
**Apply to:** `SessionService`, `ParticipantService`, `SessionController`, `ParticipantController`, `TokenService`, `CurrentParticipantArgumentResolver`, `WebMvcConfig` — all take dependencies as primary-constructor `private val` params.

### Native-query discipline (only if raw SQL is ever needed)
**Source:** `voting/VoteRepository.kt` lines 12-29 (`upsertVote`) — named `@Param` bindings exclusively, never string interpolation
**Apply to:** Not required for `findByTokenHash` (Spring-Data-derived query suffices), but binding for any future raw-SQL addition in this phase.

## No Analog Found

Files with no close in-repo match — planner should use RESEARCH.md's Architecture Patterns / Code Examples sections (Patterns 1-3) as the primary source, since those were purpose-designed against this exact codebase's conventions:

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `session/SessionService.kt` | service | CRUD | First real service class; `LobbyService.kt`/`MovieService.kt` are in-memory legacy prototype, explicitly excluded |
| `session/ParticipantService.kt` | service | CRUD | Same as above |
| `session/SessionController.kt` | controller | request-response (REST) | First `@RestController` in the codebase; `LobbyController.kt`/`MovieVoteController.kt` are view-returning `@Controller` + `HttpSession`, explicitly not reusable per CONTEXT.md |
| `session/ParticipantController.kt` | controller | request-response (REST) | Same as above |
| `auth/TokenService.kt` | utility | transform (crypto) | First crypto/token code in the codebase |
| `auth/CurrentParticipant.kt` | utility (annotation) | — | First custom argument-resolver annotation |
| `auth/CurrentParticipantArgumentResolver.kt` | middleware | request-response | First `HandlerMethodArgumentResolver` in the codebase |

## Metadata

**Analog search scope:** `src/main/kotlin/org/example/muvimatchr/**`, `src/main/resources/db/migration/**`, `src/test/kotlin/org/example/muvimatchr/**`
**Files scanned:** 15 source files, 3 migrations, 4 test files (full repo at time of mapping)
**Pattern extraction date:** 2026-09-02
