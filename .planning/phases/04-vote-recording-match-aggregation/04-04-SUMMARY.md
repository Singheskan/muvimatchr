---
phase: 04-vote-recording-match-aggregation
plan: 04
subsystem: testing
tags: [kotlin, spring-boot, testcontainers, postgres, hikari, concurrency, junit5]

# Dependency graph
requires:
  - phase: 04-01
    provides: VoteService.recordVote (session-scoped FOR UPDATE lock + upsert + status read inside one @Transactional), MatchAggregationService.computeStatus, PostgresTestSupport singleton-container pattern
  - phase: 04-03
    provides: MatchAggregationService.computeStatus's full SessionVoteStatus shape (matchedMovieIds/likeCounts) exercised by the post-race assertions
provides:
  - VoteServiceConcurrencyTest — ten independently-raced simultaneous three-way finishes against real Postgres, proving exactly-once completion and zero lost writes, with the connection pool ruled out as an accidental serialiser
  - RestartSurvivalTest — a second test method proving a VoteService.recordVote-written vote and its pinned-deck JSONB snapshot both survive a real application-context restart
affects: [05-realtime-updates]

# Actuals (#2632)
actuals:
  tokens: 3389
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "CountDownLatch starting gun for genuine multi-thread simultaneity in tests, never Thread.sleep-based lineup -- enforced by a structural grep gate in the plan's own verify step"
    - "Pool-vs-database serialisation disambiguation: assert HikariDataSource.maximumPoolSize exceeds the racing thread count before trusting a concurrency test's green result, so a pool bottleneck can never masquerade as proof of a database-level lock"
    - "Repeated-race discipline: a single green concurrent test run is weak evidence for a probabilistic defect -- ten independently created sessions raced in one test method is what turns it into a gate"

key-files:
  created:
    - src/test/kotlin/org/example/muvimatchr/voting/VoteServiceConcurrencyTest.kt
  modified:
    - src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt

key-decisions:
  - "The pool-size assertion runs once, inside the single race test method, before the ten-iteration loop starts -- not as a separate test -- so its failure message names pool sizing directly rather than the race surfacing as an unexplained downstream timeout."
  - "The restart test's new method reads the post-restart vote row via a direct jdbcTemplate query rather than through VoteRepository, matching the existing test's own established pattern of querying flyway_schema_history directly and keeping the assertion independent of any repository-layer behavior being tested."

patterns-established:
  - "Pattern 5: three-thread CountDownLatch race repeated across N independently created fixtures within one test method, with bounded future timeouts and named per-thread exception surfacing, as the standard shape for proving persistence-layer concurrency claims in this codebase"

requirements-completed: [VOTE-02, VOTE-05]

coverage:
  - id: D1
    description: "When the last participants of a session submit their final vote simultaneously, every vote is persisted and exactly one response reports completion -- proven across ten independent races, not one lucky run"
    requirement: "VOTE-02, VOTE-05"
    verification:
      - kind: integration
        ref: "VoteServiceConcurrencyTest.kt#ten independent simultaneous three-way finishes each persist every vote and complete exactly once"
        status: pass
    human_judgment: false
  - id: D2
    description: "The connection pool is ruled out as an accidental serialiser: the pool carries strictly more connections than there are racing threads"
    requirement: "VOTE-02"
    verification:
      - kind: integration
        ref: "VoteServiceConcurrencyTest.kt#ten independent simultaneous three-way finishes each persist every vote and complete exactly once (Hikari maximumPoolSize assertion)"
        status: pass
    human_judgment: false
  - id: D3
    description: "A vote written through VoteService.recordVote (the locked transactional path, not a bare repository save) is readable by a fresh, independently-constructed application context after a restart"
    requirement: "VOTE-02"
    verification:
      - kind: integration
        ref: "RestartSurvivalTest.kt#a vote written through VoteService and the pinned deck it targeted both survive a restart"
        status: pass
    human_judgment: false
  - id: D4
    description: "A session's pinned deck JSONB snapshot and deck_pinned_at timestamp survive a restart intact, and the recomputed MatchAggregationService status after restart matches the status captured before it"
    requirement: "VOTE-02"
    verification:
      - kind: integration
        ref: "RestartSurvivalTest.kt#a vote written through VoteService and the pinned deck it targeted both survive a restart"
        status: pass
    human_judgment: false
  - id: D5
    description: "Flyway does not reapply its migrations on the second startup after a restart -- confirmed unchanged across both the original and the new restart test method"
    requirement: "VOTE-02"
    verification:
      - kind: integration
        ref: "RestartSurvivalTest.kt#a vote written through VoteService and the pinned deck it targeted both survive a restart (flyway_schema_history row count assertion)"
        status: pass
    human_judgment: false
  - id: D6
    description: "The concurrency test passes on a second, cache-defeating run (--rerun-tasks), distinguishing a genuinely deterministic race from one Gradle merely reported as up-to-date"
    verification:
      - kind: integration
        ref: "./gradlew test --tests \"*.voting.VoteServiceConcurrencyTest\" --rerun-tasks"
        status: pass
    human_judgment: false
  - id: D7
    description: "The concurrency test still passes on a machine under concurrent load, not just an idle one"
    verification: []
    human_judgment: true
    rationale: "This is the plan's own designated end-of-phase human-check item -- it requires a human to run the test once more alongside another build running concurrently, which cannot be simulated deterministically at plan-authoring time."

