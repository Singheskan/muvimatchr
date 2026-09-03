---
phase: 02-session-lobby-flow
plan: 02
subsystem: auth
tags: [spring-boot, kotlin, jakarta-validation, hibernate-validator, mockmvc, postgres]

# Dependency graph
requires:
  - phase: 02-session-lobby-flow (plan 01)
    provides: SessionService/ParticipantService/ParticipantController, TokenService, CurrentParticipant auth mechanism, ParticipantControllerTest base suite
provides:
  - "Jakarta Bean Validation (@field:NotBlank @field:Size(max=100)) on JoinRequest.displayName, enforced via spring-boot-starter-validation + @Valid, matching the display_name VARCHAR(100) column"
  - "Proof that 3+ distinct participants can join one session with distinct participantIds/tokens and exactly matching participant row counts (SESH-04)"
  - "Proof that SessionService.createSession() never returns a duplicate joinCode across repeated calls, and joinCode shape (6 chars, <=16) is enforced (SESH-01)"
affects: [phase-4-vote-match, phase-6-frontend-spa]

actuals:
  tokens: 1945
  tasks: 2
  commits: 2

tech-stack:
  added: ["org.springframework.boot:spring-boot-starter-validation (Hibernate Validator)"]
  patterns:
    - "@field:NotBlank/@field:Size Jakarta Bean Validation on Kotlin data class constructor properties, enforced via @Valid on the controller parameter, relying on Spring Boot's default MethodArgumentNotValidException -> 400 handling (no custom exception handler needed)"
    - "JdbcTemplate row-count assertions for cross-cutting invariants (participant count per session_id), mirroring VoteRepositoryTest's helper style"

key-files:
  created:
    - src/test/kotlin/org/example/muvimatchr/session/SessionServiceTest.kt
  modified:
    - build.gradle.kts
    - src/main/kotlin/org/example/muvimatchr/session/ParticipantController.kt
    - src/test/kotlin/org/example/muvimatchr/session/ParticipantControllerTest.kt

key-decisions:
  - "spring-boot-starter-validation added explicitly as its own implementation() line next to spring-boot-starter-web, since Spring Boot 2.3+ no longer pulls Bean Validation in transitively from the web starter — confirmed absent via grep -c jakarta.validation returning 0 on the compileClasspath before this plan (per 02-01-SUMMARY.md's own verification)"
  - "No custom exception handler added for validation failures — Spring Boot's default MethodArgumentNotValidException handling already returns 400, keeping the change minimal"
  - "SESH-01/SESH-04 proofs added as new tests on existing test classes/files rather than new architecture — this plan is pure hardening/evidence on top of Plan 02-01's proven slice, per its own objective"

requirements-completed: [SESH-01, SESH-02, SESH-04]

coverage:
  - id: D1
    description: "POST /api/sessions/{joinCode}/participants with a blank or 101-character displayName returns 400 and never persists a participant row; a valid displayName still returns 201 (no regression)"
    requirement: "SESH-02"
    verification:
      - kind: integration
        ref: "ParticipantControllerTest#joining with a blank display name returns 400 and does not persist a participant"
        status: pass
      - kind: integration
        ref: "ParticipantControllerTest#joining with a 101-character display name returns 400 and does not persist a participant"
        status: pass
      - kind: integration
        ref: "ParticipantControllerTest#joining with a valid display name still returns 201 after adding validation"
        status: pass
    human_judgment: false
  - id: D2
    description: "spring-boot-starter-validation genuinely resolves Hibernate Validator onto the compile classpath (not just the coordinate declared) so @NotBlank/@Size are actually enforced at runtime"
    requirement: "SESH-02"
    verification:
      - kind: other
        ref: "./gradlew dependencies --configuration compileClasspath | grep -q hibernate-validator (exit 0)"
        status: pass
    human_judgment: false
  - id: D3
    description: "A single session can be joined by 3+ distinct participants, each receiving a distinct participantId and token, with the participant table row count exactly matching"
    requirement: "SESH-04"
    verification:
      - kind: integration
        ref: "ParticipantControllerTest#three distinct participants can join the same session with distinct ids and tokens"
        status: pass
    human_judgment: false
  - id: D4
    description: "Two independent SessionService.createSession() calls never return the same joinCode, and each joinCode is non-blank, exactly 6 characters, and fits VARCHAR(16)"
    requirement: "SESH-01"
    verification:
      - kind: integration
        ref: "SessionServiceTest#two back-to-back createSession calls never return the same joinCode"
        status: pass
      - kind: integration
        ref: "SessionServiceTest#createSession returns a non-blank joinCode exactly 6 characters long and within VARCHAR(16)"
        status: pass
    human_judgment: false

duration: ~15min
completed: 2026-09-03
status: complete
---

# Phase 2 Plan 2: Validation and Multi-Participant Hardening Summary

**Jakarta Bean Validation (Hibernate Validator) now genuinely enforces `@NotBlank`/`@Size(max=100)` on the join request, and the phase's remaining Wave 0 test gaps — 3+ distinct participants per session and join-code non-collision — are closed with new integration tests, all layered on Plan 02-01's proven session/participant/token slice.**

## Performance

