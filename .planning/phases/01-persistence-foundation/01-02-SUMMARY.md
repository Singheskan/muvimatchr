---
phase: 01-persistence-foundation
plan: 02
subsystem: persistence
tags: [jpa, flyway, postgresql, testcontainers, spring-boot, restart-survival]

requires:
  - Boot 4.1.1 / Kotlin 2.3.20 / Gradle 8.14.3 toolchain (Plan 01-01)
  - JPA + Flyway + PostgreSQL + Testcontainers dependency set on the classpath (Plan 01-01)
  - Local PostgreSQL 18 dev database via docker-compose (Plan 01-01)
provides:
  - "session table (Flyway V1) with UUID PK and unique join_code index"
  - "Session JPA entity, validated (never mutated) against the Flyway schema"
  - "SessionRepository (JpaRepository<Session, UUID>)"
  - "The D-03 full-context restart proof (RestartSurvivalTest), primary SpringApplicationBuilder mechanism confirmed working end-to-end"
  - "PostgresTestSupport: shared static postgres:18 Testcontainers fixture via @ServiceConnection, reusable by every later repository test in this phase"
affects: [01-03-participant-vote-tables]

actuals:
  tokens: 1500
  tasks: 2
  commits: 3

tech-stack:
  added: []
  patterns:
    - "SpringApplicationBuilder + static @Container Testcontainers field for a genuine two-context restart proof (RESEARCH.md Pattern 3, confirmed working)"
    - "@ServiceConnection on a static Testcontainers field for ordinary single-context repository tests (RESEARCH.md Standard Stack)"

key-files:
  created:
    - src/main/resources/db/migration/V1__create_session.sql
    - src/main/kotlin/org/example/muvimatchr/session/Session.kt
    - src/main/kotlin/org/example/muvimatchr/session/SessionRepository.kt
    - src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt
    - src/test/kotlin/org/example/muvimatchr/support/PostgresTestSupport.kt
    - src/test/kotlin/org/example/muvimatchr/session/SessionRepositoryTest.kt
  modified: []

key-decisions:
  - "Worktree base mismatch at spawn time (branched before Plan 01-01's implementation commits landed on main) was resolved with a pure fast-forward merge — the worktree branch had zero unique commits, so `git merge --ff-only main` was risk-free and brought in the toolchain/Postgres/compose work this plan depends on."
  - "Colima + Testcontainers requires TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock (in addition to DOCKER_HOST pointing at Colima's actual socket path) — without it, the Ryuk resource-reaper container fails to start because it tries to bind-mount the host socket path literally, which Colima's VM does not expose at that path. This is required for every ./gradlew test invocation touching Testcontainers in this environment, alongside the already-known JAVA_HOME=temurin-21 requirement from Plan 01-01."
  - "RESEARCH.md Assumption A1's primary mechanism (SpringApplicationBuilder + static container) works end-to-end — no fallback to @DynamicPropertySource or the static SpringApplication.run overload was needed — but only after fixing how properties are passed: SpringApplicationBuilder.properties(vararg) sets *default* (lowest-precedence) properties, not overrides, so it was silently beaten by application.properties' classpath datasource config. Fixed by passing the same values as command-line-style --key=value args to .run(...) instead, which have Spring Boot's highest property precedence."

patterns-established:
  - "Testcontainers env prerequisites for this Colima-based dev machine: JAVA_HOME=temurin-21 (Plan 01-01) + DOCKER_HOST=unix:///Users/psrg/.colima/default/docker.sock + TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock, all exported before any ./gradlew test invocation."

requirements-completed: [RELI-01]

