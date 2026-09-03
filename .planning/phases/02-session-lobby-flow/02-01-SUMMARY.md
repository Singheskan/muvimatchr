---
phase: 02-session-lobby-flow
plan: 01
subsystem: auth
tags: [spring-boot, kotlin, jpa, postgres, mockmvc, bearer-token, sha-256, flyway]

# Dependency graph
requires:
  - phase: 01-persistence-foundation
    provides: Session/Participant/Vote entities and repositories, Flyway V1-V3, Testcontainers Postgres test harness
provides:
  - "POST /api/sessions — creates a session with a unique join code (DB-level collision retry)"
  - "POST /api/sessions/{joinCode}/participants — joins a session with a display name, issues a server-issued 256-bit token, persists only its SHA-256 hash"
  - "GET /api/sessions/{sessionId}/participants/me — bearer-token identity resolution and resume, no duplicate participant row"
  - "org.example.muvimatchr.auth package: TokenService, CurrentParticipant/CurrentParticipantArgumentResolver — first auth mechanism in the codebase"
affects: [02-02-multi-participant-hardening, phase-4-vote-match, phase-6-frontend-spa]

actuals:
  tokens: 5860
  tasks: 2
  commits: 3

tech-stack:
  added: []
  patterns:
    - "HandlerMethodArgumentResolver-based custom auth (@CurrentParticipant) standing in for Spring Security, registered via WebMvcConfigurer.addArgumentResolvers"
    - "SecureRandom + SHA-256 hash-at-rest token issuance (TokenService.issue()/hash())"
    - "DB-level unique-constraint collision retry with per-attempt save() (no shared @Transactional) to avoid Postgres transaction-abort-on-failed-statement"
    - "MockMvc-against-real-Postgres integration test (no mocks) as the first REST-layer test in the codebase"

key-files:
  created:
    - src/main/resources/db/migration/V4__add_participant_token.sql
    - src/main/kotlin/org/example/muvimatchr/auth/TokenService.kt
    - src/main/kotlin/org/example/muvimatchr/auth/CurrentParticipantArgumentResolver.kt
    - src/main/kotlin/org/example/muvimatchr/config/WebMvcConfig.kt
    - src/main/kotlin/org/example/muvimatchr/session/SessionService.kt
    - src/main/kotlin/org/example/muvimatchr/session/ParticipantService.kt
    - src/main/kotlin/org/example/muvimatchr/session/SessionController.kt
    - src/main/kotlin/org/example/muvimatchr/session/ParticipantController.kt
    - src/test/kotlin/org/example/muvimatchr/session/ParticipantControllerTest.kt
  modified:
    - src/main/kotlin/org/example/muvimatchr/session/Participant.kt
    - src/main/kotlin/org/example/muvimatchr/session/SessionRepository.kt
    - src/main/kotlin/org/example/muvimatchr/session/ParticipantRepository.kt
    - src/test/kotlin/org/example/muvimatchr/voting/VoteRepositoryTest.kt
    - src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt
    - build.gradle.kts

key-decisions:
  - "Crockford Base32 alphabet (0-9, A-Z minus I/L/O/U) confirmed at exactly 32 characters for join-code generation, resolving 02-RESEARCH.md's flagged uncertainty"
  - "SessionService.createSession() deliberately carries no @Transactional — each join-code retry attempt calls plain save() so it gets its own transaction, avoiding Postgres's whole-transaction-abort-after-failed-statement behavior"
  - "Spring Boot 4.1.1 modularized @AutoConfigureMockMvc out of spring-boot-test-autoconfigure into a new spring-boot-webmvc-test artifact (package org.springframework.boot.webmvc.test.autoconfigure) — added as an explicit testImplementation dependency"
  - "Spring Boot 4.1.1 autoconfigures a Jackson 3 (tools.jackson.databind.ObjectMapper) bean, not the classic com.fasterxml.jackson.databind.ObjectMapper the project's jackson-module-kotlin dependency provides — test code autowires the tools.jackson type to match what's actually autoconfigured"

requirements-completed: [SESH-01, SESH-02, SESH-03, SESH-05]

coverage:
  - id: D1
    description: "Creating a session via POST /api/sessions returns 201 with a unique, non-blank join code"
    requirement: "SESH-01"
    verification:
      - kind: integration
        ref: "ParticipantControllerTest#POST sessions twice returns two distinct non-blank join codes"
        status: pass
    human_judgment: false
  - id: D2
    description: "Joining a session with a display name issues a server-issued, unguessable token (never the display name or participant id); the persisted token_hash is a 64-char SHA-256 hex digest, never the raw token"
    requirement: "SESH-02"
    verification:
      - kind: integration
        ref: "ParticipantControllerTest#joining with a valid join code returns a server-issued token distinct from name and id, hashed at rest"
        status: pass
    human_judgment: false
  - id: D3
    description: "Requests without a valid bearer token cannot resolve as any participant; unknown/malformed/missing tokens return 401, cross-session tokens return 404, unknown join codes return 404"
    requirement: "SESH-03"
    verification:
      - kind: integration
        ref: "ParticipantControllerTest#me with no Authorization header returns 401"
        status: pass
      - kind: integration
        ref: "ParticipantControllerTest#me with an unrecognized bearer token returns 401"
        status: pass
      - kind: integration
        ref: "ParticipantControllerTest#me with a valid token but a different session's sessionId returns 404"
        status: pass
      - kind: integration
        ref: "ParticipantControllerTest#joining with an unknown join code returns 404"
        status: pass
    human_judgment: false
  - id: D4
    description: "A participant who returns later with their token resumes as the same participant (identical participantId), with no duplicate participant row created"
    requirement: "SESH-05"
    verification:
      - kind: integration
        ref: "ParticipantControllerTest#me with a valid token returns the same participantId issued at join time"
        status: pass
      - kind: integration
        ref: "ParticipantControllerTest#me does not insert a new participant row on resume"
        status: pass
    human_judgment: false