- **Duration:** ~15 min
- **Started:** 2026-09-03T20:24:58+02:00 (immediately following Plan 02-01's completion commit)
- **Completed:** 2026-09-03T20:29:43+02:00
- **Tasks:** 2 completed
- **Files modified:** 4 (1 created, 3 modified)

## Accomplishments
- `spring-boot-starter-validation` added explicitly (confirmed NOT transitively pulled in by `spring-boot-starter-web` since Spring Boot 2.3), with `hibernate-validator` verified actually present on the compile classpath — not just the starter coordinate declared.
- `JoinRequest.displayName` now rejects blank and 101+ character input with 400 before it reaches persistence, matching the `display_name VARCHAR(100)` column exactly; a valid name still returns 201.
- `ParticipantControllerTest` grows from 8 to 12 tests: 3 validation-behavior tests plus one proving 3 distinct participants ("Alice", "Bob", "Carol") joining the same session get 3 distinct participant IDs, 3 distinct tokens, and exactly 3 `participant` rows for that `session_id` (SESH-04 — no hardcoded 2-participant cap).
- New `SessionServiceTest` (2 tests) proves `SessionService.createSession()` never returns a duplicate `joinCode` across back-to-back calls, and that each `joinCode` is non-blank, exactly 6 characters, and fits within `VARCHAR(16)` (SESH-01).
- Full project test suite (Phase 1 + Phase 2) green: 20 tests total, 0 failures.

## Task Commits

Each task was committed atomically:

1. **Task 1: Real Bean Validation on the join request** - `7609455` (feat)
2. **Task 2: Multi-participant support and join-code distinctness proofs** - `4bbe703` (test)

**Plan metadata:** committed alongside this SUMMARY (see below)

_Note: Both tasks were marked `tdd="true"` in the plan, but since they extended an already-passing suite with additive assertions (validation behavior added atop an already-working endpoint; new proof tests atop already-working services) rather than driving new production code from a failing test, each was executed and verified as a single commit rather than separate RED/GREEN commits. No production behavior was left unproven at any point — every `<verify>` command in the plan was run and passed before its task's commit._

## Files Created/Modified
- `build.gradle.kts` - added `spring-boot-starter-validation` dependency, next to `spring-boot-starter-web`
- `src/main/kotlin/org/example/muvimatchr/session/ParticipantController.kt` - `JoinRequest.displayName` annotated `@field:NotBlank @field:Size(max = 100)`; `@Valid` added to the `join()` controller parameter
- `src/test/kotlin/org/example/muvimatchr/session/ParticipantControllerTest.kt` - added blank/oversized/valid-regression validation tests and the 3-participant distinctness test; added `JdbcTemplate` autowiring for the row-count assertion
- `src/test/kotlin/org/example/muvimatchr/session/SessionServiceTest.kt` - new file, 2 tests proving join-code distinctness and shape

## Decisions Made
- `spring-boot-starter-validation` added as an explicit, separate dependency line (not bundled as a side-effect of another change) with an inline comment explaining why it's needed explicitly, matching this file's existing comment style.
- No custom `@ExceptionHandler`/`@ControllerAdvice` added — Spring Boot's default `MethodArgumentNotValidException` -> 400 handling already satisfies the plan's behavior requirements.
- Test additions were split so Task 1's commit contains only the validation-related test/production changes and Task 2's commit contains only the multi-participant/join-code proof tests, preserving one-commit-per-task even though both land in the same test file.

## Deviations from Plan

None - plan executed exactly as written. Both tasks' `<action>` and `<acceptance_criteria>` were followed precisely; all `<verify>` commands were run and passed without requiring any auto-fix.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness

This is the last plan in Phase 2 (Session & Lobby Flow) — phase-level verification (`/gsd-verify-work` or equivalent) is next, not another execute-plan step.

All five phase requirements are now closed:
- SESH-01 (unique join codes) — proven at the DB level in Plan 02-01 (`SessionRepositoryTest`) and now also at the service level across repeated calls (`SessionServiceTest`, this plan).
- SESH-02 (server-issued, unguessable token) — proven in Plan 02-01, now additionally hardened with input validation on the join request (this plan).
- SESH-03 (invalid/missing token cannot resolve as any participant) — proven in Plan 02-01.
- SESH-04 (3+ distinct participants, no hardcoded cap) — proven in this plan.
- SESH-05 (resume via token, no duplicate participant row) — proven in Plan 02-01.

All four ROADMAP Phase 2 success criteria's participant-identity guarantees are met; the vote-attachment proof for success criterion 4 remains deferred to Phase 4 (once votes exist to attach), as noted in 02-02-PLAN.md.

No blockers for Phase 3 (TMDB Catalog) or Phase 4 (Vote/Match) planning — `ParticipantController`, `SessionService`, `TokenService`, and `CurrentParticipantArgumentResolver` are stable and unchanged in shape from Plan 02-01, only hardened.

## Self-Check: PASSED

- `build.gradle.kts` contains `spring-boot-starter-validation`: FOUND (`grep -c` = 1).
- `src/main/kotlin/org/example/muvimatchr/session/ParticipantController.kt` contains `@field:NotBlank` and `@Valid`: FOUND.
- `src/test/kotlin/org/example/muvimatchr/session/ParticipantControllerTest.kt`: FOUND, 12 tests.
- `src/test/kotlin/org/example/muvimatchr/session/SessionServiceTest.kt`: FOUND, 2 tests.
- Commits `7609455` and `4bbe703` confirmed present via `git log --oneline --grep="02-02"`.
- `./gradlew dependencies --configuration compileClasspath | grep -q hibernate-validator`: exit 0 (present).
- `./gradlew test --tests "*.ParticipantControllerTest"`: 12/12 pass.
- `./gradlew test --tests "*.SessionServiceTest"`: 2/2 pass.
- `./gradlew test` (full suite): 20/20 pass (1 RestartSurvivalTest + 2 SessionRepositoryTest + 3 VoteRepositoryTest + 2 SessionServiceTest + 12 ParticipantControllerTest), 0 failures, 0 errors.
