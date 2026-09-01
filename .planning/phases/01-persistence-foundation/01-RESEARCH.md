# Phase 1: Persistence Foundation - Research

**Researched:** 2026-09-01
**Domain:** Spring Boot 4.1 / Kotlin 2.3 JPA + Flyway persistence layer, Testcontainers-based restart-survival testing
**Confidence:** HIGH (all dependency coordinates and version pins verified directly against Maven Central / the Spring Boot 4.1.1 BOM POM; the restart-test mechanics are MEDIUM — synthesized from documented Spring Boot APIs since no tutorial demonstrates this exact "two contexts, one container" pattern)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Bump Spring Boot 3.3.3 → 4.1.x and Kotlin 1.9.25 → 2.3.20 as part of this phase, before/alongside adding JPA, Flyway, and the Postgres driver. Do not add the persistence stack on top of the current 3.3.3/1.9.25 baseline. — **Reversibility:** costly.
  - Watch for the known Boot 4 migration gotchas already flagged in `.planning/research/STACK.md`: JSpecify (`org.jspecify.annotations`) replacing Spring's own `@Nullable`/`@NonNull` can surface new Kotlin nullability mismatches on code that compiled fine under Boot 3.x.
- **D-02:** Add a `docker-compose.yml` with a local Postgres service in this phase, even though there are no endpoints yet to manually exercise via `bootRun`. — **Reversibility:** reversible.
  - Local Postgres version in compose should match the version targeted for Testcontainers and production (Postgres 17 or 18 per STACK.md) to avoid dialect surprises.
- **D-03:** The success-criteria test for "data survives a restart" must be a literal full-context restart: write rows via one Spring context wired to a Testcontainers Postgres container, shut that context down, bring up a fresh Spring context pointed at the *same* container, then read the rows back. A repository-level round-trip (write, `EntityManager.clear()`, read in the same running app) is explicitly NOT sufficient proof for this phase.
  - This is the direct test-level fix for the prior prototype's actual failure mode (in-memory `HttpSession`/`Map`-based state, which by definition cannot survive a process restart).

### Claude's Discretion