duration: ~35min
completed: 2026-09-03
status: complete
---

# Phase 2 Plan 1: Session Create/Join/Resume Tracer Summary

**End-to-end session creation, token-issued join, and bearer-token resume wired through real REST controllers against Postgres — this codebase's first auth mechanism and first REST controller layer, proven by one MockMvc-against-real-Postgres integration test with zero mocks.**

## Performance

- **Duration:** ~35 min
- **Started:** 2026-09-03 (session execution start)
- **Completed:** 2026-09-03T18:15:00Z
- **Tasks:** 2 completed
- **Files modified:** 15 (9 created, 6 modified)

## Accomplishments
- `POST /api/sessions` creates a session with a DB-level-unique 6-character Crockford Base32 join code, retrying on collision without a shared transaction (avoids Postgres's transaction-abort-after-failed-statement behavior).
- `POST /api/sessions/{joinCode}/participants` issues a 256-bit `SecureRandom` token per join, persists only its SHA-256 hash (`token_hash`, 64 hex chars, unique-indexed), and returns the raw token exactly once in the response body plus a ready-made `resumeUrl`.
- `GET /api/sessions/{sessionId}/participants/me` resolves identity from an `Authorization: Bearer` header via a custom `HandlerMethodArgumentResolver` (`@CurrentParticipant`), correctly rejecting missing/malformed/unrecognized tokens (401) and cross-session tokens (404), and resuming without creating a duplicate participant row.
- One integration test class (`ParticipantControllerTest`, 8 tests) proves the entire path end-to-end via real MockMvc calls against a real Testcontainers Postgres instance — no service-layer shortcuts, no mocks.

## Task Commits

Each task was committed atomically:

1. **Task 1: Create session, join with token issuance — one path, hash-at-rest proven** - `d5e56b5` (feat)
2. **Task 2: Bearer-token resolution and resume (GET /me)** - `5a9f93e` (test, RED) then `edfa75f` (feat, GREEN)

**Plan metadata:** committed alongside this SUMMARY (see below)

_Note: Task 2 used TDD (RED/GREEN); no REFACTOR commit was needed — the GREEN implementation was already clean._

## Files Created/Modified
- `src/main/resources/db/migration/V4__add_participant_token.sql` - adds `participant.token_hash` (VARCHAR(64), NOT NULL) plus `uq_participant_token_hash` unique index
- `src/main/kotlin/org/example/muvimatchr/session/Participant.kt` - adds `tokenHash` constructor property
- `src/main/kotlin/org/example/muvimatchr/session/SessionRepository.kt` - adds `findByJoinCode`
- `src/main/kotlin/org/example/muvimatchr/session/ParticipantRepository.kt` - adds `findByTokenHash`
- `src/main/kotlin/org/example/muvimatchr/auth/TokenService.kt` - `issue()`/`hash()`, `IssuedToken` DTO
- `src/main/kotlin/org/example/muvimatchr/auth/CurrentParticipantArgumentResolver.kt` - `@CurrentParticipant` annotation + resolver
- `src/main/kotlin/org/example/muvimatchr/config/WebMvcConfig.kt` - registers the resolver
- `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt` - `createSession()` with join-code collision retry
- `src/main/kotlin/org/example/muvimatchr/session/ParticipantService.kt` - `join()` orchestrates lookup + token issuance + persistence
- `src/main/kotlin/org/example/muvimatchr/session/SessionController.kt` - `POST /api/sessions`
- `src/main/kotlin/org/example/muvimatchr/session/ParticipantController.kt` - `POST /{joinCode}/participants`, `GET /{sessionId}/participants/me`
- `src/test/kotlin/org/example/muvimatchr/session/ParticipantControllerTest.kt` - 8 end-to-end tests
- `src/test/kotlin/org/example/muvimatchr/voting/VoteRepositoryTest.kt` - fixed `Participant(...)` call site for the new required `tokenHash` arg
- `src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt` - fixed `Participant(...)` call site + added missing `java.util.UUID` import
- `build.gradle.kts` - added `spring-boot-webmvc-test` testImplementation dependency

## Decisions Made
- Crockford Base32 alphabet character count (32) verified explicitly in code comments, resolving the plan's own flagged uncertainty.
- `SessionService.createSession()` has no `@Transactional`; each retry attempt's plain `save()` gets its own transaction, exactly as the plan's objective specified to avoid Postgres's whole-transaction-abort behavior.
- No host/owner flag, no session-lock/state-machine field added (D-03/D-04 honored, confirmed by grep-level review of `Participant.kt`/`Session.kt`).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocker] `RestartSurvivalTest.kt` was missing the `java.util.UUID` import the plan assumed already existed**
- **Found during:** Task 1
- **Issue:** The plan's `<action>` stated "Both files already import `java.util.UUID`," but `RestartSurvivalTest.kt` had no such import — only `VoteRepositoryTest.kt` did. Adding the `tokenHash = UUID.randomUUID().toString()` argument without the import would not compile.
- **Fix:** Added `import java.util.UUID` to `RestartSurvivalTest.kt`.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt`
- **Verification:** `./gradlew build` compiles and the full pre-existing suite passes.
- **Committed in:** `d5e56b5`

**2. [Rule 3 - Blocker] `@AutoConfigureMockMvc` unresolved — Spring Boot 4.1.1 modularized MockMvc test autoconfiguration into a new artifact**
- **Found during:** Task 1
- **Issue:** `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` (the class the plan's read_first analog implied) does not exist in this Spring Boot version's `spring-boot-test-autoconfigure` jar. Boot 4.1.1 split MockMvc test support into a dedicated `spring-boot-webmvc-test` artifact, relocating the class to `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
- **Fix:** Added `testImplementation("org.springframework.boot:spring-boot-webmvc-test")` to `build.gradle.kts` (version resolved automatically via the existing Spring Boot dependency-management BOM) and imported the relocated package in the test.
- **Files modified:** `build.gradle.kts`, `src/test/kotlin/org/example/muvimatchr/session/ParticipantControllerTest.kt`
- **Verification:** `./gradlew test --tests "*.ParticipantControllerTest"` compiles and passes.
- **Committed in:** `d5e56b5`

