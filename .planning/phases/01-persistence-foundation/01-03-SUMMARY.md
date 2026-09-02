---
phase: 01-persistence-foundation
plan: 03
subsystem: database
tags: [jpa, flyway, postgresql, testcontainers, spring-boot, upsert, unique-constraint, restart-survival]

requires:
  - phase: 01-persistence-foundation (Plan 01-02)
    provides: Session table/entity/repository, PostgresTestSupport shared Testcontainers fixture, proven SpringApplicationBuilder two-context restart mechanism
provides:
  - "participant table (Flyway V2) with FK to session and idx_participant_session_id"
  - "Participant JPA entity + repository, lazy ManyToOne to Session"
  - "vote table (Flyway V3) with uq_vote_session_participant_movie unique constraint and idx_vote_session_id"
  - "Vote JPA entity + VoteChoice enum (EnumType.STRING) + VoteRepository including the race-safe native upsert"
  - "VoteRepository.upsertVote: INSERT ... ON CONFLICT DO UPDATE, five named @Param bindings, no string interpolation, explicit CAST(:param AS uuid)"
  - "RestartSurvivalTest extended to cover Session, Participant AND Vote in one restart proof, including lazy association navigation"
  - "Full schema (V1-V3), entities, repositories complete for Phase 2/3/4 to build on"
affects: [02-session-lobby, 04-vote-match]

actuals:
  tokens: 3100
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Testcontainers singleton-container pattern for a shared fixture consumed by multiple test classes: manual .start() in the companion object, no @Testcontainers/@Container per-class lifecycle management, to avoid stale-DataSource-vs-restarted-container port mismatches"
    - "Native @Modifying @Query upsert with @Param-bound named parameters and explicit CAST(:param AS uuid) for the Spring Data JPA UUID-native-query binding pitfall"

key-files:
  created:
    - src/main/resources/db/migration/V2__create_participant.sql
    - src/main/kotlin/org/example/muvimatchr/session/Participant.kt
    - src/main/kotlin/org/example/muvimatchr/session/ParticipantRepository.kt
    - src/main/resources/db/migration/V3__create_vote.sql
    - src/main/kotlin/org/example/muvimatchr/voting/Vote.kt
    - src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt
    - src/test/kotlin/org/example/muvimatchr/voting/VoteRepositoryTest.kt
  modified:
    - src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt
    - src/test/kotlin/org/example/muvimatchr/support/PostgresTestSupport.kt

key-decisions:
  - "Worktree base mismatch at spawn time (branched before the prior session's chore/merge commits landed on main) resolved with a pure fast-forward merge — the worktree branch had zero unique commits, so `git merge --ff-only main` was risk-free."
  - "PostgresTestSupport switched from @Testcontainers/@Container-managed lifecycle to Testcontainers' documented singleton-container pattern (manual .start(), no per-class stop) — see Deviations. This is now the load-bearing pattern for any future test class sharing this fixture."
  - "VoteRepositoryTest builds its own fresh Session+Participant per test method (random-UUID-derived join code truncated to the 16-char column width) rather than sharing fixtures across tests, matching PostgresTestSupport's no-rollback @SpringBootTest semantics documented in Plan 01-02."

patterns-established:
  - "Singleton Testcontainers fixture for multi-class sharing: start once manually in a companion object, let the Ryuk reaper clean up at JVM exit, never rely on @Testcontainers per-class start/stop for a base class with more than one subclass."

requirements-completed: [RELI-01]

coverage:
  - id: D1
    description: "Participant table, entity and repository exist; Hibernate validates the mapping against the Flyway V2 schema on every context start"
    verification:
      - kind: integration
        ref: "./gradlew test (full suite, Hibernate schema validation runs at every context start)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Vote table with uq_vote_session_participant_movie unique constraint; native upsert inserts once then updates in place for a repeated tuple; a direct duplicate insert is rejected by the database independent of the upsert path; every bound value goes through a named @Param (five total), no string interpolation"
    verification:
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/voting/VoteRepositoryTest.kt#upsertVote for a new tuple inserts exactly one row"
        status: pass
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/voting/VoteRepositoryTest.kt#upsertVote for an existing tuple updates the choice in place instead of duplicating"
        status: pass
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/voting/VoteRepositoryTest.kt#duplicate vote tuple via plain save is rejected by the database"
        status: pass
    human_judgment: false
  - id: D3
    description: "Restart proof extended to Session, Participant and Vote together in one context, including lazy participant-to-session association navigation in the second context, with Flyway history row count unchanged across the restart"
    verification:
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt#session, participant and vote written before restart are readable after a fresh context starts"
        status: pass
    human_judgment: false
  - id: D4
    description: "Full test suite green with zero skipped classes: RestartSurvivalTest, SessionRepositoryTest, VoteRepositoryTest all produce JUnit result files with skipped=0"
    verification:
      - kind: other
        ref: "./gradlew test && grep -L 'skipped=\"[1-9]' build/test-results/test/TEST-*.xml"
        status: pass
    human_judgment: false

