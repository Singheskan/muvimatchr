---
phase: 02-session-lobby-flow
plan: 02-01, 02-02
reviewed: 2026-09-03T00:00:00Z
depth: standard
files_reviewed: 16
files_reviewed_list:
  - build.gradle.kts
  - src/main/kotlin/org/example/muvimatchr/auth/CurrentParticipantArgumentResolver.kt
  - src/main/kotlin/org/example/muvimatchr/auth/TokenService.kt
  - src/main/kotlin/org/example/muvimatchr/config/WebMvcConfig.kt
  - src/main/kotlin/org/example/muvimatchr/session/Participant.kt
  - src/main/kotlin/org/example/muvimatchr/session/ParticipantController.kt
  - src/main/kotlin/org/example/muvimatchr/session/ParticipantRepository.kt
  - src/main/kotlin/org/example/muvimatchr/session/ParticipantService.kt
  - src/main/kotlin/org/example/muvimatchr/session/SessionController.kt
  - src/main/kotlin/org/example/muvimatchr/session/SessionRepository.kt
  - src/main/kotlin/org/example/muvimatchr/session/SessionService.kt
  - src/main/resources/db/migration/V4__add_participant_token.sql
  - src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt
  - src/test/kotlin/org/example/muvimatchr/session/ParticipantControllerTest.kt
  - src/test/kotlin/org/example/muvimatchr/session/SessionServiceTest.kt
  - src/test/kotlin/org/example/muvimatchr/voting/VoteRepositoryTest.kt
findings:
  critical: 0
  warning: 4
  info: 7
  total: 11
status: issues_found
---

# Phase 2: Code Review Report

**Verdict:** No blocking security/correctness defects found — the core crypto/auth tracer (SecureRandom token issuance, SHA-256 hash-at-rest, hash-then-compare bearer resolution, parameterized-only queries) is sound, but 4 warnings and 7 info-level gaps should be addressed before this becomes the long-term contract other phases build on.

**Reviewed:** 2026-09-03
**Depth:** standard
**Files Reviewed:** 16
**Status:** issues_found

## Summary

This is a focused, well-scoped tracer slice (session create → join → token issuance → bearer resolution/resume). I read every file in full and specifically stress-tested the areas the task called out:

