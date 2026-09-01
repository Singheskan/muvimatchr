# Phase 1: Persistence Foundation - Pattern Map

**Mapped:** 2026-09-01
**Files analyzed:** 13 (2 build/config modifications, 3 entity files, 3 Flyway migrations, 2 repository files, 4 test files, 1 compose file)
**Analogs found:** 2 / 13 (both build-config files; all code-level files have NO in-repo analog)

## Summary

This codebase has no prior persistence layer. `Lobby.kt` (`data class Participant`/`Lobby`, in-memory), `LobbyService.kt` (commented-out `mutableMapOf` fields), `MovieService.kt` (OMDb HTTP client, no DB), and `MovieVoteController.kt` (`HttpSession`-attribute voting) are the exact in-memory anti-pattern this phase eliminates — confirmed by direct inspection (see below), matching CONTEXT.md's `<code_context>` claim verbatim. There is no `repository/`, `entity/`, `db/migration/`, or Testcontainers test infrastructure anywhere in the repo. **Every JPA entity, Flyway migration, Spring Data repository, and integration test this phase creates is greenfield** — the planner must rely on RESEARCH.md's Architecture Patterns §1-4 (entity shape, migration granularity, restart test, upsert query) as the primary source of implementation pattern, not an in-repo analog.

The only two files with genuine analogs are build/config files being *modified in place*: `build.gradle.kts` and `gradle/wrapper/gradle-wrapper.properties`. Their "pattern" is their own current content plus the exact diffs specified in RESEARCH.md's Standard Stack section.

## Verified Non-Analogs (read directly, confirmed unusable)

| File | Why it's not a pattern source |
|------|-------------------------------|
| `src/main/kotlin/org/example/muvimatchr/model/Lobby.kt` | `data class Participant(val name: String, var isReady: Boolean = false)` and `data class Lobby(...)` — plain in-memory data classes, no persistence annotations, no id/UUID strategy, no relations. Using `data class` for a JPA entity is explicitly flagged as an anti-pattern in RESEARCH.md (`equals`/`hashCode` over lazy associations). Do not model `Participant`/`Session` entities after this shape. |
| `src/main/kotlin/org/example/muvimatchr/service/LobbyService.kt` | Contains commented-out `mutableMapOf` fields per CONTEXT.md — the documented anti-pattern this phase exists to remove. Not a repository-layer analog. |
| `src/main/kotlin/org/example/muvimatchr/controller/MovieVoteController.kt` | `HttpSession`-attribute-based voting logic. No DB, no repository call. Out of this phase's scope entirely (controllers are Phase 2+). |
| `src/main/kotlin/org/example/muvimatchr/service/MovieService.kt` | Calls OMDb via `RestTemplate`/HTTP, not a DB. Superseded by Phase 3's `catalog/` package, irrelevant to entities/repositories. |
| `src/test/kotlin/org/example/muvimatchr/MuviMatchrApplicationTests.kt` | Bare `@SpringBootTest` with an empty `contextLoads()` — no Testcontainers, no repository injection, no assertions. Confirms there is no existing integration-test scaffolding to imitate; `PostgresTestSupport.kt` and `RestartSurvivalTest.kt` must be built from RESEARCH.md Pattern 3, not from this file. |

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|--------------------|------|-----------|-----------------|----------------|
| `build.gradle.kts` (modified) | config | batch (build-time) | itself (current content) | exact (self-modify) |
| `gradle/wrapper/gradle-wrapper.properties` (modified) | config | batch (build-time) | itself (current content) | exact (self-modify) |
| `src/main/resources/application.properties` (modified) | config | batch (startup) | itself (current 1-line content) | exact (self-modify) |
| `docker-compose.yml` (new) | config | batch | none in repo | no analog |
| `src/main/resources/db/migration/V1__create_session.sql` (new) | migration | batch (DDL) | none in repo | no analog |
| `src/main/resources/db/migration/V2__create_participant.sql` (new) | migration | batch (DDL) | none in repo | no analog |
| `src/main/resources/db/migration/V3__create_vote.sql` (new) | migration | batch (DDL) | none in repo | no analog |
| `src/main/kotlin/org/example/muvimatchr/session/Session.kt` (new) | model (JPA entity) | CRUD | `Lobby.kt` — NOT usable (data class anti-pattern); use RESEARCH.md Pattern 1 instead | no usable analog |
| `src/main/kotlin/org/example/muvimatchr/session/Participant.kt` (new) | model (JPA entity) | CRUD | `Lobby.kt`'s `Participant` — NOT usable (data class, no persistence) | no usable analog |
| `src/main/kotlin/org/example/muvimatchr/voting/Vote.kt` (new) | model (JPA entity) | CRUD | none in repo | no analog |
| `src/main/kotlin/org/example/muvimatchr/session/SessionRepository.kt` (new) | service (Spring Data repository) | CRUD | none in repo | no analog |
| `src/main/kotlin/org/example/muvimatchr/session/ParticipantRepository.kt` (new) | service (Spring Data repository) | CRUD | none in repo | no analog |
| `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt` (new) | service (Spring Data repository) | CRUD + event-driven (upsert) | none in repo | no analog |
| `src/test/kotlin/org/example/muvimatchr/support/PostgresTestSupport.kt` (new) | test (support fixture) | batch | `MuviMatchrApplicationTests.kt` — NOT usable (empty, no fixture) | no usable analog |
| `src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt` (new) | test (integration) | request-response (context lifecycle) | `MuviMatchrApplicationTests.kt` — NOT usable (single-context only) | no usable analog |
| `src/test/kotlin/org/example/muvimatchr/session/SessionRepositoryTest.kt` (new) | test (integration) | CRUD | none in repo | no analog |
| `src/test/kotlin/org/example/muvimatchr/voting/VoteRepositoryTest.kt` (new) | test (integration) | CRUD | none in repo | no analog |