**3. [Rule 3 - Blocker] Autowiring `com.fasterxml.jackson.databind.ObjectMapper` failed — Boot 4.1.1 autoconfigures a Jackson 3 bean instead**
- **Found during:** Task 1
- **Issue:** Spring Boot 4.1.1's `spring-boot-jackson` autoconfiguration registers a `tools.jackson.databind.ObjectMapper` (Jackson 3.x) bean, not the classic `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2.x) that the project's pre-existing `jackson-module-kotlin` dependency provides on the classpath. Autowiring the classic type in the test threw `NoSuchBeanDefinitionException`.
- **Fix:** Imported and autowired `tools.jackson.databind.ObjectMapper` in `ParticipantControllerTest.kt` instead — its `readValue(String, Class)` API is compatible for this test's needs.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/session/ParticipantControllerTest.kt`
- **Verification:** `./gradlew test --tests "*.ParticipantControllerTest"` passes.
- **Committed in:** `d5e56b5`

---

**Total deviations:** 3 auto-fixed (all Rule 3 - blocking compile/runtime issues, all caused by this being the project's first encounter with Spring Boot 4.1.1's REST/JSON test-support module layout)
**Impact on plan:** All three were necessary environment/framework-version corrections to get the plan's exact design compiling and running; no scope creep, no architectural changes, no deviation from the planned token scheme, endpoint shapes, or entity design.

## Issues Encountered
None beyond the three auto-fixed deviations above — all resolved within the fix-attempt budget on first diagnosis.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness

Plan 02-02 (multi-participant hardening: Bean Validation, additional edge cases) can proceed directly on top of:
- `SessionService`, `ParticipantService` (constructor-injectable, no changes needed to add `@Valid`)
- `ParticipantController.JoinRequest` (currently has no validation annotations — Plan 02-02's explicit job)
- `TokenService`, `CurrentParticipantArgumentResolver`, `WebMvcConfig` (stable, no changes anticipated)

All artifacts listed in 02-01-PLAN.md's "Artifacts this phase produces" section exist exactly as specified. `./gradlew dependencies --configuration compileClasspath | grep -c jakarta.validation` confirmed `0` — Bean Validation is genuinely not yet on the classpath, matching the plan's explicit expectation.

## Self-Check: PASSED

- All 14 key files confirmed present on disk (`FOUND:` for every path in `must_haves.artifacts` plus modified Phase 1 files).
- All 3 commits (`d5e56b5`, `5a9f93e`, `edfa75f`) confirmed present via `git log --oneline --grep="02-01"`.
- `./gradlew test --tests "*.ParticipantControllerTest"` re-run: 8/8 tests pass.
- `./gradlew test` (full suite): pass, no regressions to Phase 1 tests.
- `./gradlew build`: pass.
- `grep -c 'addArgumentResolvers' WebMvcConfig.kt` = 1.
- `./gradlew dependencies --configuration compileClasspath | grep -c jakarta.validation` = 0, as the plan's verification block requires.