- **Token entropy:** `TokenService.issue()` (`src/main/kotlin/org/example/muvimatchr/auth/TokenService.kt:12-17`) correctly uses `SecureRandom` (not `Random`), 32 bytes (256 bits), Base64URL-no-padding encoding. Good.
- **Hash-at-rest:** `TokenService.hash()` (same file, lines 19-22) uses `MessageDigest.getInstance("SHA-256")` and hex-encodes via `"%02x".format(it)`. I verified empirically (via a standalone Java harness reproducing Kotlin's `Byte.format` semantics) that Java's `Formatter` masks negative `Byte` values to unsigned before hex conversion, so this produces exactly 64 lowercase hex characters for a 32-byte digest with no sign-extension bug — confirmed correct, not just "looks right." The raw token is never persisted anywhere; only `tokenHash` is stored, and only `IssuedToken.rawToken`/`JoinResult.rawToken` carry the raw value, both purely in-memory, returned once in the join HTTP response body.
- **Timing-attack posture:** `CurrentParticipantArgumentResolver` (`src/main/kotlin/org/example/muvimatchr/auth/CurrentParticipantArgumentResolver.kt:39-42`) hashes the incoming bearer token and looks it up by exact hash equality via `findByTokenHash` — hash-then-compare, which by design sidesteps raw-token timing attacks. Correct approach, no hand-rolled comparison to worry about.
- **SQL injection surface:** Zero native `@Query`/string-concatenated SQL in any of the reviewed repositories (`SessionRepository.findByJoinCode`, `ParticipantRepository.findByTokenHash` are both plain Spring-Data-derived methods); the only raw SQL is the static DDL in `V4__add_participant_token.sql` and parameterized (`?`) `JdbcTemplate` queries in tests. No injection surface found.
- **Join-code retry transaction boundary:** Confirmed by direct inspection that `SessionService.kt` contains no `@Transactional` anywhere in the file and its retry loop calls `.save(` (not `.saveAndFlush(`) — the documented Postgres-abort avoidance pattern is genuinely followed, not just described in comments.
- **Bean Validation:** `JoinRequest.displayName` (`ParticipantController.kt:47-51`) is annotated `@field:NotBlank @field:Size(max = 100)`, exactly matching the `display_name VARCHAR(100)` column, with `@Valid` correctly applied to the controller parameter (line 23). No completeness gap here.

None of the above are findings — they're the "hold the line" checks the review specifically targeted, and all passed. The issues below are genuine gaps, mostly around edge-case robustness and forward-compatibility rather than the crypto/auth core itself.

## Warnings

### WR-01: `V4__add_participant_token.sql` adds a `NOT NULL` column with no `DEFAULT` — will hard-fail against any environment with existing participant rows

**File:** `src/main/resources/db/migration/V4__add_participant_token.sql:1`
**Issue:** `ALTER TABLE participant ADD COLUMN token_hash VARCHAR(64) NOT NULL;` has no `DEFAULT`, so it only works because no environment currently has any `participant` rows (true today, since no join endpoint existed before this phase). This is a correct-for-now, fragile-forever assumption: any future seed data, manual QA insert, or staging snapshot with existing participant rows before this migration runs will make Flyway fail the whole deploy with a `NOT NULL` violation, and there is no comment in the migration file itself flagging this precondition for whoever runs it next.
**Fix:** Either add an inline comment in the migration noting the "no existing rows" precondition explicitly (so a future migration author doesn't copy this pattern blindly onto a populated table), or make the migration robust regardless of data state, e.g.:
```sql
ALTER TABLE participant ADD COLUMN token_hash VARCHAR(64);
-- backfill here if any rows could exist
ALTER TABLE participant ALTER COLUMN token_hash SET NOT NULL;
CREATE UNIQUE INDEX uq_participant_token_hash ON participant (token_hash);
```

### WR-02: Join-code lookup is case-sensitive with no normalization

**File:** `src/main/kotlin/org/example/muvimatchr/session/ParticipantService.kt:17`, `src/main/kotlin/org/example/muvimatchr/session/SessionRepository.kt:7`
**Issue:** `SessionService` always generates uppercase Crockford Base32 codes (`JOIN_CODE_ALPHABET` is all-uppercase, `SessionService.kt:9`), but `findByJoinCode(joinCode)` does an exact-case Postgres `VARCHAR` comparison with no `upper()`/case-insensitive normalization applied to the incoming path variable in `ParticipantService.join()`. A human typing a shared 6-character code (the entire point of D-01's "short typable join code") on a mobile keyboard with autocapitalize-off, or a client that lowercases URLs, will get a spurious 404 for a code that is objectively valid.
**Fix:** Normalize the input before lookup: `sessionRepository.findByJoinCode(joinCode.trim().uppercase())` in `ParticipantService.join()`.

### WR-03: No rate limiting on the join endpoint — 6-character join code space (32^6 ≈ 1.07 billion) is guessable at scale

**File:** `src/main/kotlin/org/example/muvimatchr/session/ParticipantController.kt:22-36`, `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt:9-11`
**Issue:** The STRIDE register in 02-01-PLAN.md rates token-guessing as infeasible (256-bit, correctly), but the *join code* itself — the sole credential gating who can join a session at all — is only 6 characters from a 32-character alphabet, and nothing in `ParticipantController`/`ParticipantService` throttles repeated `POST /api/sessions/{joinCode}/participants` attempts per caller. An attacker who wants to intrude on an active session (not attack a specific token) only needs to guess one valid, currently-open join code, which is far more tractable than exhausting the full keyspace given the low per-guess cost of an HTTP POST and no per-IP/per-time limiting anywhere in this layer.
**Fix:** At minimum, add IP- or session-scoped rate limiting in front of the join endpoint (e.g., Bucket4j filter, or a reverse-proxy rate limit rule); consider also logging repeated 404s from the same source for anomaly detection. Flag this explicitly in the phase's threat register (it currently only covers token-guessing, not join-code-guessing).

### WR-04: Raw bearer token embedded in `resumeUrl` query string

**File:** `src/main/kotlin/org/example/muvimatchr/session/ParticipantController.kt:26`
**Issue:** `resumeUrl = "/session/${participant.session.id}?token=${result.rawToken}"` puts the raw, still-valid 256-bit credential into a URL. This is called out and knowingly accepted in 02-01-PLAN.md's threat model (T-02-02), but URLs containing secrets are a well-known leak vector regardless: they land in browser history, get sent in the `Referer` header to any third-party resource the resume page subsequently loads, and often end up in server/proxy access logs verbatim. Re-flagging here because "accepted risk" in a plan document doesn't reduce the actual exposure once real users start bookmarking/sharing these links.
**Fix:** At minimum ensure the eventual frontend (Phase 6) never causes the resume page to fetch third-party resources before moving the token out of the URL (e.g., into `sessionStorage` immediately, then history.replaceState to strip the query string). Consider whether the `token` response field alone is sufficient and `resumeUrl` should be dropped in favor of the frontend constructing it client-side without transiting it through a shareable-looking link shape.

## Info

### IN-01: `CurrentParticipantArgumentResolver` doesn't validate the annotated parameter's declared type

**File:** `src/main/kotlin/org/example/muvimatchr/auth/CurrentParticipantArgumentResolver.kt:25-26`
**Issue:** `supportsParameter` only checks for the `@CurrentParticipant` annotation, not that the parameter type is `Participant`. If a future controller method annotates a parameter of a different type with `@CurrentParticipant`, Spring will attempt to assign the returned `Participant` to it and fail with an opaque runtime `ClassCastException`/`IllegalArgumentException` rather than a clear resolver-level error.
**Fix:** Add a defensive check, e.g. `require(parameter.parameterType == Participant::class.java) { "@CurrentParticipant only supports Participant parameters" }` in `resolveArgument`, or assert in `supportsParameter`.

### IN-02: No handling for a (vanishingly unlikely) `token_hash` collision in `ParticipantService.join()`

**File:** `src/main/kotlin/org/example/muvimatchr/session/ParticipantService.kt:20-23`
**Issue:** Unlike `SessionService.createSession()`, which explicitly retries on `DataIntegrityViolationException` from the 6-character join-code's unique index, `ParticipantService.join()` has no `try`/`catch` around `participantRepository.save(...)`. A `token_hash` collision against `uq_participant_token_hash` is astronomically improbable (256-bit space) so this is not a practical risk, but it is an inconsistency in error-handling philosophy within the same file/plan — worth a one-line comment explaining why no retry is needed here (unlike the join-code path), so a future reader doesn't wonder if it was simply forgotten.
**Fix:** Add a short comment noting the collision probability is negligible and intentionally unhandled (surfaces as 500 if it ever happens), for symmetry with `SessionService`'s explicit reasoning comment.

### IN-03: Bearer scheme match is exact-case ("Bearer " only)

**File:** `src/main/kotlin/org/example/muvimatchr/auth/CurrentParticipantArgumentResolver.kt:36`
**Issue:** `header.startsWith("Bearer ")` requires the literal capitalization "Bearer". RFC 7235 defines the auth-scheme token as case-insensitive, so a spec-compliant client sending `bearer <token>` or `BEARER <token>` would be rejected as "Malformed Authorization header" (401) even though the token itself may be valid.
**Fix:** `header.startsWith("Bearer ", ignoreCase = true)` (and strip using the actual matched-length prefix, not a hardcoded `removePrefix("Bearer ")`, to handle the case-insensitive variant correctly).

### IN-04: `JoinRequest.displayName` is not trimmed before persistence

**File:** `src/main/kotlin/org/example/muvimatchr/session/ParticipantController.kt:47-51`
**Issue:** `@field:NotBlank` rejects whitespace-only input but does not trim the value used downstream — a display name like `"  Alice  "` passes validation and is persisted with leading/trailing whitespace intact, which will render oddly in any future participant list UI.
**Fix:** Trim in the controller/service before persisting, e.g. `participantService.join(joinCode, request.displayName.trim())`.

### IN-05: `SessionController` doesn't translate join-code-space exhaustion into a defined HTTP response

**File:** `src/main/kotlin/org/example/muvimatchr/session/SessionController.kt:14-19`, `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt:30`
**Issue:** If `SessionService.createSession()` exhausts `MAX_JOIN_CODE_ATTEMPTS` (10), it throws a plain `IllegalStateException`, which `SessionController` does not catch — it falls through to Spring Boot's default handler and surfaces as an undifferentiated 500. This is an extremely rare failure mode today (32^6 keyspace, few sessions), but as usage grows it's worth a deliberate 503/"try again" response rather than a bare 500.
**Fix:** Either catch `IllegalStateException` in the controller and map it to `ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ...)`, or have `SessionService` throw a dedicated exception type that a `@ControllerAdvice` maps explicitly.

### IN-06: Test JSON bodies built via raw string interpolation

**File:** `src/test/kotlin/org/example/muvimatchr/session/ParticipantControllerTest.kt:49, 95, 166, 183`
**Issue:** Request bodies like `"""{"displayName":"$displayName"}"""` are constructed by direct string interpolation rather than via `objectMapper.writeValueAsString(...)`. This works today only because every test-supplied `displayName` value happens to be quote-free (`"Alice"`, `"Bob"`, `"A".repeat(101)`, etc.); a future test author who adds a display name containing a `"` character would silently produce invalid JSON and get a confusing test failure unrelated to the behavior under test.
**Fix:** Use `objectMapper.writeValueAsString(mapOf("displayName" to displayName))` (or a small `JoinRequest`-shaped DTO) instead of raw string templates.

### IN-07: `SessionServiceTest` doesn't assert generated join codes only use `JOIN_CODE_ALPHABET` characters

**File:** `src/test/kotlin/org/example/muvimatchr/session/SessionServiceTest.kt:22-29`
**Issue:** The shape test checks length (`== 6`, `<= 16`) and non-blankness but never asserts that every character of the generated `joinCode` is a member of `JOIN_CODE_ALPHABET` — so a hypothetical off-by-one in `generateJoinCode()`'s alphabet indexing (e.g., an index error that occasionally emits an unintended character) would not be caught by this test.
**Fix:** Add `assertTrue(session.joinCode.all { it in JOIN_CODE_ALPHABET })` (or duplicate the alphabet string as a test constant if the production `private const val` isn't visibly importable).

---

_Reviewed: 2026-09-03_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
