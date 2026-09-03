---
phase: 02-session-lobby-flow
verified: 2026-09-03T20:50:00Z
status: passed
score: 9/9 must-haves verified
behavior_unverified: 0
overrides_applied: 0
deferred:
  - truth: "A participant who returns later using their previously issued link/token is recognized as the same participant, and any votes they already cast are still attributed to them (vote-attachment half only)"
    addressed_in: "Phase 4"
    evidence: "02-CONTEXT.md's Phase Boundary explicitly excludes voting from Phase 2 ('no voting or match logic (Phase 4)'); Phase 4's goal is 'Participants' swipes are recorded correctly and exactly once each' with Success Criterion 2 ('Submitting a second vote for the same participant+movie updates the existing vote rather than creating a duplicate row'). Votes will be persisted keyed on the stable participantId this phase already proves survives resume (ParticipantControllerTest#me-does-not-insert-a-new-participant-row-on-resume), so vote attribution across resume is a direct, testable consequence of Phase 2's identity guarantee plus Phase 4's participant-keyed vote schema — it cannot be proven until votes exist. 02-02-PLAN.md and 02-02-SUMMARY.md both explicitly flag this as deferred, not overlooked."
---

# Phase 2: Session & Lobby Flow Verification Report

**Phase Goal:** A host can create a session and share it, and any number of participants can join with just a display name, get a real identity token, and leave/resume without losing progress.
**Verified:** 2026-09-03T20:50:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Creating a session returns a unique, shareable join code/link (ROADMAP SC1) | ✓ VERIFIED | `SessionController.createSession()` → `SessionService.createSession()` (DB-level unique-index collision retry, 6-char Crockford Base32). Proven by real, passing tests: `ParticipantControllerTest#POST sessions twice returns two distinct non-blank join codes` and `SessionServiceTest` (both tests, distinctness + shape). Test suite run confirmed 0 failures. |
| 2 | Joining with a valid code + display name returns a server-issued, unguessable token (not an echo of the display name); requests without a valid token cannot act as that participant (ROADMAP SC2) | ✓ VERIFIED | `ParticipantService.join()` calls `TokenService.issue()` (SecureRandom 256-bit, SHA-256 hash-at-rest, only `tokenHash` persisted). Proven by `ParticipantControllerTest#joining with a valid join code returns a server-issued token distinct from name and id, hashed at rest` (asserts token ≠ displayName, token ≠ participantId, persisted `tokenHash` is 64 hex chars and ≠ raw token). Rejection proven by `#me with no Authorization header returns 401` and `#me with an unrecognized bearer token returns 401` — both pass. |
| 3 | A single session can be joined by 3+ distinct participants, confirming group support isn't hardcoded to exactly 2 (ROADMAP SC3) | ✓ VERIFIED | `ParticipantControllerTest#three distinct participants can join the same session with distinct ids and tokens` — 3 sequential joins ("Alice"/"Bob"/"Carol"), asserts 3 distinct `participantId`s, 3 distinct tokens, and `SELECT count(*) FROM participant WHERE session_id = ?` = 3 via real `JdbcTemplate` query. Test passes. |
| 4a | A participant who returns later using their token is recognized as the same participant, no duplicate row created (ROADMAP SC4, identity half) | ✓ VERIFIED | `CurrentParticipantArgumentResolver` resolves by `findByTokenHash`; `ParticipantControllerTest#me with a valid token returns the same participantId issued at join time` and `#me does not insert a new participant row on resume` (asserts `participantRepository.count()` unchanged before/after resume) — both pass. Cross-session rejection also proven: `#me with a valid token but a different session's sessionId returns 404`. |
| 4b | ...and any votes they already cast are still attributed to them (ROADMAP SC4, vote-attachment half) | — DEFERRED | No voting exists in this phase's scope (02-CONTEXT.md explicitly excludes it). See `deferred` in frontmatter — addressed by Phase 4's participant-keyed vote persistence, building on this phase's proven stable-identity-across-resume guarantee. |
| 5 | No host/owner flag or session-lock/state-machine field added (D-03/D-04 honored) | ✓ VERIFIED | `grep -rniE "host|owner|isLocked|locked|sessionState"` against `Session.kt`/`Participant.kt` returns no matches. |
| 6 | Blank/oversized display name rejected with 400, never persisted | ✓ VERIFIED | `JoinRequest.displayName` has `@field:NotBlank @field:Size(max = 100)`, `@Valid` on controller param; `hibernate-validator` confirmed on compile classpath (this session: `./gradlew dependencies` not re-run, but `build.gradle.kts` declares `spring-boot-starter-validation` and the two negative-path tests — `#joining with a blank display name returns 400...` and `#joining with a 101-character display name returns 400...` — both assert `participantRepository.count()` unchanged and both pass, which is only possible if the validator is genuinely wired, not merely declared). |
| 7 | Unknown join code returns 404, not 500, not a silently-created session | ✓ VERIFIED | `ParticipantService.join()` throws `ResponseStatusException(NOT_FOUND, ...)` on a null `findByJoinCode` lookup. `ParticipantControllerTest#joining with an unknown join code returns 404` passes. |