duration: 20min
completed: 2026-09-05
status: complete
---

# Phase 4 Plan 4: Concurrency & Restart Durability Proof Summary

**Ten independently-raced simultaneous three-way vote finishes against real Postgres (CountDownLatch starting gun, Hikari pool ruled out as serialiser) plus a new restart test proving a VoteService.recordVote-written vote and the pinned-deck JSONB snapshot both survive a real application-context restart.**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-09-05T09:55:00Z (approx.)
- **Completed:** 2026-09-05T10:02:08Z
- **Tasks:** 2
- **Files modified:** 2 (1 created, 1 modified)

## Accomplishments
- `VoteServiceConcurrencyTest` races three real threads through `VoteService.recordVote` against real Postgres, released by a single `CountDownLatch` (never a fixed-duration sleep), over ten independently created sessions inside one test method -- proving the session-scoped `FOR UPDATE` row lock serialises every simultaneous final vote with zero lost writes and exactly-one completion, not a coincidence of an idle machine.
- The test rules out the connection pool as an accidental serialiser up front: it asserts Hikari's `maximumPoolSize` exceeds the racing thread count, with a failure message that names pool sizing directly rather than surfacing as a mysterious timeout.
- Both automated re-runs the plan specified passed: a normal run and a `--rerun-tasks` cache-defeating run, confirming the race is genuinely deterministic rather than reported green by an up-to-date Gradle cache.
- `RestartSurvivalTest` gained a second test method (the original, repository-level test is untouched) that writes its vote through the transactional, locked `VoteService.recordVote` path -- the service-layer durability claim Phase 1's repository-level test did not cover -- and additionally proves the pinned-deck JSONB snapshot (`deck_pinned_at`, `pinnedMovies`) round-trips intact across a real shutdown/restart, with the recomputed `MatchAggregationService.computeStatus` matching the pre-shutdown status exactly.
- The whole suite (`./gradlew test`) passes green together, including both new tests alongside every prior phase's test.

## Task Commits

1. **Task 1: A synchronised simultaneous finish completes the session exactly once, repeatedly** - `607ff6f` (test)
2. **Task 2: A service-written vote and the pinned deck both survive a real restart** - `25a0283` (test)

## Files Created/Modified
- `src/test/kotlin/org/example/muvimatchr/voting/VoteServiceConcurrencyTest.kt` - new: `PostgresTestSupport` subclass racing three threads through `recordVote` over ten independent sessions, with the Hikari pool-size guard and post-race `jdbcTemplate`/`computeStatus` cross-checks
- `src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt` - added a second `@Test` method proving service-path vote durability and pinned-deck survival across a restart; the original method is unchanged

## Decisions Made
- Pool-size assertion placed once, inside the race test itself, ahead of the ten-iteration loop -- see frontmatter `key-decisions` for the rationale (a dedicated separate test would split the failure message from the race it protects).
- Post-restart vote-row assertion in the new `RestartSurvivalTest` method reads via a direct `jdbcTemplate` query rather than `VoteRepository`, matching the existing test's own precedent of querying `flyway_schema_history` directly and keeping the read independent of the repository layer under test elsewhere.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The plan's own `Thread.sleep` verify-gate grep matched a comment, not code**
- **Found during:** Task 1, running the plan's structural grep verification (`! grep -q 'Thread.sleep' ...`)
- **Issue:** The file's header comment originally described the test's discipline using the literal phrase "never Thread.sleep" -- a true statement about the code, but the plan's own automated gate greps the whole file for that exact string with no code/comment distinction, so the comment itself tripped the gate meant to catch the pattern in test logic.
- **Fix:** Reworded the comment to convey the same discipline ("never a fixed-duration sleep") without the literal substring, leaving all test logic unchanged.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/voting/VoteServiceConcurrencyTest.kt`
- **Verification:** `! grep -q 'Thread.sleep' ...` now exits 0 (no match); full test class still passes.
- **Committed in:** `607ff6f` (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - a comment tripping the plan's own literal-string verify gate)
**Impact on plan:** Cosmetic only -- no test logic changed, no scope creep. The plan's verification intent (no sleep-based thread lineup) was already satisfied by the actual code; only the comment's wording needed adjustment.

## Issues Encountered
None beyond the one deviation documented above.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Both of the roadmap's explicitly-named durability claims for Phase 4 (simultaneous-finish exactly-once completion, and service-path restart survival including the pinned deck) are now proven against real Postgres, closing VOTE-02 and VOTE-05 at the concurrency/durability layer.
- One human-check item remains open per the plan's own design: re-running `VoteServiceConcurrencyTest` once more on a machine under concurrent load, to confirm the race isn't only holding on an idle machine. This is an end-of-phase check, not a blocker for this plan; tracked as coverage item D7 (human_judgment: true) above.
- Phase 5 (real-time updates) can build on `VoteService.recordVote` and `MatchAggregationService.computeStatus` with confidence that the underlying lock and restart-durability behavior is proven, not just plausible.
- No other blockers. This closes out Phase 4 (04-01 through 04-04, all four plans complete).

---
*Phase: 04-vote-recording-match-aggregation*
*Completed: 2026-09-05*

## Self-Check: PASSED

All created/modified files confirmed present on disk; both task commit hashes (`607ff6f`, `25a0283`) confirmed present in `git log`.