coverage:
  - id: D-03
    description: "A Session row written through one running application context is still present and correctly readable after that context is closed and a second, independently-constructed context starts against the same PostgreSQL database"
    verification:
      - kind: other
        ref: "./gradlew test --tests '*.RestartSurvivalTest'"
        status: pass
    human_judgment: false
  - id: RELI-01-migration
    description: "Flyway applies V1 cleanly to a fresh database, and applies nothing new when a second application startup meets an already-migrated schema"
    verification:
      - kind: other
        ref: "RestartSurvivalTest asserts flyway_schema_history row count is identical before and after the restart"
        status: pass
    human_judgment: false
  - id: RELI-01-unique-constraint
    description: "The session table enforces a unique constraint on join codes at the database level, not application code"
    verification:
      - kind: other
        ref: "./gradlew test --tests '*.SessionRepositoryTest'"
        status: pass
    human_judgment: false
  - id: RELI-01-ddl-auto-validate
    description: "Hibernate writes no DDL; the entity mapping is validated against the Flyway-created schema on every context start"
    verification:
      - kind: other
        ref: "spring.jpa.hibernate.ddl-auto=validate used in both application.properties and every context started by RestartSurvivalTest; ./gradlew test is green with no schema-validation errors"
        status: pass
    human_judgment: false

duration: ~1.5 hours (including worktree recovery and a mid-plan test-infrastructure bug fix)
completed: 2026-09-02
status: complete
---

# Phase 01, Plan 02: Tracer Slice — Session Entity & Restart-Survival Proof Summary

**Session table, entity, and repository built end-to-end and proven durable across a literal application restart against a real PostgreSQL 18 Testcontainers instance, with the join-code unique constraint enforced at the database level — the architecture proof every later phase in this project inherits.**