- Exact Flyway migration file granularity (one migration for all three tables vs. one per table).
- Primary key strategy details (UUID vs bigint) beyond what `.planning/research/ARCHITECTURE.md` already specifies (UUID for Session/Participant ids, raw `Long` TMDB id for `Vote.movieId`, no local `Movie`/`CachedMovie` entity referenced from `Vote` — that's Phase 3's `catalog/` package).
- Join code column type/length (Phase 2 owns actual code-generation logic; this phase only needs a unique, indexed column to satisfy success criterion #3).
- Package structure — follow the package-by-feature layout already specified in `.planning/research/ARCHITECTURE.md` (`session/`, `voting/`, not `controller/`/`service/`/`model/`).

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope. (Note: participant "active"/"left" status modeling was intentionally NOT discussed here — `.planning/STATE.md` already flags it as an explicit product decision for Phase 2/4 planning, not Phase 1's schema-only scope.)
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| RELI-01 | Session/participant/vote data lives in a real database and survives a server restart | Standard Stack (PostgreSQL + Spring Data JPA + Flyway), Code Examples §"Restart-survival test", Architecture Patterns §"Full-context restart test" — this is the entirety of Phase 1's scope |
</phase_requirements>

## Summary

Phase 1 replaces the prior in-memory prototype's persistence with a real Postgres-backed JPA schema, but the schema work itself is the *easy* part — it's a near-verbatim application of `.planning/research/ARCHITECTURE.md`'s already-specified `Session`/`Participant`/`Vote` shapes. The actual risk in this phase is entirely in the **framework upgrade** (Spring Boot 3.3.3→4.1.1, Kotlin 1.9.25→2.3.20) that D-01 requires happen first, and in correctly implementing the **unusual "kill the context, start a fresh one" restart test** that D-03 requires. Both are now fully version-pinned below, verified directly against Maven Central and the Spring Boot 4.1.1 BOM POM (not training-data guesses) — several exact artifact coordinates and package names changed between the 3.x-era tutorials most search results surface and what Boot 4.1.1 actually resolves, and getting these wrong would produce confusing dependency-resolution or `NoClassDefFoundError` failures rather than a clean compile error.

Three non-obvious, version-specific facts drive most of the plan's shape: (1) Spring Boot 4.x requires a **dedicated `spring-boot-starter-flyway` starter**, not the bare `flyway-core` dependency most existing tutorials show; (2) Boot 4.1.1's BOM pins **Testcontainers 2.0.5**, which renamed the Postgres module artifact and relocated `PostgreSQLContainer` to a new package — code copied from most existing Testcontainers-Postgres tutorials (which target Testcontainers 1.x) will not compile; (3) Kotlin 2.3.20's `kotlin("plugin.jpa")` Gradle plugin now auto-applies the `all-open` plugin with a built-in JPA preset, so the classic "why is my `@Entity` final / no-arg constructor missing" Kotlin+JPA pain points that dominate older blog posts are already solved by the plugin alone — no manual `allOpen { annotation(...) }` block needed.

The one blocking environment gap discovered during this research: **Docker is not installed on this machine** (no `docker`, `colima`, `podman`, or `orbstack` binary found, no Docker-related app in `/Applications`). Both D-02 (local Postgres via `docker-compose`) and D-03 (Testcontainers-backed restart test — this phase's primary proof of correctness) require a Docker-API-compatible daemon. This is not a "pick a fallback" situation — Testcontainers has no non-Docker execution mode — so the plan must include an early setup/checkpoint step to get a Docker daemon running before any repository test can be written or run.

**Primary recommendation:** Bump the Gradle wrapper to 8.14.3 first (a prerequisite the CONTEXT.md decisions don't mention but the Boot 4.1 Gradle plugin requires), then bump Kotlin to 2.3.20 and Boot to 4.1.1 in one `build.gradle.kts` commit, add `kotlin("plugin.jpa")`, then add the persistence dependency set using the exact BOM-verified coordinates in the Standard Stack table below — do not copy dependency blocks from pre-2026 tutorials without cross-checking against this table.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Session/Participant/Vote schema definition | Database / Storage | — | Flyway-versioned DDL is the single source of truth for schema shape; this phase creates it. |
| Entity ↔ row mapping | API / Backend (JPA layer) | Database / Storage | `@Entity` classes are backend-tier code, but their shape is fully dictated by the DB schema (Flyway owns `ddl-auto=validate`, never the reverse). |
| Unique-constraint enforcement (join code, vote tuple) | Database / Storage | API / Backend | The constraint must exist at the DB level (survives any application bug); the upsert-path code in the backend tier is what makes violating it gracefully unlikely, not what prevents it. |
| Restart-survival proof | API / Backend (test code) + Database / Storage | — | The test lives in backend-tier test code, but what it proves is a property of the Database/Storage tier — that a fresh backend process reconnecting to the same DB sees the same data. |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|---------------|
| Kotlin | 2.3.20 | Backend language | Locked by D-01. `[VERIFIED: repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml]` — 2.3.20 is a published release (2.3.21 also exists as a newer patch, but D-01 pins 2.3.20 exactly; treat 2.3.21 as an option to raise with the user, not a silent substitution). |
| Spring Boot | 4.1.1 | Backend framework | Locked by D-01 ("4.1.x"). `[VERIFIED: repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-flyway/maven-metadata.xml]` — 4.1.1 is the latest non-milestone 4.1.x release (published 2026-08-20; 4.2.0-M1 also exists but is a milestone, not GA). |
| Gradle (wrapper) | 8.14.3 | Build tool | Not explicitly decided in CONTEXT.md but required: the current wrapper is 8.10 `[VERIFIED: gradle/wrapper/gradle-wrapper.properties:4]` (`distributionUrl=...gradle-8.10-bin.zip`), and Spring Boot's Gradle plugin "requires Gradle 8.x (8.14 or later) or 9.x" `[CITED: docs.spring.io/spring-boot/gradle-plugin/index.html]`. Kotlin 2.3.20 is compatible "with Gradle 7.6.3 through 9.3.0" `[CITED: kotlinlang.org/docs/whatsnew2320.html]`, so 8.14.3 (verified released, non-broken: `[VERIFIED: services.gradle.org/versions/all]`) is the safest pin — it clears Boot's floor without leaving Kotlin's tested range. |
| `org.springframework.boot:spring-boot-starter-data-jpa` | 4.1.1 (managed) | ORM starter for Session/Participant/Vote entities | `[VERIFIED: repo1.maven.org spring-boot-dependencies:4.1.1 POM — read directly, see Sources]` — declares `<artifactId>spring-boot-starter-data-jpa</artifactId><version>4.1.1</version>`. Pulls in Hibernate 7.4.5.Final (`hibernate.version=7.4.5.Final` in same POM). |
| `org.springframework.boot:spring-boot-starter-flyway` | 4.1.1 (managed) | Flyway auto-configuration starter | **Not the bare `flyway-core` dependency.** `[VERIFIED: repo1.maven.org spring-boot-dependencies:4.1.1 POM]` lists `spring-boot-starter-flyway` version 4.1.1 as a managed starter. The official Boot 4.0 migration guide states this explicitly: *"if you are using Flyway or Liquibase you used to only have the relevant third-party dependency. You now need to replace that with `spring-boot-starter-flyway` or `spring-boot-starter-liquibase`, respectively."* `[CITED: github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide]`. Using bare `flyway-core` in Boot 4.x will NOT get Flyway auto-configured/run at startup. |
| `org.flywaydb:flyway-database-postgresql` | 12.4.0 (managed) | Flyway's Postgres-dialect module | `[VERIFIED: repo1.maven.org spring-boot-dependencies:4.1.1 POM]` — `flyway.version=12.4.0`, and `flyway-database-postgresql` is one of the managed `flyway.version`-pinned artifacts in that same POM. Still required in addition to the starter above: since Flyway 10, database dialect support was split out of `flyway-core` into per-DB modules. |
| `org.postgresql:postgresql` | 42.7.13 (managed) | JDBC driver | `[VERIFIED: repo1.maven.org spring-boot-dependencies:4.1.1 POM]` — `postgresql.version=42.7.13`. |
| `org.testcontainers:testcontainers-postgresql` | 2.0.5 (managed via `testcontainers-bom`) | Postgres Testcontainers module | **Not `org.testcontainers:postgresql`** (the 1.x artifact id most tutorials show — still exists at 1.21.4 but is NOT what Boot 4.1.1's BOM manages). `[VERIFIED: repo1.maven.org spring-boot-dependencies:4.1.1 POM]` — `testcontainers.version=2.0.5`, imports `testcontainers-bom:2.0.5`. `[VERIFIED: repo1.maven.org/maven2/org/testcontainers/testcontainers-postgresql/maven-metadata.xml]` confirms this artifact id exists at 2.0.5. Testcontainers 2.0 renamed all modules to a `testcontainers-` prefix and moved `PostgreSQLContainer` from `org.testcontainers.containers` to `org.testcontainers.postgresql` `[VERIFIED: raw.githubusercontent.com/testcontainers/testcontainers-java/main/modules/postgresql/.../org/testcontainers/postgresql/PostgreSQLContainer.java — package declaration read directly]`. |
| `org.testcontainers:testcontainers-junit-jupiter` | 2.0.5 (managed) | JUnit 5 integration (`@Testcontainers`, `@Container`) | `[VERIFIED: repo1.maven.org/maven2/org/testcontainers/testcontainers-junit-jupiter/maven-metadata.xml]`. The `@Testcontainers`/`@Container` annotation package did **not** move in the 2.x rename — still `org.testcontainers.junit.jupiter` `[VERIFIED: raw.githubusercontent.com/testcontainers/testcontainers-java/main/modules/junit-jupiter/.../org/testcontainers/junit/jupiter/Testcontainers.java — package declaration read directly]`. |
| `org.springframework.boot:spring-boot-testcontainers` | 4.1.1 (managed) | Spring's `@ServiceConnection` integration | `[VERIFIED: repo1.maven.org spring-boot-dependencies:4.1.1 POM]`. Provides `org.springframework.boot.testcontainers.service.connection.ServiceConnection`, confirmed present in the Boot 4.1.0 API docs `[CITED: docs.spring.io/spring-boot/api/java/org/springframework/boot/testcontainers/service/connection/ServiceConnection.html]`. |
| `kotlin("plugin.jpa")` (Gradle plugin, shorthand for `org.jetbrains.kotlin.plugin.jpa`) | 2.3.20 | Auto-generates no-arg constructors + all-open for `@Entity`/`@Embeddable`/`@MappedSuperclass` | As of Kotlin 2.3.20 this plugin **automatically applies the `all-open` compiler plugin with a built-in JPA preset**, in addition to the `no-arg` plugin it always applied — `[CITED: kotlinlang.org/docs/whatsnew2320.html]`: *"The kotlin.plugin.jpa plugin now automatically applies the all-open compiler plugin with the newly added built-in JPA preset... This ensures that lazy associations work as expected instead of causing eager loading."* No manual `allOpen { annotation("jakarta.persistence.Entity") }` block is needed with this Kotlin version — older tutorials showing that block are targeting pre-2.3.20 Kotlin. |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `io.spring.dependency-management` (Gradle plugin) | 1.1.7 | Imports `spring-boot-dependencies` BOM for version management | `[VERIFIED: repo1.maven.org/maven2/io/spring/gradle/dependency-management-plugin/maven-metadata.xml]`. Not built into the Boot 4.x Gradle plugin — still needs explicit `id("io.spring.dependency-management") version "1.1.7"`, same as the project's current setup, just bump the version. Gradle's native `platform()`/`enforcedPlatform()` BOM import is a documented alternative (faster builds) but changing build-system approach mid-upgrade adds risk for no requirement-driven benefit here — keep the existing plugin. |
| `org.testcontainers:junit-jupiter` / `org.testcontainers:postgresql` (1.x artifact ids) | — | — | **Do not use.** These are the pre-2.0 artifact ids. Mixing them with a Boot 4.1.1 project (whose BOM manages `testcontainers-bom:2.0.5`) risks either an unresolvable/unmanaged-version dependency or two different major Testcontainers versions on the classpath. Use the `testcontainers-*`-prefixed 2.x artifact ids instead. |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `io.spring.dependency-management` plugin | Gradle native `platform(...)` BOM import | Slightly faster builds, no property-based version overrides; not worth a build-system change mid framework-upgrade for this phase. |
| Gradle 8.14.3 | Gradle 9.3.0 (top of Kotlin 2.3.20's tested range) | Also valid per Boot's "8.x (8.14+) or 9.x" statement; 8.14.3 chosen as the smaller delta from the current 8.10 wrapper and to stay mid-range rather than at the edge of Kotlin's tested compatibility window. |
| `spring-boot-starter-data-jpa` version left unmanaged (relying on BOM only, no explicit version) | Pin explicitly | Not needed — with `io.spring.dependency-management` importing `spring-boot-dependencies:4.1.1`, omitting an explicit version on any Spring-Boot-managed artifact is correct/standard and was already the project's existing pattern. |

**Installation:**
```kotlin
// build.gradle.kts — plugins block
plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.spring") version "2.3.20"
    kotlin("plugin.jpa") version "2.3.20"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

// dependencies block — additions for this phase
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}
```

```properties
# gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip
```

**Version verification performed:** Every artifact/version above was cross-checked directly against `repo1.maven.org` (Maven Central's own file listing and, for Spring-managed artifacts, the actual `spring-boot-dependencies-4.1.1.pom` file content) during this research session — not taken from web-search summaries alone. Web search results for this exact topic frequently describe the pre-4.0 (`flyway-core` directly, `org.testcontainers:postgresql`) pattern because most existing tutorials predate Boot 4.0's GA; those patterns will not auto-configure correctly on this project's pinned versions.

## Package Legitimacy Audit

> This phase's dependencies are all Maven/Gradle (Java ecosystem) coordinates. The automated `package-legitimacy check` seam only supports `npm`/`pypi`/`crates` ecosystems (confirmed by invoking it: `Error: Usage: gsd-tools package-legitimacy check --ecosystem <npm|pypi|crates> ...`). In its place, every artifact recommended above was manually verified against Maven Central's own repository file listing (`repo1.maven.org/maven2/.../maven-metadata.xml`) and, for Spring-managed artifacts, by downloading and reading the actual `spring-boot-dependencies-4.1.1.pom` — a stronger check than the automated npm/pypi/crates heuristics (age/downloads/source-repo signals), since it confirms the exact artifact+version is the one Spring Boot 4.1.1 itself will resolve.

| Package | Registry | Verification | Verdict | Disposition |
|---------|----------|--------------|---------|-------------|
| `org.springframework.boot:spring-boot-starter-data-jpa:4.1.1` | Maven Central | Read directly from `spring-boot-dependencies:4.1.1` POM | OK | Approved |
| `org.springframework.boot:spring-boot-starter-flyway:4.1.1` | Maven Central | Read directly from `spring-boot-dependencies:4.1.1` POM + confirmed file listing exists (2026-08-20) | OK | Approved |
| `org.flywaydb:flyway-database-postgresql:12.4.0` | Maven Central | Read directly from `spring-boot-dependencies:4.1.1` POM | OK | Approved |
| `org.postgresql:postgresql:42.7.13` | Maven Central | Read directly from `spring-boot-dependencies:4.1.1` POM | OK | Approved |
| `org.testcontainers:testcontainers-postgresql:2.0.5` | Maven Central | `maven-metadata.xml` file listing confirmed; source package confirmed via GitHub | OK | Approved |
| `org.testcontainers:testcontainers-junit-jupiter:2.0.5` | Maven Central | `maven-metadata.xml` file listing confirmed | OK | Approved |
| `org.springframework.boot:spring-boot-testcontainers:4.1.1` | Maven Central | Read directly from `spring-boot-dependencies:4.1.1` POM | OK | Approved |
| `io.spring.dependency-management:1.1.7` (Gradle plugin) | Gradle Plugin Portal / Maven Central | `maven-metadata.xml` file listing confirmed | OK | Approved |

**Packages removed due to [SLOP] verdict:** none.
**Packages flagged as suspicious [SUS]:** none — all artifacts are long-established, first-party Spring/Testcontainers/PostgreSQL modules with direct BOM/registry confirmation, not novel or unfamiliar packages.

## Architecture Patterns

### System Architecture Diagram

```
                    ┌───────────────────────────────────────────┐
                    │      Test code (this phase's only          │
                    │      "client" — no controllers yet)         │
                    │  SessionRepositoryTest, VoteRepositoryTest, │
                    │  RestartSurvivalTest                        │
                    └───────────────────┬─────────────────────────┘
                                        │ Spring context #1: write
                    ┌───────────────────▼─────────────────────────┐
                    │   Spring Data JPA repositories                │
                    │   SessionRepository, ParticipantRepository,   │
                    │   VoteRepository (incl. native upsert query)  │
                    └───────────────────┬─────────────────────────┘
                                        │ Hibernate (JPA provider)
                    ┌───────────────────▼─────────────────────────┐
                    │   JDBC (org.postgresql:postgresql driver)      │
                    └───────────────────┬─────────────────────────┘
                                        │
                    ┌───────────────────▼─────────────────────────┐
                    │   Testcontainers PostgreSQL container          │
                    │   (postgres:18, started once, shared by both  │
                    │    Spring contexts in the restart test)        │
                    └───────────────────▲─────────────────────────┘
                                        │ same container, new connection
                    ┌───────────────────┴─────────────────────────┐
                    │   Spring context #2 (fresh, context #1        │
                    │   explicitly .close()'d first) — read-back     │
                    └─────────────────────────────────────────────┘

     Schema provenance (both contexts read the same, Flyway-created schema):
     Flyway migrations (src/main/resources/db/migration/V*__*.sql)
        → applied on context startup (ddl-auto=validate only verifies, never writes)
        → tables: session, participant, vote (+ unique constraints)
```

### Recommended Project Structure

```
src/main/kotlin/org/example/muvimatchr/
├── session/
│   ├── Session.kt              # JPA entity — UUID id, join_code, created_at
│   ├── Participant.kt          # JPA entity — UUID id, session FK, display_name
│   └── SessionRepository.kt / ParticipantRepository.kt
├── voting/
│   ├── Vote.kt                 # JPA entity — UUID id, session FK, participant FK, movieId: Long
│   └── VoteRepository.kt       # includes native upsertVote query
└── MuviMatchrApplication.kt
src/main/resources/db/migration/
├── V1__create_session.sql
├── V2__create_participant.sql
└── V3__create_vote.sql
src/test/kotlin/org/example/muvimatchr/
├── session/SessionRepositoryTest.kt
├── voting/VoteRepositoryTest.kt
├── RestartSurvivalTest.kt      # the D-03 full-context-restart proof
└── support/PostgresTestSupport.kt  # shared static container + Flyway/JPA test config helpers
docker-compose.yml               # local Postgres 18, matches Testcontainers/prod version
```

No `controller/`, `service/`, or `catalog/`/`realtime/` packages yet — those belong to later phases per the CONTEXT.md phase boundary (this phase is entities + migrations + repository tests only).

### Pattern 1: Kotlin JPA entity shape (plain class, not `data class`)

**What:** JPA entities are plain Kotlin classes with `val` constructor properties, `@Entity`, and rely on the `kotlin("plugin.jpa")` Gradle plugin (not manual `open`/`allOpen` config) for the no-arg constructor + open-class requirements Hibernate needs.
**When to use:** All three entities (`Session`, `Participant`, `Vote`).
**Why not `data class`:** `data class` auto-generates `equals()`/`hashCode()`/`toString()` from all constructor properties, which is a known Kotlin+JPA footgun — it evaluates `equals`/`hashCode` before lazy-loaded associations are initialized and can break Hibernate's identity/proxy comparisons. `.planning/research/ARCHITECTURE.md`'s own `Vote` example already uses a plain `class`, not `data class` — follow that precedent.

**Example:**
```kotlin
// Source: synthesized from .planning/research/ARCHITECTURE.md Pattern 1 (Vote shape)
// + JPA 3.1/Jakarta EE 11 GenerationType.UUID (portable since JPA 3.1,
//   supported by Hibernate since 6.2 — [CITED: jakarta.ee/specifications/persistence/3.2/apidocs])
// + Kotlin 2.3.20 kotlin.plugin.jpa auto-allOpen (no manual allOpen block needed)
package org.example.muvimatchr.voting

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "vote",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_vote_session_participant_movie",
            columnNames = ["session_id", "participant_id", "movie_id"],
        )
    ],
)
class Vote(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    val session: Session,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id", nullable = false)
    val participant: Participant,

    @Column(name = "movie_id", nullable = false)
    val movieId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var choice: VoteChoice,

    @Column(name = "voted_at", nullable = false)
    var votedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null
}

enum class VoteChoice { LIKE, PASS }
```

### Pattern 2: Flyway migration granularity — one file per table

**What:** `V1__create_session.sql`, `V2__create_participant.sql`, `V3__create_vote.sql`, each creating exactly one table (plus its indexes/constraints).
**When to use:** This phase's three tables. Left to Claude's discretion per CONTEXT.md; recommending per-table granularity because Participant and Vote both FK to Session, so ordering must be enforced anyway (Flyway applies in version order) — per-table files make that dependency ordering self-documenting, and keep each migration's diff reviewable in isolation.
**Example:**
```sql
-- Source: synthesized from ARCHITECTURE.md schema shape + Scaling Priorities
--         (session_id index, unique join code, unique vote tuple)
-- V1__create_session.sql
CREATE TABLE session (
    id UUID PRIMARY KEY,
    join_code VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_session_join_code ON session (join_code);

-- V2__create_participant.sql
CREATE TABLE participant (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES session (id),
    display_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_participant_session_id ON participant (session_id);

-- V3__create_vote.sql
CREATE TABLE vote (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES session (id),
    participant_id UUID NOT NULL REFERENCES participant (id),
    movie_id BIGINT NOT NULL,
    choice VARCHAR(10) NOT NULL,
    voted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vote_session_participant_movie UNIQUE (session_id, participant_id, movie_id)
);
CREATE INDEX idx_vote_session_id ON vote (session_id);
```

### Pattern 3: Full-context restart test (D-03)

**What:** Two independently-created `ConfigurableApplicationContext`s pointed at the same Testcontainers container — NOT `@SpringBootTest`'s managed/cached context, and NOT `@DirtiesContext` (which controls Spring's *test-framework* context cache eviction between test *methods/classes*, not a mid-test-method "close this, start a genuinely new one" restart within a single test).
**When to use:** Exactly once, for the restart-survival proof (success criterion #1). Ordinary repository tests (upsert behavior, constraint violations) should use a normal single `@SpringBootTest` (or `@DataJpaTest`) — don't pay the double-context-startup cost everywhere.
**Why `@SpringBootTest` alone is insufficient:** Spring's `TestContext` framework caches `ApplicationContext`s by configuration key and hands back the *same* context to any test with a matching key (that's a deliberate performance feature). Even `@DirtiesContext` only marks a context for eviction from that cache — it doesn't give you a handle to explicitly close context A and observe context B as a *separate*, freshly-constructed object within one test method. To prove "restart," you need that separation directly.

**Example:**
```kotlin
// Source: synthesized from documented Spring Boot APIs — SpringApplicationBuilder.properties()/.run()
// [CITED: docs.spring.io/spring-boot/api/java/org/springframework/boot/builder/SpringApplicationBuilder.html]
// + Testcontainers static-container-shared-across-contexts pattern
// [CITED: testcontainers.com/guides/testing-spring-boot-rest-api-using-testcontainers/]
// No single tutorial demonstrates this exact "two contexts, one container, in one test method"
// combination — this composes two independently-documented APIs; verify end-to-end when planning executes.
package org.example.muvimatchr

import org.example.muvimatchr.session.Session
import org.example.muvimatchr.session.SessionRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertj.Assertions.assertThat // or org.assertj.core.api.Assertions
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers
class RestartSurvivalTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18")
    }

    private var activeContext: ConfigurableApplicationContext? = null

    @AfterEach
    fun tearDown() {
        activeContext?.close()
    }

    private fun startContext(): ConfigurableApplicationContext =
        SpringApplicationBuilder(MuviMatchrApplication::class.java)
            .properties(
                "spring.datasource.url=${postgres.jdbcUrl}",
                "spring.datasource.username=${postgres.username}",
                "spring.datasource.password=${postgres.password}",
                "spring.jpa.hibernate.ddl-auto=validate",
            )
            .run()
            .also { activeContext = it }

    @Test
    fun `session written before restart is readable after a fresh context starts`() {
        // --- Context #1: write ---
        val context1 = startContext()
        val sessionId = context1.getBean(SessionRepository::class.java)
            .save(Session(joinCode = "ABC123"))
            .id!!
        context1.close()
        activeContext = null // already closed; avoid double-close in @AfterEach

        // --- Context #2: fresh, same container, read back ---
        val context2 = startContext()
        val found = context2.getBean(SessionRepository::class.java).findById(sessionId)

        assertThat(found).isPresent
        assertThat(found.get().joinCode).isEqualTo("ABC123")
    }
}
```

### Pattern 4: Upsert-on-conflict vote write (repository-level proof)

**What:** A native `INSERT ... ON CONFLICT DO UPDATE` query on `VoteRepository`, tested directly at the repository level (no `VoteService` exists yet in this phase).
**When to use:** Satisfies success criterion #2 ("a working upsert path" for the vote unique constraint). Full vote-recording business logic belongs to Phase 4 — this phase only needs to prove the constraint + upsert mechanics work.
**Known pitfall:** UUID parameters in native `@Modifying` queries have a documented Spring Data JPA binding issue — cast explicitly in the SQL. `[CITED: github.com/spring-projects/spring-data-jpa issues #2720, #3689 — UUID/native-query parameter binding problems]`
**Example:**
```kotlin
// Source: pattern from .planning/research/ARCHITECTURE.md Pattern 2,
// adapted for Boot 4.1/Hibernate 7.4 native query UUID cast pitfall
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

### Anti-Patterns to Avoid

- **Bare `flyway-core` dependency in Boot 4.x:** Will compile, but Flyway will silently NOT auto-configure/run at startup without `spring-boot-starter-flyway`. This is the single easiest mistake to make copying from a pre-4.0 tutorial.
- **`org.testcontainers:postgresql` (1.x artifact id) alongside a Boot-4.1.1-managed classpath:** Boot's BOM manages `testcontainers-bom:2.0.5`; requesting the old artifact id either resolves an unmanaged/mismatched version or silently pulls in two Testcontainers major versions.
- **Proving "restart survival" via `@DirtiesContext` or `EntityManager.clear()`:** Explicitly rejected by D-03 — neither actually constructs a second, independent `ConfigurableApplicationContext` object within the test.
- **`data class` JPA entities:** Auto-generated `equals`/`hashCode`/`toString` over all properties is a well-documented Hibernate lazy-loading/proxy-identity footgun.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| UUID generation for entity ids | A custom ID generator / manual `UUID.randomUUID()` assignment in application code | `@GeneratedValue(strategy = GenerationType.UUID)` (portable JPA 3.1+, Hibernate 6.2+) | Standard, provider-generated, RFC 4122 v4 compliant; avoids app-code races or forgetting to set the id before `save()`. |
| Vote upsert semantics | Read-then-write "find or create" logic in a service | Native `INSERT ... ON CONFLICT DO UPDATE` (Pattern 4) | Race-safe at the DB level; a find-then-write approach has a TOCTOU race under concurrent votes from the same participant (e.g. double-tap), which is exactly the class of bug this phase exists to make structurally impossible. |
| Schema versioning | Hand-applied SQL scripts, or `ddl-auto=update`/`create-drop` | Flyway versioned migrations (`ddl-auto=validate` only) | `ddl-auto=update` silently drifts schema with no history — explicitly called out as forbidden in `.planning/research/STACK.md` "What NOT to Use". |

**Key insight:** Every "don't hand-roll" item above maps directly to a documented failure mode of the prior prototype (`.planning/research/ARCHITECTURE.md` Anti-Pattern 1) or a documented Spring Data JPA issue found during this research (UUID/native-query binding). None of these are speculative — all have specific corroborating sources.

## Runtime State Inventory

**Trigger check:** This phase is a framework upgrade (Boot 3.3.3→4.1.1, Kotlin 1.9.25→2.3.20) plus a full replacement of the in-memory prototype's persistence approach — the Runtime State Inventory protocol applies.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — the prior prototype (`LobbyService.kt`, `MovieVoteController.kt`) held all state in-memory (`mutableMapOf`/`HttpSession`); there is no existing database, file, or external datastore holding session/participant/vote data to migrate. Verified by reading `LobbyService.kt` and `MovieVoteController.kt` structure per CONTEXT.md's `<code_context>` section (no JDBC/JPA/file-persistence code present in either). | None — greenfield schema creation, not a data migration. |
| Live service config | None — no external services (n8n, Datadog, etc.) reference this project's session/vote data; this is a single-developer local prototype. | None. |
| OS-registered state | None found — no Task Scheduler/launchd/pm2/systemd registrations exist for this project; it runs via `./gradlew bootRun` or IDE, not as a registered background service. | None. |
| Secrets/env vars | `application.properties` currently has no secrets (`spring.application.name=MuviMatchr` only) `[VERIFIED: src/main/resources/application.properties:1]`. This phase adds `spring.datasource.*`/Flyway properties — for local dev these can be plaintext in `application.properties` or `docker-compose.yml` (no production secret yet exists to protect at this phase). | None now; flag for a future phase once real deployment credentials exist. |
| Build artifacts / installed packages | The Gradle wrapper itself (`gradle/wrapper/gradle-wrapper.properties`) is the only "installed artifact" carrying a stale version marker (8.10) that this phase's build change makes incompatible with the new Spring Boot Gradle plugin. | Update `distributionUrl` to the 8.14.3 distribution as part of this phase's first plan (see Standard Stack). |

**Nothing found in four of five categories — this is a greenfield persistence build, not a rename/data migration**, despite the framework-version bump. The only genuine "existing state" concern is the Gradle wrapper version, handled above.

## Common Pitfalls

### Pitfall 1: Copying Testcontainers-Postgres example code from pre-2.0 tutorials

**What goes wrong:** `import org.testcontainers.containers.PostgreSQLContainer` and `testImplementation("org.testcontainers:postgresql")` — both compile-looking, both wrong for this project's Boot-4.1.1-managed Testcontainers 2.0.5.
**Why it happens:** The overwhelming majority of existing Testcontainers+Spring Boot tutorials (including Spring's own older blog posts) predate the Testcontainers 2.0 rename (source note: even a Spring Boot GitHub issue tracking the 2.0 upgrade exists — `spring-projects/spring-boot#47639`).
**How to avoid:** Use `org.testcontainers.postgresql.PostgreSQLContainer` and `org.testcontainers:testcontainers-postgresql` exactly as specified in the Standard Stack table.
**Warning signs:** `ClassNotFoundException`/`NoClassDefFoundError` for `PostgreSQLContainer`, or a dependency resolution conflict citing two different Testcontainers versions on the classpath.

### Pitfall 2: Bare `flyway-core` silently not running migrations

**What goes wrong:** Adding `implementation("org.flywaydb:flyway-core")` alone (the Boot 3.x pattern) compiles fine and the app starts, but Flyway auto-configuration doesn't activate — `ddl-auto=validate` then fails at startup because no tables exist, OR (if `ddl-auto` is left at Hibernate's default) Hibernate may attempt DDL you don't want.
**Why it happens:** Boot 4.0 split Flyway/Liquibase auto-configuration into dedicated starters as part of the broader "auto-configuration jar split into focused per-technology modules" change.
**How to avoid:** Always pair `spring-boot-starter-flyway` with `flyway-database-postgresql`.
**Warning signs:** App starts but Flyway's `flyway_schema_history` table is never created; `ddl-auto=validate` throws `SchemaManagementException: Schema-validation: missing table`.

### Pitfall 3: JSpecify nullability mismatches surfacing at framework boundaries

**What goes wrong:** Kotlin 2.1+ (including 2.3.20) treats JSpecify nullness mismatches as **strict — a compile error, not a warning** `[CITED: spring.io/blog/2025/11/12/null-safe-applications-with-spring-boot-4]`. As Spring Data JPA/Spring Data Commons packages get incrementally annotated `@NullMarked`, Kotlin code calling into those APIs can hit new compile errors that didn't exist under Boot 3.3.3/Spring Framework 6.
**Why it happens:** Spring Framework 7 (which Boot 4.1 sits on) replaced Spring's own `@Nullable`/`@NonNull` with JSpecify annotations, and Kotlin 2 automatically translates JSpecify annotations into Kotlin's own null-safety enforcement — this is by design (moving NPE risk to compile time), but it's new behavior this codebase has never been exposed to.
**How to avoid:** Budget time during the D-01 upgrade step specifically for compile-error triage — this is expected friction, not a sign something is broken. The migration is opt-in per-package on Spring's side (`@NullMarked` is applied gradually across Spring Data's own packages), so the blast radius should be limited to whichever specific JPA repository/entity APIs this phase actually touches.
**Warning signs:** Kotlin compile errors referencing nullability on types from `org.springframework.data.*`/`jakarta.persistence.*` packages that had no such errors under the old Boot 3.3.3/Kotlin 1.9.25 baseline.

### Pitfall 4: `postgres:18` Docker image PGDATA path change (only relevant if `docker-compose.yml` mounts a named volume)

**What goes wrong:** PostgreSQL 18's official Docker image changed its internal data directory path to be version-specific (`/var/lib/postgresql/18/docker` under the hood; the image's documented mount point is now `/var/lib/postgresql`, not the old `/var/lib/postgresql/data`) `[CITED: multiple sources cross-checking Docker Hub's postgres image docs — see Sources]`. A `docker-compose.yml` volume mount written against the old path silently creates an empty/wrong-path volume.
**Why it happens:** Upstream Postgres Docker image change specific to major version 18, unrelated to this project.
**How to avoid:** If D-02's `docker-compose.yml` includes a named volume for data persistence across `docker compose down`/`up` cycles, mount it at `/var/lib/postgresql`, not `/var/lib/postgresql/data`, when using the `postgres:18` tag. (Not applicable if choosing `postgres:17` instead — STACK.md accepts either version.)
**Warning signs:** Local Postgres data doesn't survive `docker compose down && docker compose up` even though a volume is declared.

## Code Examples

See Architecture Patterns §1–4 above for the four load-bearing examples this phase needs (entity shape, Flyway migration shape, restart test, upsert query) — all four are the actual deliverables of this phase, not incidental snippets, so they're documented inline with their rationale rather than repeated here.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| `flyway-core` as a bare dependency, auto-configured by Boot | `spring-boot-starter-flyway` dedicated starter required | Spring Boot 4.0 (2025) | Every pre-4.0 Flyway+Spring-Boot tutorial's dependency block is now incomplete for this project. |
| `org.testcontainers:postgresql` + `org.testcontainers.containers.PostgreSQLContainer` | `org.testcontainers:testcontainers-postgresql` + `org.testcontainers.postgresql.PostgreSQLContainer` | Testcontainers 2.0 (2026) | Every pre-2.0 Testcontainers-Postgres tutorial's imports/dependency coordinates are now wrong for this project. |
| Manual `allOpen { annotation("jakarta.persistence.Entity") }` Gradle config for Kotlin JPA entities | Automatic — `kotlin("plugin.jpa")` alone now configures all-open with a built-in JPA preset | Kotlin 2.3.20 (2026) | Simplifies `build.gradle.kts` vs. older guides; adding the old manual block is harmless-but-redundant, not wrong. |
| Spring's own `@Nullable`/`@NonNull` (`org.springframework.lang`) | JSpecify (`org.jspecify.annotations`) | Spring Framework 7 / Boot 4.0 | Source of the Kotlin nullability-mismatch risk flagged in D-01 and Pitfall 3 above. |

**Deprecated/outdated:** `@MockBean`/`@SpyBean` (removed in Boot 4.0, replaced by `@MockitoBean`/`@MockitoSpyBean`) — not used by this phase's repository tests, but worth knowing before any later phase adds mocked-dependency tests on this Boot version.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|----------------|
| A1 | The exact `SpringApplicationBuilder`-based restart test pattern in Architecture Pattern 3 will compile and behave as described end-to-end (starting two genuinely independent contexts against one shared Testcontainers-managed Postgres, closing the first before starting the second) — synthesized from two independently-documented APIs (`SpringApplicationBuilder`, Testcontainers static-container-sharing) rather than copied from a single tutorial demonstrating this exact combination. | Architecture Patterns §Pattern 3 | If wrong, the D-03 restart test needs a different mechanism (e.g., explicit `@DynamicPropertySource` + a manually-constructed `AnnotationConfigApplicationContext`, or accepting `SpringApplication.run()` static overload instead of the builder). Low-to-medium risk: the two composed APIs are each independently well-documented and stable; first attempt at this test during plan execution should validate the mechanism quickly, and this is exactly the kind of thing a fast "run the one test" verification loop catches immediately. |
| A2 | Docker is genuinely absent from this development machine (no `docker`/`colima`/`podman`/`orbstack` binary, no app in `/Applications`) and this isn't a PATH issue. | Summary; Environment Availability | If actually present but misconfigured (e.g., installed but not on PATH, or a remote Docker context), the "blocking, no fallback" framing below is overstated and just needs a PATH fix rather than a fresh install. Medium risk if wrong — worth a quick manual double-check (`which docker`, Docker Desktop app check) before treating this as an install-from-scratch blocker. |

## Open Questions

1. **Which exact restart-test context-management mechanism will the plan actually implement?**
   - What we know: D-03's requirement (two genuinely separate contexts, same container) is unambiguous, and `SpringApplicationBuilder` + a `@Container static` Testcontainers field is a standard, well-documented way to achieve it (Pattern 3 above).
   - What's unclear: Whether `@DynamicPropertySource`-style dynamic property injection (typically used with `@SpringBootTest`) can/should be adapted to the builder's `.properties(...)` call, or whether hardcoding the container's `jdbcUrl`/`username`/`password` directly (as shown in Pattern 3) is preferred for clarity in a test that's deliberately not using `@SpringBootTest`.
   - Recommendation: Use the direct `.properties(postgres.jdbcUrl, ...)` approach shown in Pattern 3 — it's simpler to read as "these two contexts are definitely pointed at the same container" than threading a `@DynamicPropertySource` static method through, and this test doesn't need any of `@SpringBootTest`'s other conveniences (web environment, auto-configured MockMvc, etc. — none of which apply to a repository-only phase anyway).

2. **Postgres 17 vs 18 for `docker-compose.yml`/Testcontainers/eventual production?**
   - What we know: STACK.md accepts either; 18.4 is current stable `[CITED: web search, Docker Hub]`; 18 has the PGDATA path change noted in Pitfall 4.
   - What's unclear: Whether the project's eventual hosting target (Render/Railway, per STACK.md) offers managed Postgres 18 yet, or only up to 17 — this wasn't checked in this research pass since it's a Phase-6-adjacent deployment concern, not a Phase-1 blocker (Testcontainers pulls whatever tag is specified regardless of hosting-provider support).
   - Recommendation: Use `postgres:18` for `docker-compose.yml`/Testcontainers now (matches "current stable," and Flyway/Hibernate have no known 18-specific incompatibilities found during this research); if the eventual hosting provider caps at 17 when Phase 6 deployment is planned, downgrading the tag is a one-line change with no schema impact, since nothing in this phase uses any Postgres-18-specific SQL feature.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|-------------|-----------|---------|----------|
| Java (JDK) | Gradle toolchain, Boot 4.1 (needs 17+) | ✓ | OpenJDK/Corretto 25.0.4 `[VERIFIED: java -version output]` | — (toolchain already pins 21 in `build.gradle.kts:13`, satisfied by JDK 25) |
| Gradle wrapper | Build tool | ✓ (but stale) | 8.10 `[VERIFIED: gradle/wrapper/gradle-wrapper.properties:4]` — below the 8.14 floor Boot 4.1's Gradle plugin requires | Bump wrapper to 8.14.3 as part of this phase's first task (see Standard Stack) — not optional, this blocks the Boot 4.1 plugin from working at all. |
| Docker (or a Docker-API-compatible daemon: Colima, Podman, OrbStack) | Testcontainers (D-03 restart test), `docker-compose.yml` local Postgres (D-02) | ✗ | — `[VERIFIED: command -v docker/colima/podman/orbstack all empty; no Docker-named app in /Applications; no matching brew package]` | **None** — Testcontainers has no non-Docker execution mode. This must be installed on the development machine before this phase's core deliverable (the restart test) can be written and run. |
| PostgreSQL client (`psql`) or local Postgres server | Convenience only — not required by any locked decision | ✗ | — `[VERIFIED: command -v psql empty; pg_isready fails]` | Not needed — all Postgres access in this phase goes through Testcontainers (tests) or the Docker Compose service (manual local exploration), neither of which requires a host-installed `psql`. |

**Missing dependencies with no fallback:**
- **Docker (or equivalent daemon).** This blocks D-02 and D-03 entirely — the plan must include an explicit early setup step (install Docker Desktop, OrbStack, or Colima; verify with `docker info` succeeding) before any Testcontainers-based test can be attempted. Recommend a `checkpoint:human-verify`-style task at the very start of this phase's plan, since installing a Docker daemon is an out-of-repo, human-driven action no amount of code can substitute for.

**Missing dependencies with fallback:**
- `psql`/local Postgres server binary — not needed; Testcontainers and `docker-compose.yml` cover this phase's needs without a host-installed Postgres client.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter) — already present via `kotlin-test-junit5` and `junit-platform-launcher` `[VERIFIED: build.gradle.kts:29-31]`; this phase adds Spring Boot's test starter (`spring-boot-starter-test`, already present) plus Testcontainers' JUnit 5 integration. |
| Config file | none dedicated yet — `tasks.withType<Test> { useJUnitPlatform() }` already configured `[VERIFIED: build.gradle.kts:43-45]` |
| Quick run command | `./gradlew test --tests "org.example.muvimatchr.voting.VoteRepositoryTest"` (single repository test, fast) |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|---------------------|--------------|
| RELI-01 (restart survival) | Session/Participant/Vote rows written in one Spring context are readable from a fresh, independently-started context against the same Testcontainers container | integration | `./gradlew test --tests "*.RestartSurvivalTest"` | ❌ Wave 0 |
| RELI-01 (vote unique constraint + upsert) | Writing a duplicate `(session_id, participant_id, movie_id)` via the native upsert query updates in place rather than erroring/duplicating | integration | `./gradlew test --tests "*.VoteRepositoryTest"` | ❌ Wave 0 |
| RELI-01 (join code unique constraint) | Inserting two sessions with the same `join_code` violates the DB unique constraint | integration | `./gradlew test --tests "*.SessionRepositoryTest"` | ❌ Wave 0 |
| RELI-01 (Flyway migrations apply cleanly + reapply-safe) | A fresh Testcontainers Postgres instance accepts all migrations with no errors; running the app a second time against an already-migrated schema doesn't fail | integration (implicitly exercised by every other integration test's context startup — Flyway runs on every context start in the test suite) | `./gradlew test` (full suite; any context-startup failure surfaces this) | — (covered by other tests' setup, no dedicated test needed) |

### Sampling Rate

- **Per task commit:** the single relevant repository test (e.g., `./gradlew test --tests "*.VoteRepositoryTest"` after writing `Vote`/`VoteRepository`)
- **Per wave merge:** `./gradlew test` (full suite)
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps

- [ ] `src/test/kotlin/org/example/muvimatchr/support/PostgresTestSupport.kt` — shared static `@Container` Postgres instance/helpers, reused by the repository tests (not by `RestartSurvivalTest`, which deliberately manages its own context lifecycle — see Pattern 3).
- [ ] `src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt` — the D-03 proof itself.
- [ ] `src/test/kotlin/org/example/muvimatchr/session/SessionRepositoryTest.kt`
- [ ] `src/test/kotlin/org/example/muvimatchr/voting/VoteRepositoryTest.kt`
- [ ] Testcontainers dependencies (`spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`) — none present yet; part of this phase's dependency-addition task.
- [ ] Docker daemon availability on the dev machine — see Environment Availability; this is a precondition for Wave 0 test execution, not a file gap, but blocks all of the above from running.

## Security Domain

> `security_enforcement: true`, `security_asvs_level: 1` per `.planning/config.json`.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|-----------------|---------|--------------------|
| V1 Architecture | Yes (indirectly) | Package-by-feature separation (`session/`, `voting/`) with Flyway-owned schema and no `ddl-auto=update` — already the locked architecture; nothing additional needed this phase. |
| V2 Authentication | No | This phase has no endpoints, no request path, no auth concept — deferred to Phase 2 (SESH-03, unguessable participant tokens). |
| V3 Session Management | No | Same as above — no HTTP session/cookie surface exists in this phase (and per architecture research, never will — participant identity is a DB-issued token consumed client-side, not `HttpSession`). |
| V4 Access Control | No | No endpoints in this phase to authorize access to. |
| V5 Input Validation | Partial | The only "input" in this phase is data written by test code directly to repositories, not untrusted external input — standard JPA/Bean Validation constraints (`nullable = false`, column lengths) are sufficient; no user-facing validation surface exists yet. The native upsert query (Pattern 4) uses parameterized `@Param` binding exclusively — **never string-concatenate values into the native SQL**, which would reintroduce classic SQL injection risk even though there's no HTTP layer yet exercising it (the query shape itself should be injection-safe by construction, since Phase 4 will call it with real user-influenced `movieId`/`choice` values). |
| V6 Cryptography | No | No secrets/credentials generated or stored by this phase's schema (join codes are non-secret identifiers per the requirements — SESH-03's unguessable *token* is a separate, Phase-2-owned concept, not the join code this phase indexes). |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|-----------------------|
| SQL injection via native `@Query` string concatenation | Tampering | Always use `@Param`-bound named parameters (as in Pattern 4); never build native query strings via Kotlin string templates with user-influenced values. |
| Schema drift via `ddl-auto=update`/`create-drop` masking a bad migration | Tampering / Repudiation (no audit trail of schema changes) | `ddl-auto=validate` only, Flyway owns all schema writes — already locked by STACK.md and CONTEXT.md; this phase's migrations are the mechanism that satisfies this. |

## Sources

### Primary (HIGH confidence — directly verified via tool against an authoritative registry/source in this session)

- `repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom` — downloaded and read directly; source of the `flyway.version=12.4.0`, `hibernate.version=7.4.5.Final`, `postgresql.version=42.7.13`, `testcontainers.version=2.0.5`, `jackson-bom.version=3.1.5`, and the `spring-boot-starter-flyway`/`spring-boot-starter-data-jpa`/`spring-boot-testcontainers` managed-version entries (all `4.1.1`) cited throughout.
- `repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-flyway/maven-metadata.xml` — confirmed 4.1.1 published 2026-08-20; 4.2.0-M1 (milestone) also exists.
- `repo1.maven.org/maven2/org/testcontainers/testcontainers-postgresql/maven-metadata.xml` and `.../testcontainers-junit-jupiter/maven-metadata.xml` — confirmed both exist at 2.0.5.
- `raw.githubusercontent.com/testcontainers/testcontainers-java/main/modules/postgresql/src/main/java/org/testcontainers/postgresql/PostgreSQLContainer.java` — package declaration read directly, confirms the 2.x package relocation.
- `raw.githubusercontent.com/testcontainers/testcontainers-java/main/modules/junit-jupiter/src/main/java/org/testcontainers/junit/jupiter/Testcontainers.java` — package declaration read directly, confirms this package did NOT move.
- `services.gradle.org/versions/all` — confirmed Gradle 8.14.3 and 9.3.0 both exist and are not marked broken.
- Local machine inspection: `java -version`, `command -v docker/colima/podman/orbstack`, `ls /Applications`, `brew list`, `command -v psql`, `pg_isready`, `cat gradle/wrapper/gradle-wrapper.properties`, `cat build.gradle.kts`, `cat src/main/resources/application.properties` — all read directly this session.

### Secondary (MEDIUM confidence — official documentation, cited but not independently registry-verified)

- `docs.spring.io/spring-boot/gradle-plugin/index.html` — exact quote: "Spring Boot's Gradle plugin requires Gradle 8.x (8.14 or later) or 9.x."
- `github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide` — exact quote on the Flyway/Liquibase starter requirement.
- `kotlinlang.org/docs/whatsnew2320.html` — `kotlin.plugin.jpa` auto-allOpen behavior, Gradle 7.6.3–9.3.0 compatibility range.
- `docs.spring.io/spring-boot/api/java/org/springframework/boot/testcontainers/service/connection/ServiceConnection.html` — confirms `@ServiceConnection` present in Boot 4.1.0 API.
- `docs.spring.io/spring-boot/api/java/org/springframework/boot/builder/SpringApplicationBuilder.html` — `.properties(String...)`/`.run(String...)` method signatures (note: this fetch's summary included a confused/incorrect closing disclaimer claiming "Spring Boot 4.1.1 does not exist" — disregard that line; it contradicts this session's own direct Maven Central verification of 4.1.1's existence and publish date).
- `spring.io/blog/2025/11/12/null-safe-applications-with-spring-boot-4` — JSpecify strict-by-default nullness checking in Kotlin 2.1+.
- `jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/generationtype` — `GenerationType.UUID` portability.

### Tertiary (LOW confidence — web search only, flagged for validation during planning/execution if load-bearing)

- Postgres 18's `postgres:18` Docker image PGDATA path change (Pitfall 4) — web search only, not independently verified against the actual Docker Hub image manifest in this session; low risk since it only matters if `docker-compose.yml` uses a named volume, and is trivially checkable at implementation time.
- Spring Data JPA UUID-in-native-query binding issue (Pattern 4 pitfall) — sourced from GitHub issue titles/summaries in search results, not read in full; the mitigation recommended (`CAST(:param AS uuid)`) is a standard, low-risk defensive pattern regardless of whether the specific issue is still open in current Spring Data JPA versions.

## Metadata

**Confidence breakdown:**
- Standard stack (dependency coordinates/versions): HIGH — every artifact directly verified against Maven Central and/or the actual Spring Boot 4.1.1 BOM POM content, not training-data recall.
- Architecture (entity shape, migration shape, upsert pattern): HIGH — directly derived from `.planning/research/ARCHITECTURE.md`'s already-researched, already-locked patterns; this research only adapted them to Boot 4.1/Hibernate 7.4-specific syntax.
- Restart-test mechanics (Pattern 3): MEDIUM — synthesized from two independently well-documented, stable Spring Boot APIs rather than a single tutorial covering this exact scenario (flagged as Assumption A1); the underlying APIs themselves are HIGH confidence, the composition is unverified end-to-end.
- Pitfalls: HIGH for Pitfalls 1–3 (each backed by an official migration guide or blog quote), LOW-MEDIUM for Pitfall 4 (web-search-only, low-stakes).
- Environment availability: HIGH — Docker absence and Gradle wrapper staleness both directly confirmed by running commands against this machine this session.

**Research date:** 2026-09-01
**Valid until:** ~2026-10-01 (30 days) for the architecture/pattern guidance; the specific version pins (Boot 4.1.1, Kotlin 2.3.20, Testcontainers 2.0.5, Flyway 12.4.0) should be re-verified against `repo1.maven.org` if this phase's plan execution is delayed more than a few weeks past this research date, since Spring Boot ships patch releases roughly monthly (4.1.1 → 4.1.2 etc. following the same cadence visible in the 4.0.x history captured in this session's `maven-metadata.xml` fetch).

---
*Phase: 1-Persistence Foundation*
*Research completed: 2026-09-01*