**Score:** 9/9 truths verified (0 present-behavior-unverified). SC4's vote-attachment clause is explicitly deferred to Phase 4 per the phase's own scope boundary, not a gap.

### Deferred Items

| # | Item | Addressed In | Evidence |
|---|------|-------------|----------|
| 1 | "...any votes they already cast are still attributed to them" (ROADMAP Phase 2 SC4, second clause) | Phase 4 | 02-CONTEXT.md Phase Boundary excludes voting from Phase 2; Phase 4 goal/SC2 covers per-participant vote persistence/update; this phase already proves the participant identity that Phase 4's votes will be keyed on survives resume with no duplicate row. |

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/resources/db/migration/V4__add_participant_token.sql` | `token_hash` column + `uq_participant_token_hash` unique index | ✓ VERIFIED | Content confirmed: `ALTER TABLE participant ADD COLUMN token_hash VARCHAR(64) NOT NULL;` + `CREATE UNIQUE INDEX uq_participant_token_hash ON participant (token_hash);`. V1-V3 untouched. |
| `src/main/kotlin/org/example/muvimatchr/session/Participant.kt` | `tokenHash` field | ✓ VERIFIED | `@Column(name = "token_hash", nullable = false, length = 64) val tokenHash: String` present; still a plain `class`, not `data class`. |
| `src/main/kotlin/org/example/muvimatchr/session/SessionRepository.kt` | `findByJoinCode` | ✓ VERIFIED | Present, Spring-Data-derived. |
| `src/main/kotlin/org/example/muvimatchr/session/ParticipantRepository.kt` | `findByTokenHash` | ✓ VERIFIED | Present, Spring-Data-derived. |
| `src/main/kotlin/org/example/muvimatchr/auth/TokenService.kt` | SecureRandom issuance + SHA-256 hashing | ✓ VERIFIED | `SecureRandom`, 32-byte token, Base64URL-no-padding, `MessageDigest.getInstance("SHA-256")`, hex-encoded. |
| `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt` | join-code generation w/ retry | ✓ VERIFIED | `JOIN_CODE_ALPHABET` (32-char Crockford Base32), no `@Transactional`, `.save(` not `.saveAndFlush(`, matches plan's documented Postgres-abort avoidance. |
| `src/main/kotlin/org/example/muvimatchr/session/ParticipantService.kt` | join flow orchestration | ✓ VERIFIED | `fun join(...)` present, wired to `sessionRepository`, `tokenService`, `participantRepository`. |
| `src/main/kotlin/org/example/muvimatchr/session/SessionController.kt` | `POST /api/sessions` | ✓ VERIFIED | `@PostMapping` present, calls `sessionService.createSession()`. |
| `src/main/kotlin/org/example/muvimatchr/session/ParticipantController.kt` | join + me endpoints | ✓ VERIFIED | Both `/participants` (POST) and `/participants/me` (GET) present. |
| `src/main/kotlin/org/example/muvimatchr/auth/CurrentParticipantArgumentResolver.kt` | bearer-token resolver | ✓ VERIFIED | `CurrentParticipant` annotation + `CurrentParticipantArgumentResolver` class both present, correct 401 logic. |
| `src/main/kotlin/org/example/muvimatchr/config/WebMvcConfig.kt` | resolver registration | ✓ VERIFIED | `addArgumentResolvers` present; `MuviMatchrApplication` in the root package component-scans `config`/`auth`/`session` subpackages — resolver is live, confirmed empirically via passing 401/200 tests exercising the real Spring context. |
| `src/test/kotlin/org/example/muvimatchr/session/ParticipantControllerTest.kt` | end-to-end MockMvc proof | ✓ VERIFIED | 12 test methods present and passing (confirmed via actual `./gradlew test` run, not SUMMARY claim). |
| `build.gradle.kts` | `spring-boot-starter-validation` | ✓ VERIFIED | Line 25: `implementation("org.springframework.boot:spring-boot-starter-validation")`. |
| `src/test/kotlin/org/example/muvimatchr/session/SessionServiceTest.kt` | join-code distinctness/shape proof | ✓ VERIFIED | 2 test methods present and passing. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `ParticipantController` | `ParticipantService` | `participantService.join(...)` | ✓ WIRED | Confirmed by direct code read; exercised end-to-end by passing tests. |
| `ParticipantService` | `TokenService` | `tokenService.issue()` | ✓ WIRED | Confirmed; raw token only ever appears in response body, hash persisted. |
| `CurrentParticipantArgumentResolver` | `WebMvcConfig` | `addArgumentResolvers` registration | ✓ WIRED | Confirmed present; empirically proven live by passing 401/200/404 tests that only work if the resolver is registered on the Spring MVC request pipeline. |
| `CurrentParticipantArgumentResolver` | `ParticipantRepository` | `findByTokenHash` keyed on `TokenService.hash()` | ✓ WIRED | Confirmed by code read and by passing resume/rejection tests. |
| `build.gradle.kts` | `ParticipantController` (JoinRequest) | `spring-boot-starter-validation` supplies `jakarta.validation` annotations | ✓ WIRED | `@field:NotBlank @field:Size(max=100)` present and functionally proven by passing 400-rejection tests with `participantRepository.count()` unchanged assertions — this can only pass if Hibernate Validator is genuinely on the classpath and enforcing, not merely declared. |

### Behavioral Spot-Checks / Test Execution

Ran the actual test suite from scratch (not trusting SUMMARY.md's "20/20 pass" claim):

```
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
DOCKER_HOST=unix:///Users/psrg/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew test --rerun
```

Result: `BUILD SUCCESSFUL`. Verified via JUnit XML reports (not console text alone):

| Test class | tests | failures | errors |
|---|---|---|---|
| SessionRepositoryTest | 2 | 0 | 0 |
| SessionServiceTest | 2 | 0 | 0 |
| RestartSurvivalTest | 1 | 0 | 0 |
| VoteRepositoryTest | 3 | 0 | 0 |
| ParticipantControllerTest | 12 | 0 | 0 |
| **Total** | **20** | **0** | **0** |

This independently reproduces the SUMMARY.md claim of "20/20 pass" — confirmed, not merely trusted.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| SESH-01 | 02-01, 02-02 | Host can create a session and get a unique shareable join code/link | ✓ SATISFIED | `SessionController`/`SessionService`; `SessionServiceTest` + `ParticipantControllerTest` distinctness tests, all passing. |
| SESH-02 | 02-01, 02-02 | Participant joins via code/link by picking a display name — no account required | ✓ SATISFIED | `ParticipantController.join()`; validated, tested, passing. |
| SESH-03 | 02-01 | Server-issued unguessable token, not spoofable via guessed code | ✓ SATISFIED | `TokenService` (256-bit SecureRandom + SHA-256 hash-at-rest); 401 rejection tests passing. |
| SESH-04 | 02-02 | Session supports 2+ participants | ✓ SATISFIED | 3-participant test passing, no cap in code. |
| SESH-05 | 02-01 | Participant can leave and resume without losing votes | ✓ SATISFIED (identity half); vote-half deferred to Phase 4 | Resume/no-duplicate-row tests passing; vote persistence doesn't exist yet (out of phase scope, see Deferred). |

No orphaned requirements — REQUIREMENTS.md traceability table lists only SESH-01..05 for Phase 2, and both plans together declare exactly that set.

### Anti-Patterns Found

None. Scanned all files modified/created by this phase (`V4__add_participant_token.sql`, `Participant.kt`, `SessionRepository.kt`, `ParticipantRepository.kt`, `TokenService.kt`, `CurrentParticipantArgumentResolver.kt`, `WebMvcConfig.kt`, `SessionService.kt`, `ParticipantService.kt`, `SessionController.kt`, `ParticipantController.kt`, `build.gradle.kts`) for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER|not yet implemented`. Zero matches. No debt markers.

**Carried forward from 02-REVIEW.md (advisory, non-blocking per project policy — not phase-blocking, but noted for future hardening):**
- WR-01: `V4` migration's `NOT NULL` column has no `DEFAULT` — safe today (no existing rows) but fragile if a future migration author copies the pattern onto a populated table.
- WR-02: Join-code lookup is case-sensitive with no normalization — a legitimately-issued code typed in lowercase would 404 spuriously.
- WR-03: No rate limiting on the join endpoint — the 6-char/32-alphabet join code (not the 256-bit token) is the actual guessable credential surface.
- WR-04: Raw bearer token embedded in `resumeUrl` query string — accepted tradeoff per D-02/T-02-02, but real leak-vector exposure (browser history, Referer header, access logs) once a real frontend exists.

These are quality/hardening concerns, not goal-blocking defects — the phase's stated success criteria do not require rate limiting, case-insensitive codes, or token-out-of-URL handling, and REVIEW.md itself found 0 critical issues and rated the core crypto/auth tracer sound.

### Human Verification Required

None. All observable truths were verifiable programmatically via code inspection and re-running the actual test suite.

### Gaps Summary

No gaps found. All 5 requirements (SESH-01 through SESH-05) and all four ROADMAP Phase 2 success criteria are satisfied, with the single exception of SC4's vote-attachment clause, which is legitimately out of this phase's scope (no voting exists yet) and is explicitly deferred to Phase 4 — a scope boundary set by 02-CONTEXT.md itself, not an oversight. The test suite was independently re-run (not trusted from SUMMARY.md) and reproduces the claimed 20/20 pass with 0 failures/errors, confirmed via JUnit XML reports. Code review's 4 warnings (case-sensitivity, no rate limiting, no-DEFAULT migration, token-in-URL) are hardening items appropriately deferred, not phase blockers.

---

_Verified: 2026-09-03T20:50:00Z_
_Verifier: Claude (gsd-verifier)_