## Accomplishments
- Created `V1__create_session.sql` (Flyway migration): `session` table with UUID primary key, `join_code VARCHAR(16) NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, and `uq_session_join_code` unique index.
- Created `Session.kt`: plain Kotlin class (not `data class`) JPA entity, `@GeneratedValue(strategy = GenerationType.UUID)`, relying on the Kotlin 2.3.20 `plugin.jpa` auto-open/no-arg behavior from Plan 01-01 — no manual `allOpen` config needed.
- Created `SessionRepository.kt`: `interface SessionRepository : JpaRepository<Session, UUID>`, no extra methods.
- Created `RestartSurvivalTest.kt` — the D-03 proof: two independently-constructed `ConfigurableApplicationContext`s (via `SpringApplicationBuilder`, never `@SpringBootTest`/`@DirtiesContext`/`EntityManager.clear()`) against one shared static Testcontainers `postgres:18` instance. Context one writes a `Session`, closes; context two starts fresh and reads it back, also asserting the `flyway_schema_history` row count is unchanged (proving Flyway's reapply-safety across the restart).
- Created `PostgresTestSupport.kt`: shared abstract `@SpringBootTest`/`@Testcontainers` base class with a static `@ServiceConnection`-annotated `postgres:18` container, reused by ordinary single-context repository tests (deliberately *not* by `RestartSurvivalTest`, which manages its own two-context lifecycle per RESEARCH.md Pattern 3's "when to use" guidance).
- Created `SessionRepositoryTest.kt`: proves the UUID round trip (`save` → `findById`) and that a duplicate `join_code` is rejected by the database on `saveAndFlush` (`DataIntegrityViolationException`), not by an application-level pre-check.
- `./gradlew test` is green: 3 tests total (1 `RestartSurvivalTest` + 2 `SessionRepositoryTest`), 0 failures — the wave gate for Plan 01-03.

## Task Commits

1. **Task 1: End-to-end "a session survives a restart"** — `83082f6` (feat) — `V1__create_session.sql`, `Session.kt`, `SessionRepository.kt`, `RestartSurvivalTest.kt`. Verified with `./gradlew test --tests "*.RestartSurvivalTest"` (1 test, passed) before the tracer feedback gate allowed expansion.
2. **[Rule 1 bug fix, discovered during Task 2's full-suite verification]** — `e9b50de` (fix) — `RestartSurvivalTest.kt` corrected to use `.run(--key=value...)` instead of `.properties(...)`. See Deviations below.
3. **Task 2: Shared PostgreSQL test fixture and join-code unique-constraint proof** — `4ff8b38` (feat) — `PostgresTestSupport.kt`, `SessionRepositoryTest.kt`. Verified with `./gradlew test --tests "*.SessionRepositoryTest"` (2 tests, passed) and the full `./gradlew test` suite (3 tests, passed).

## Files Created/Modified
- `src/main/resources/db/migration/V1__create_session.sql` - new Flyway migration.
- `src/main/kotlin/org/example/muvimatchr/session/Session.kt` - new JPA entity.
- `src/main/kotlin/org/example/muvimatchr/session/SessionRepository.kt` - new Spring Data repository.
- `src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt` - new integration test (the D-03 proof), later corrected — see Deviations.
- `src/test/kotlin/org/example/muvimatchr/support/PostgresTestSupport.kt` - new shared Testcontainers test fixture.
- `src/test/kotlin/org/example/muvimatchr/session/SessionRepositoryTest.kt` - new integration test (UUID round trip + duplicate join-code rejection).

## Decisions Made
- **Assumption A1 resolved: primary mechanism works, with a precedence fix.** RESEARCH.md flagged the composed `SpringApplicationBuilder` + static-container mechanism as MEDIUM confidence (Assumption A1) and named two fallbacks (`@DynamicPropertySource`, static `SpringApplication.run`). Neither fallback was needed — the mechanism itself is sound — but the exact API call it specified (`.properties(vararg).run()`) does not override `application.properties` as intended, because `SpringApplicationBuilder.properties(...)` sets Spring Boot's lowest-precedence "default properties" source. Switched to passing the same key/value pairs as `--key=value` command-line-style arguments to `.run(...)`, which have the framework's highest property precedence. This stays within the plan's intended mechanism (no architectural change, no fallback to a different API family) and is recorded here per the plan's `<output>` instruction to resolve this open question for later phases wanting a restart test.
- **Colima + Testcontainers requires an explicit socket override.** Beyond the `JAVA_HOME=temurin-21` export already required by Plan 01-01, every `./gradlew test` invocation touching Testcontainers in this environment also needs `DOCKER_HOST=unix:///Users/psrg/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` exported first. Without the override, Testcontainers' Ryuk resource-reaper container fails to start (`InternalServerErrorException: error while creating mount source path '.../docker.sock': operation not supported`) because it tries to bind-mount the host socket at its literal Colima path, which Colima's VM does not expose there. Not committed to a repo file (machine-specific path, same reasoning as the Plan 01-01 `JAVA_HOME` decision) — flagged here for future sessions in this phase.
- **Worktree base mismatch resolved via fast-forward, not a workaround.** This plan's executor worktree was branched before Plan 01-01's implementation commits (`a3845f2`, `de4a3a2`, `1c77b80`) landed on `main`, so required files (`01-01-SUMMARY.md`, `docker-compose.yml`, the upgraded toolchain) were initially absent from the worktree. Confirmed the worktree branch had zero unique commits of its own (`git log main..HEAD` was empty), so `git merge --ff-only main` was a risk-free way to bring the worktree fully current before starting Task 1 — not a merge, not a rebase, no conflict resolution involved.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] RestartSurvivalTest was silently connecting to the local dev database instead of the Testcontainers instance**
- **Found during:** Task 2's full-suite verification (`./gradlew test`, run after Task 1's own tests had already passed in isolation).
- **Issue:** `SpringApplicationBuilder.properties("spring.datasource.url=${postgres.jdbcUrl}", ...)` — exactly as RESEARCH.md Pattern 3 specifies — sets Spring Boot's *default* properties, which are the **lowest**-precedence property source. `application.properties` (added to the classpath in Plan 01-01, pointing at `jdbc:postgresql://localhost:5432/muvimatchr`, the persistent local dev Postgres) has normal, higher precedence and silently won. `RestartSurvivalTest` run in isolation appeared to pass because the persistent dev database trivially "survives" a context restart (it's a real, always-on service) — a false positive that looked identical to a correct pass. The bug surfaced only when running the full suite a second time: the dev database already had a row with `join_code = 'ABC123'` (inserted by the previous isolated run of `RestartSurvivalTest`, which does not get cleaned up like an ephemeral Testcontainers instance does), so the second run's insert hit `uq_session_join_code` and failed with `DataIntegrityViolationException`. Confirmed directly by querying `muvimatchr-db-1` (`docker exec ... psql -c "SELECT join_code FROM session;"`), which returned `ABC123`.
- **Fix:** Changed `startContext()` to pass the same datasource values as `--key=value` command-line-style arguments to `.run(...)` instead of via `.properties(...)`. Command-line arguments are Spring Boot's highest-precedence property source and correctly override `application.properties`. Re-ran `./gradlew test --tests "*.RestartSurvivalTest"` in isolation (passed) and `./gradlew test` (full suite, passed, 3/3), then confirmed via direct `psql` query that the dev database's `session` table row count was unchanged across repeated test runs after the fix — proof the test now genuinely targets the ephemeral container, not the dev database.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt`.
- **Verification:** `./gradlew test` green (3 tests, 0 failures); `psql` query against `muvimatchr-db-1` stable at 1 row across multiple post-fix test runs.
- **Committed in:** `e9b50de`

---

**Total deviations:** 1 auto-fixed (Rule 1 — bug in the researched test mechanism, corrected within the same mechanism family, no architectural change).
**Impact on plan:** No scope creep. This is exactly the kind of composed-API risk RESEARCH.md's Assumption A1 anticipated; the fix keeps the plan's intended mechanism (`SpringApplicationBuilder` + static container) intact and working, and is the answer the plan's `<output>` instruction asked for regarding which mechanism resolved cleanly.

## Known Stubs
None. All artifacts (`Session`, `SessionRepository`, `V1__create_session.sql`) are the real, final shapes this phase's later plans and every subsequent phase build on — not prototypes.

## Known Issues / Cleanup Items
- **Leftover row in local dev database.** Before the Rule-1 fix above, `RestartSurvivalTest` accidentally wrote one row (`join_code = 'ABC123'`) into the persistent local dev Postgres (`muvimatchr-db-1`) instead of an ephemeral Testcontainers instance. It remains there — harmless (no schema impact, not referenced by any other data, this is a purely local dev-only database with no production counterpart yet), but worth a manual `DELETE FROM session WHERE join_code = 'ABC123';` next time a session is interactively working against that database, since an executor-level DB write was blocked by this session's permission classifier.

## Issues Encountered
- **Worktree base mismatch at spawn time.** This plan's executor worktree branched from a commit predating Plan 01-01's actual implementation commits (only the plan docs were present; `docker-compose.yml`, the upgraded `build.gradle.kts`, and `01-01-SUMMARY.md` were all missing). Resolved via `git merge --ff-only main` before starting any task — see Decisions Made above. Flagging for the orchestrator: worktrees for dependent plans (`depends_on: ["01-01"]`) should be cut from `main` only after the dependency's final commit lands, not from an earlier snapshot.
- **Colima Testcontainers socket-mount issue.** See Decisions Made above — required `DOCKER_HOST` + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` exports, not documented anywhere before this plan.
- **RestartSurvivalTest false-positive-then-failure.** See Deviations above — the bug did not manifest on the first isolated test run (looked like a clean pass), only surfaced when the full suite ran a second time against the polluted dev database. Worth noting for future phases: an isolated single-test pass is not sufficient confidence that a Testcontainers-based test is actually targeting the ephemeral container — a full-suite or repeated-run check (or a direct query against the "real" datasource this app would otherwise use) is a good sanity check when standing up a new restart-style test.

## User Setup Required
None. All fixes and environment discoveries in this plan were resolved by the executor; the `DOCKER_HOST`/`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`/`JAVA_HOME` exports are session-only shell state, not a repo or user action.

## Next Steps
Plan 01-03 (Participant and Vote tables) builds directly on this plan's `Session` entity and the now-proven restart/Flyway/Testcontainers infrastructure. Before starting, export `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`, `DOCKER_HOST=unix:///Users/psrg/.colima/default/docker.sock`, and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` before any `./gradlew` invocation.

## Self-Check: PASSED
All six created files (`V1__create_session.sql`, `Session.kt`, `SessionRepository.kt`, `RestartSurvivalTest.kt`, `PostgresTestSupport.kt`, `SessionRepositoryTest.kt`) confirmed present on disk. All three commit hashes (`83082f6`, `e9b50de`, `4ff8b38`) confirmed present in `git log`.