duration: ~40 min
completed: 2026-09-02
status: complete
---

# Phase 01, Plan 03: Participant & Vote Tables — Full Schema, Upsert & Multi-Entity Restart Proof Summary

**Participant and Vote tables, entities, and repositories complete the Phase 1 schema; the native `INSERT ... ON CONFLICT DO UPDATE` vote upsert is proven race-safe and injection-safe (five named `@Param` bindings, zero string interpolation); and the restart-survival proof now covers Session, Participant and Vote together — including lazy association navigation across a genuine process restart — closing all four ROADMAP Phase 1 success criteria.**

## Performance

- **Duration:** ~40 min
- **Tasks:** 3 completed
- **Files modified:** 9 (7 created, 2 modified)

## Accomplishments
- Created `V2__create_participant.sql`: `participant` table with `session_id` FK to `session` and `idx_participant_session_id` index.
- Created `Participant.kt`: plain-class JPA entity (not `data class`), `@ManyToOne(fetch = FetchType.LAZY)` to `Session`, `GenerationType.UUID` id — mirrors `Session.kt`'s proven shape exactly.
- Created `ParticipantRepository.kt`: `JpaRepository<Participant, UUID>`, no extra methods (roster queries are Phase 2's scope).
- Created `V3__create_vote.sql`: `vote` table with the three-column `uq_vote_session_participant_movie` unique constraint and `idx_vote_session_id` index.
- Created `Vote.kt`: plain-class JPA entity with lazy `ManyToOne` to both `Session` and `Participant`, `movieId: Long` (raw TMDB id, no local FK), `VoteChoice` enum (`LIKE`/`PASS`) stored via `EnumType.STRING`.
- Created `VoteRepository.kt`: native `@Modifying @Query` upsert (`INSERT ... ON CONFLICT (session_id, participant_id, movie_id) DO UPDATE SET choice = EXCLUDED.choice, voted_at = now()`) — all five values (`id`, `sessionId`, `participantId`, `movieId`, `choice`) bound through `@Param`-annotated named parameters, UUID params explicitly `CAST(... AS uuid)`, zero Kotlin string-template interpolation anywhere in the file.
- Created `VoteRepositoryTest.kt`: three tests proving insert-once, update-in-place on a repeated upsert (ROADMAP success criterion 2), and database-level rejection of a duplicate tuple via plain `saveAndFlush` independent of the upsert path.
- Extended `RestartSurvivalTest.kt` (Plan 01-02's Session-only proof) to write a `Session`, `Participant`, and `Vote` in one context and read all three back — including navigating the lazy `Participant.session` association — from a second, independently-constructed context against the same Testcontainers Postgres instance, with the Flyway history row count assertion retained.
- **[Deviation, Rule 1]** Fixed a latent bug in `PostgresTestSupport.kt` (Plan 01-02's shared fixture) surfaced by adding this plan's second consumer of it (`VoteRepositoryTest`, alongside `SessionRepositoryTest`): switched from `@Testcontainers`/`@Container`-managed (per-test-class) lifecycle to Testcontainers' documented singleton-container pattern. See Deviations below.
- `./gradlew test` is green: 6 tests total across `RestartSurvivalTest` (1), `SessionRepositoryTest` (2), `VoteRepositoryTest` (3) — 0 failures, 0 skipped. This is the Phase 1 gate.

## Task Commits

1. **Task 1: Participant table, entity and repository** - `d35352d` (feat)
2. **Task 2: Vote table with the unique constraint, entity, repository, and the race-safe upsert write path** - `099ad01` (feat) — includes the Rule 1 `PostgresTestSupport.kt` fix (see Deviations), folded into this commit because the bug was discovered and had to be fixed before this task's own acceptance criteria (`./gradlew test` green) could be verified.
3. **Task 3: Extend the restart proof to Session, Participant and Vote together** - `154ac36` (test)

_Note: this SUMMARY.md is the plan-closing artifact; no separate plan-metadata commit per this worktree's instructions (STATE.md/ROADMAP.md/KANBAN.md are owned centrally by the orchestrator, not this executor)._

## Files Created/Modified
- `src/main/resources/db/migration/V2__create_participant.sql` - new Flyway migration; `participant` table, FK to `session`, `idx_participant_session_id`.
- `src/main/kotlin/org/example/muvimatchr/session/Participant.kt` - new JPA entity.
- `src/main/kotlin/org/example/muvimatchr/session/ParticipantRepository.kt` - new Spring Data repository.
- `src/main/resources/db/migration/V3__create_vote.sql` - new Flyway migration; `vote` table, `uq_vote_session_participant_movie`, `idx_vote_session_id`.
- `src/main/kotlin/org/example/muvimatchr/voting/Vote.kt` - new JPA entity + `VoteChoice` enum.
- `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt` - new Spring Data repository with the native upsert query.
- `src/test/kotlin/org/example/muvimatchr/voting/VoteRepositoryTest.kt` - new integration test (insert, update-in-place, DB-level duplicate rejection).
- `src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt` - extended: now writes/reads Session + Participant + Vote across the restart, asserts lazy association navigation.
- `src/test/kotlin/org/example/muvimatchr/support/PostgresTestSupport.kt` - fixed (Rule 1): singleton-container pattern instead of `@Testcontainers`/`@Container` per-class lifecycle.

## Decisions Made
- **Worktree base mismatch resolved via fast-forward.** This plan's executor worktree branched before several prior-session commits (KANBAN.md, session-closeout skill, README/licensing merge, and — critically — Plan 01-01/01-02's actual implementation) landed on `main`. Confirmed zero unique commits on the worktree branch (`git rev-list --count main..HEAD` = 0), so `git merge --ff-only main` was risk-free and brought the worktree fully current before Task 1 started.
- **PostgresTestSupport's shared-fixture lifecycle fixed at its root cause, not worked around per-test.** Rather than giving `VoteRepositoryTest` its own separate container (which would have masked the real problem and left `SessionRepositoryTest`/any future repository test class vulnerable to the same bug), the shared base class itself was corrected using Testcontainers' own documented pattern for containers shared across multiple test classes.
- **VoteRepositoryTest reads all assertions through the injected `JdbcTemplate`, never through the repository**, per the plan's explicit instruction — `@Modifying` native statements bypass the JPA persistence context, so a repository-level read after `upsertVote` could return a stale cached entity and report a false green.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] PostgresTestSupport's shared Testcontainers fixture broke when a second consumer test class was added**
- **Found during:** Task 2's full-suite verification (`./gradlew test`, run after Task 2's own `VoteRepositoryTest`-only run had already passed in isolation).
- **Issue:** `PostgresTestSupport` (Plan 01-02) declared its shared static Postgres container with `@Container @ServiceConnection @JvmStatic` inside a `@Testcontainers`-annotated abstract base class. The JUnit 5 Testcontainers extension's container lifecycle for a `@Container` field is scoped **per test class**: it started the container fresh for the first subclass to run (`SessionRepositoryTest`), then — because a second subclass (`VoteRepositoryTest`) was added in this plan — stopped that container after `SessionRepositoryTest`'s tests finished and started a *new* container instance (on a new ephemeral port) for `VoteRepositoryTest`. Spring's `TestContext` framework, however, caches the `ApplicationContext` (and its `DataSource`/connection pool) by configuration key, and both test classes share the identical `@SpringBootTest` configuration inherited from `PostgresTestSupport` — so `VoteRepositoryTest` was handed back the *first* class's cached context, whose `DataSource` still pointed at the now-dead first container's port. Every JDBC connection attempt then failed with `Connection to localhost:XXXXX refused`, timing out after 30s per connection attempt. This only manifested when running the full suite (both classes in the same JVM); `VoteRepositoryTest` run in isolation passed cleanly, since there was no first-class-then-second-class sequencing to trigger the restart.
- **Fix:** Switched `PostgresTestSupport` to Testcontainers' documented "singleton container" pattern for fixtures shared across multiple test classes: removed `@Testcontainers`/`@Container` from the field entirely and instead start the container once, manually, via `.apply { start() }` in the companion object initializer. The container now starts exactly once per JVM and is never explicitly stopped by a JUnit extension (Testcontainers' Ryuk reaper cleans it up at JVM exit) — eliminating the stop/restart-on-second-class behavior that caused the stale-port mismatch. `@ServiceConnection` still applies correctly without the `@Testcontainers` extension, since it only needs Spring to detect the annotated field during context bootstrap, not JUnit-managed start/stop.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/support/PostgresTestSupport.kt`.
- **Verification:** Full `./gradlew test` re-run: 6/6 tests green (1 `RestartSurvivalTest` + 2 `SessionRepositoryTest` + 3 `VoteRepositoryTest`), 0 failures, 0 skipped. Re-ran twice to confirm no port-mismatch flake.
- **Committed in:** `099ad01` (folded into Task 2's commit, since the bug blocked Task 2's own `./gradlew test` acceptance criterion and was discovered during that task's verification, before any Task 3 work began).

---

**Total deviations:** 1 auto-fixed (Rule 1 — bug in a shared fixture from a prior plan, surfaced by this plan's second consumer of it).
**Impact on plan:** No scope creep. The fix corrects the root cause in the shared fixture itself (not a per-test workaround), which is exactly the kind of latent bug this deviation class exists to catch before it silently breaks a later phase's repository tests too.

## Issues Encountered
- Worktree spawned from a base predating several `main` commits (see Decisions Made) — resolved via `git merge --ff-only main`, no conflicts, zero risk given zero unique worktree commits.
- `PostgresTestSupport` shared-fixture lifecycle bug (see Deviations) — did not manifest in Task 1 (no new test class added) or in `VoteRepositoryTest` run in isolation; only surfaced on the full-suite run after adding the second subclass, matching the exact "isolated pass, full-suite fail" pattern Plan 01-02's own Known Issues section already flagged as worth watching for with Testcontainers-based tests.

## User Setup Required
None. Colima container runtime and the `JAVA_HOME`/`DOCKER_HOST`/`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` environment exports required by this machine (documented in Plans 01-01/01-02) were already in place; no new external service configuration needed.

## Known Stubs
None. `Participant`, `ParticipantRepository`, `Vote`, `VoteRepository`, and both new migrations are the real, final shapes Phases 2 and 4 build their entities and aggregation queries directly against — not prototypes. Roster/status logic on `Participant` and vote-recording business logic on top of `VoteRepository.upsertVote` are intentionally out of this phase's scope per CONTEXT.md's phase boundary (Phase 2 and Phase 4 respectively), not stubs left behind.

## Final Schema Shape (for Phases 2 and 4)

| Table | Constraint / Index | Notes |
|-------|---------------------|-------|
| `session` | `uq_session_join_code` (unique index on `join_code`) | Plan 01-02 |
| `participant` | FK `session_id → session(id)`; `idx_participant_session_id` | This plan |
| `vote` | FK `session_id → session(id)`; FK `participant_id → participant(id)`; `uq_vote_session_participant_movie` UNIQUE `(session_id, participant_id, movie_id)`; `idx_vote_session_id` | This plan |

**Vote upsert conflict target:** `ON CONFLICT (session_id, participant_id, movie_id) DO UPDATE SET choice = EXCLUDED.choice, voted_at = now()` — Phase 4's vote-recording service must call `VoteRepository.upsertVote(id, sessionId, participantId, movieId, choice)` with a fresh `UUID` on every call (the conflict path discards the passed `id` on update, keeping the original row's id); the five parameters map 1:1 to `@Param("id")`, `@Param("sessionId")`, `@Param("participantId")`, `@Param("movieId")`, `@Param("choice")`.

## Next Phase Readiness
Phase 1's full schema (Session/Participant/Vote), entities, repositories, and the D-03 restart proof across all three entities are complete. All four ROADMAP Phase 1 success criteria have a passing automated test: restart survival and Flyway reapply-safety → `RestartSurvivalTest`; vote tuple constraint + working upsert → `VoteRepositoryTest`; join-code uniqueness → `SessionRepositoryTest`. This is the last plan in Phase 1 — from this executor's view, Phase 1's ROADMAP success criteria appear fully met; recommend the orchestrator proceed to phase verification/closeout next. Phase 2 (Session/Lobby) and Phase 3 (TMDB Catalog) can both build directly on this schema with no further Phase 1 work required.

---
*Phase: 01-persistence-foundation*
*Completed: 2026-09-02*