## Pattern Assignments

### `build.gradle.kts` (config, modified in place)

**Analog:** its own current content (full file read above — 45 lines).

**Current plugins block** (lines 1-6):
```kotlin
plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.3.3"
	id("io.spring.dependency-management") version "1.1.6"
}
```

**Required target state** — per RESEARCH.md Standard Stack "Installation" block: bump versions, add `kotlin("plugin.jpa")`, bump `io.spring.dependency-management` to `1.1.7`:
```kotlin
plugins {
	kotlin("jvm") version "2.3.20"
	kotlin("plugin.spring") version "2.3.20"
	kotlin("plugin.jpa") version "2.3.20"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}
```

**Current dependencies block** (lines 20-33) — preserve all existing entries (thymeleaf, web, jackson-module-kotlin, kotlin-reflect, devtools, spring-boot-starter-test, kotlin-test-junit5, junit-platform-launcher, websocket) and additively insert the persistence dependency set exactly as specified in RESEARCH.md Standard Stack "Installation" block (`spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `flyway-database-postgresql`, `postgresql`, plus `testImplementation`/testcontainers trio). Do not remove any existing dependency — this phase is additive to the web/websocket/thymeleaf stack, which later phases still need.

**Existing `kotlin { compilerOptions {...} }` and `tasks.withType<Test> { useJUnitPlatform() }` blocks** (lines 37-45) — no change needed; `useJUnitPlatform()` already covers the new Testcontainers/JUnit5 tests.

---

### `gradle/wrapper/gradle-wrapper.properties` (config, modified in place)

**Analog:** its own current content (7 lines, full file read above).

**Current line 4:**
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10-bin.zip
```

**Required target state** (per RESEARCH.md Standard Stack table — Boot 4.1's Gradle plugin requires Gradle 8.14+):
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip
```
All other lines (`distributionBase`, `distributionPath`, `networkTimeout`, `validateDistributionUrl`, `zipStoreBase`, `zipStorePath`) are unchanged.

---

### `src/main/resources/application.properties` (config, modified in place)

**Analog:** its own current content (1 line: `spring.application.name=MuviMatchr`).

**Required additions** — datasource, JPA, Flyway config per RESEARCH.md Architecture Patterns and STACK.md ("`ddl-auto=validate`" only, never `update`/`create-drop`; Flyway owns schema). For local `bootRun` (non-test) use, point at the `docker-compose.yml` Postgres service; for tests, `PostgresTestSupport.kt`/`RestartSurvivalTest.kt` supply their own Testcontainers-backed connection properties (see RESEARCH.md Pattern 3) rather than reading this file. Suggested additive block (exact values are this phase's own discretion per CONTEXT.md, keep consistent with `docker-compose.yml`):
```properties
spring.application.name=MuviMatchr

spring.datasource.url=jdbc:postgresql://localhost:5432/muvimatchr
spring.datasource.username=muvimatchr
spring.datasource.password=muvimatchr
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
```

---

### `docker-compose.yml` (new, no analog)

No existing compose file in repo. Build from RESEARCH.md D-02 + Pitfall 4 (mount `/var/lib/postgresql`, not `/var/lib/postgresql/data`, if using `postgres:18` with a named volume) and Recommended Project Structure. Match the Postgres tag used by Testcontainers (`postgres:18` per RESEARCH.md Open Question 2 recommendation) and the credentials used in `application.properties` above.

---

### Flyway migrations — `V1__create_session.sql`, `V2__create_participant.sql`, `V3__create_vote.sql` (new, no analog)

No `db/migration/` directory exists in repo. Use RESEARCH.md Architecture Patterns §Pattern 2 verbatim as the migration content template (per-table granularity, `session_id` FK/index ordering, unique index on `join_code`, unique constraint on `(session_id, participant_id, movie_id)`). This is the canonical source — copy directly, no in-repo precedent exists to reconcile against.

---

### `session/Session.kt`, `session/Participant.kt`, `voting/Vote.kt` (new JPA entities, no usable analog)

**Rejected analog:** `src/main/kotlin/org/example/muvimatchr/model/Lobby.kt` — `data class Participant(val name: String, var isReady: Boolean = false)`. Do not copy this shape: it is a `data class` (explicitly flagged anti-pattern for JPA entities in RESEARCH.md — auto-generated `equals`/`hashCode` breaks Hibernate lazy-loading/proxy identity), has no `@Entity`/`@Id`/`@Column` annotations, no UUID strategy, and models "ready" state that has no analog in the new `Session`/`Participant`/`Vote` shape at all.

**Correct source:** RESEARCH.md Architecture Patterns §Pattern 1 (`Vote` entity shown in full) — plain `class` (not `data class`), `val` constructor properties, `@Entity`/`@Table`/`@ManyToOne(fetch = FetchType.LAZY)`/`@JoinColumn`, `@Id @GeneratedValue(strategy = GenerationType.UUID)`, relies on `kotlin("plugin.jpa")` for no-arg constructor + all-open (no manual `allOpen` block). Apply the same shape to `Session` (`id: UUID`, `joinCode: String`, `createdAt: Instant`) and `Participant` (`id: UUID`, `session: Session` FK, `displayName: String`, `createdAt: Instant`) per ARCHITECTURE.md's already-specified field list (referenced in CONTEXT.md canonical_refs).

---

### `session/SessionRepository.kt`, `session/ParticipantRepository.kt`, `voting/VoteRepository.kt` (new Spring Data repositories, no analog)

No `repository/` package or `JpaRepository` usage exists anywhere in repo (`grep -r JpaRepository src` returns nothing). Build `SessionRepository`/`ParticipantRepository` as plain `interface X : JpaRepository<Entity, UUID>` with no extra methods needed for this phase. Build `VoteRepository` from RESEARCH.md Architecture Patterns §Pattern 4 verbatim (native `INSERT ... ON CONFLICT DO UPDATE` upsert, `@Modifying`/`@Transactional`, explicit `CAST(:param AS uuid)` for the documented Spring Data JPA UUID-native-query binding pitfall).

---

### `support/PostgresTestSupport.kt`, `RestartSurvivalTest.kt`, `session/SessionRepositoryTest.kt`, `voting/VoteRepositoryTest.kt` (new tests, no usable analog)

**Rejected analog:** `src/test/kotlin/org/example/muvimatchr/MuviMatchrApplicationTests.kt` — bare `@SpringBootTest` with an empty `contextLoads()` test body. No Testcontainers annotations, no repository injection, no fixture/support class pattern to extract. Confirms zero existing integration-test infrastructure.

**Correct source:**
- `RestartSurvivalTest.kt`: RESEARCH.md Architecture Patterns §Pattern 3 verbatim — `@Testcontainers` class, static `@Container` `PostgreSQLContainer` field, `SpringApplicationBuilder(...).properties(...).run()` for two independently-managed `ConfigurableApplicationContext`s, explicit `.close()` + `@AfterEach` cleanup. Note the import is `org.testcontainers.postgresql.PostgreSQLContainer` (Testcontainers 2.0.5 package, not the pre-2.0 `org.testcontainers.containers` package — see RESEARCH.md Pitfall 1).
- `SessionRepositoryTest.kt` / `VoteRepositoryTest.kt`: ordinary `@SpringBootTest` (or `@DataJpaTest`) + `@Testcontainers`/`@ServiceConnection`-backed single context (not the double-context pattern — that's reserved for `RestartSurvivalTest` only, per RESEARCH.md Pattern 3 "When to use"). Test the unique constraints (`join_code`, `(session_id, participant_id, movie_id)`) and the upsert query's update-in-place behavior.
- `PostgresTestSupport.kt`: shared static `@Container` Postgres instance/config helper reused by `SessionRepositoryTest`/`VoteRepositoryTest` (not by `RestartSurvivalTest`, which manages its own lifecycle per Pattern 3's "When to use" note).

## Shared Patterns

### JPA entity shape (plain class, UUID generation, LAZY associations)
**Source:** RESEARCH.md Architecture Patterns §Pattern 1 (full `Vote` example, in-repo `Lobby.kt` explicitly rejected as a counter-example — see above).
**Apply to:** `Session.kt`, `Participant.kt`, `Vote.kt`.

### Flyway migration granularity and constraint placement
**Source:** RESEARCH.md Architecture Patterns §Pattern 2 (SQL verbatim for all three tables).
**Apply to:** `V1__create_session.sql`, `V2__create_participant.sql`, `V3__create_vote.sql`.

### Native upsert query with UUID cast
**Source:** RESEARCH.md Architecture Patterns §Pattern 4.
**Apply to:** `VoteRepository.kt` only (no other repository needs a native query in this phase).

### `ddl-auto=validate` only, Flyway owns schema
**Source:** RESEARCH.md STACK.md reference ("What NOT to Use" — no `update`/`create-drop`) + CONTEXT.md D-03 context.
**Apply to:** `application.properties`, `RestartSurvivalTest.kt`'s `.properties(...)` call, any `@DataJpaTest`/`@SpringBootTest` config used by repository tests.

## No Analog Found

All new code files (entities, repositories, migrations, tests, compose file) have no analog in this codebase — confirmed by direct inspection of every existing `.kt` file under `src/`. This is expected and matches CONTEXT.md's explicit claim ("None. ... None worth carrying forward."). The planner should treat RESEARCH.md's Architecture Patterns §1-4 as the primary implementation source for all of these files, not an in-repo analog.

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `docker-compose.yml` | config | batch | No compose file exists in repo; greenfield infra. |
| `src/main/resources/db/migration/V1-V3__*.sql` | migration | batch (DDL) | No `db/migration/` directory exists; no prior Flyway usage. |
| `session/Session.kt`, `session/Participant.kt`, `voting/Vote.kt` | model | CRUD | `Lobby.kt` is a rejected anti-pattern (data class, no persistence); no other entity exists. |
| `session/SessionRepository.kt`, `session/ParticipantRepository.kt`, `voting/VoteRepository.kt` | service | CRUD / event-driven | No `JpaRepository` usage anywhere in repo. |
| `support/PostgresTestSupport.kt`, `RestartSurvivalTest.kt`, `session/SessionRepositoryTest.kt`, `voting/VoteRepositoryTest.kt` | test | request-response / CRUD | `MuviMatchrApplicationTests.kt` is an empty placeholder with no fixture, Testcontainers, or assertion pattern to extract. |

## Metadata

**Analog search scope:** entire repo (`src/main`, `src/test`, `build.gradle.kts`, `gradle/wrapper/`, `application.properties`) — confirmed via `find src -type f` and direct reads of every `.kt` file referenced in CONTEXT.md's `<code_context>` section plus the sole existing test file.
**Files scanned:** 13 source files + 2 build/config files + 1 test file = 16.
**Pattern extraction date:** 2026-09-01.
