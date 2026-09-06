---
phase: 06-frontend-spa
plan: 02
subsystem: api
tags: [kotlin, spring-boot, jpa, jackson, mockmvc, native-sql, roster, tdd]

# Dependency graph
requires:
  - phase: 04-vote-recording-match-aggregation
    provides: "MatchAggregationService.computeStatus, VoteRepository.findActiveParticipantIds (D-06 inactivity rule), SessionService.pinnedMovies (deckSize source)"
  - phase: 06-frontend-spa
    provides: "06-01: single-JAR SPA scaffold, bearer-token auth pattern, frontend/src/api/types.ts DTO shapes the SPA will bind the roster response into"
provides:
  - "MatchAggregationService.computeRoster(sessionId) -- named, join-ordered, per-person voting progress derived from the same live inactivity/deckSize rules computeStatus uses"
  - "GET /api/sessions/{sessionId}/votes/roster -- membership-gated REST endpoint the D-10 waiting screen will poll"
affects: [06-03, 06-04, 06-05]

# Actuals (#2632)
actuals:
  tokens: 6240
  tasks: 2
  commits: 4

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Roster read model reuses the single existing findActiveParticipantIds() query rather than restating the D-06 inactivity expression a second time -- enforced by a grep gate counting call sites"
    - "@get:JsonProperty pin on every Kotlin Boolean response-DTO field (isFinished, isActive) -- same fix pattern as VoteStatusResponse.isComplete, now applied a second and third time"
    - "Additive-endpoint-over-widened-payload: a new GET route was added rather than growing VoteStatusResponse, keeping the Phase 4/5 REST/WebSocket parity contract untouched"

key-files:
  created:
    - src/test/kotlin/org/example/muvimatchr/voting/SessionRosterTest.kt
  modified:
    - src/main/kotlin/org/example/muvimatchr/session/ParticipantRepository.kt
    - src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt
    - src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt
    - src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt

key-decisions:
  - "Followed the plan's TDD structure literally per task, not per plan: each task got its own RED commit then GREEN commit, so the file grew in two test-then-implementation cycles rather than one big test file up front. To prove Task 2's RED honestly (the controller code for Task 1 was already written when Task 2's tests were authored), the Task 2 controller diff was git-stashed before running the new MockMvc tests, confirmed 4/5 failing, then restored for the GREEN commit."
  - "Pinned decks in the new MockMvc roster tests directly via SessionService.pinDeck(), not through the HTTP /deck endpoint -- avoids pulling in TmdbMockServerSupport/MockWebServer fixtures for tests that aren't exercising the catalog layer, matching MatchAggregationServiceTest's own in-process convention."

patterns-established:
  - "A read-model method that derives from another service's already-proven live query (activeIds) must call that query, never restate its expression -- verified via a grep gate counting call sites (2, not more) plus a negative grep for the query's inner SQL fragment appearing anywhere outside the repository."

requirements-completed: [RSLT-01]

coverage:
  - id: D1
    description: "MatchAggregationService.computeRoster returns a stable, join-ordered, named participant roster (participantId, displayName, votedCount, isFinished, isActive) derived from the same live inactivity and deckSize rules computeStatus already uses"
    requirement: RSLT-01
    verification:
      - kind: unit
        ref: "src/test/kotlin/org/example/muvimatchr/voting/SessionRosterTest.kt (6 read-model cases: join order + progress, idle-but-listed, active-set parity with computeStatus, never-pinned deckSize=0 guard, stable ordering, 404 parity)"
        status: pass
      - kind: unit
        ref: "grep gate: findActiveParticipantIds called exactly twice in MatchAggregationService.kt (computeStatus + computeRoster, no third copy)"
        status: pass
      - kind: unit
        ref: "grep gate: no COALESCE/last_voted_at SQL fragment duplicated into MatchAggregationService.kt outside a comment"
        status: pass
    human_judgment: false
  - id: D2
    description: "GET /api/sessions/{sessionId}/votes/roster is membership-gated (200 for a member, 404 for a different session's token, 401 with no header) and serializes boolean fields under their declared wire names (isFinished, isActive), with no session-level completion flag on the shape"
    requirement: RSLT-01
    verification:
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/voting/SessionRosterTest.kt (5 MockMvc cases, wire names asserted via jsonPath, plus a body-content check for absent tokenHash/likeCounts/isComplete)"
        status: pass
      - kind: unit
        ref: "grep gate: exactly 3 @get:JsonProperty pins in VoteController.kt (pre-existing isComplete plus the two new roster booleans)"
        status: pass
      - kind: integration
        ref: "./gradlew build (full Phase 1-5 regression suite, including MatchAggregationServiceTest/VoteControllerTest/SessionStatusBroadcastTest)"
        status: pass
    human_judgment: false

# Metrics
duration: ~20min
completed: 2026-09-06
status: complete
---

# Phase 6 Plan 2: Session Roster Read Model & Endpoint Summary

**Added `MatchAggregationService.computeRoster()` and `GET /api/sessions/{sessionId}/votes/roster`, giving the waiting screen a named, join-ordered per-person progress list that reuses Phase 4's exact inactivity/deckSize rules instead of a second, independently-drifting definition.**

## Performance

- **Duration:** ~20 min (context loading + two TDD task cycles + full-suite verification)
- **Started:** 2026-09-06T20:44:19+02:00 (first task commit)
- **Completed:** 2026-09-06T20:49:58+02:00 (last task commit)
- **Tasks:** 2/2
- **Files modified:** 5 (4 production/test files touched, 1 new test file)

## Accomplishments

- `ParticipantRepository.findBySession_IdOrderByCreatedAtAsc` and `VoteRepository.findVoteCountsByParticipant` added, following the file's existing native-query/derived-query conventions (`CAST(:sessionId AS uuid)`, named `@Param` bindings, no filter on `choice` since a PASS is progress too).
- `MatchAggregationService.computeRoster(sessionId)` computes a stable `SessionRoster` (`sessionId`, `deckSize`, `participants: List<ParticipantProgress>`) by calling the *same* `findActiveParticipantIds` query `computeStatus` already calls (now exactly two call sites, no restated inactivity SQL) and the same pinned-snapshot `deckSize` guard.
- `GET /api/sessions/{sessionId}/votes/roster` added to `VoteController`, inheriting the identical membership guard (`participant.session.id != sessionId` -> 404) and `@CurrentParticipant` resolution (missing/invalid token -> 401) that `recordVote`/`getStatus` already use.
- `SessionRosterResponse`/`ParticipantProgressResponse` DTOs with `@get:JsonProperty("isFinished")`/`@get:JsonProperty("isActive")` pins -- the same Jackson boolean-getter-prefix-stripping fix already applied to `VoteStatusResponse.isComplete`, now proven a second and third time.
- Deliberately no session-level completion flag on the roster response (prohibition P-02) -- `VoteStatusResponse.isComplete` remains the sole authority; a test asserts the roster body never contains `isComplete` or `likeCounts`.
- `SessionRosterTest.kt` covers all 11 behaviors from the plan (6 read-model, 5 endpoint) in one file, as specified.

## Task Commits

Each task was committed atomically as a TDD RED/GREEN pair:

1. **Task 1: Roster read model over the live participant and vote tables**
   - `a8a0159` (test, RED) -- `SessionRosterTest` fails to compile: `computeRoster`/`SessionRoster`/`ParticipantProgress` don't exist yet
   - `fcf9512` (feat, GREEN) -- `computeRoster` implemented; all 6 read-model cases pass; `MatchAggregationServiceTest`/`VoteControllerTest`/`SessionStatusBroadcastTest` still green
2. **Task 2: Expose the roster as a membership-gated REST endpoint**
   - `d97178f` (test, RED) -- MockMvc cases added; 4/5 fail (route not yet mapped, resolves to the app's default 404 handler instead of the endpoint's own semantics)
   - `134d6d6` (feat, GREEN) -- `VoteController.getRoster()` + DTOs added; all 11 cases pass; full `./gradlew build` green

**Plan metadata:** (this commit) -- docs: complete 06-02 plan

## Files Created/Modified

- `src/main/kotlin/org/example/muvimatchr/session/ParticipantRepository.kt` - `findBySession_IdOrderByCreatedAtAsc` derived query
- `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt` - `findVoteCountsByParticipant` native query (unfiltered by `choice`)
- `src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt` - `computeRoster`, `ParticipantProgress`, `SessionRoster`
- `src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt` - `getRoster()` endpoint, `SessionRosterResponse`, `ParticipantProgressResponse`, `SessionRoster.toResponse()`
- `src/test/kotlin/org/example/muvimatchr/voting/SessionRosterTest.kt` (new) - all 11 read-model + endpoint behaviors

## Decisions Made

- Followed the plan's per-task TDD structure literally: Task 1 got its own RED-then-GREEN commit pair, then Task 2 got its own RED-then-GREEN commit pair, rather than a single combined RED covering both tasks' behaviors up front. This matches the plan's task-level `tdd="true"` markers and produces a cleaner bisectable history (4 commits, each independently revertable).
- To prove Task 2's RED state honestly -- the roster-endpoint controller code technically didn't exist as a separate step since Task 1's file already needed later editing -- the Task 2 controller diff was written, then `git stash`-ed before running the new MockMvc tests (confirming 4/5 genuinely fail against the unmodified controller), then restored (`git stash pop`) before the GREEN commit. This is not a plan deviation, just an execution-order technique to preserve honest TDD discipline within a single execution session.
- Pinned decks in the new roster MockMvc tests directly via `SessionService.pinDeck()` (in-process), not through the HTTP `/deck` endpoint -- avoids pulling in `TmdbMockServerSupport`/`MockWebServer` fixtures for tests that aren't exercising the catalog layer, matching `MatchAggregationServiceTest`'s own established convention.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None. Docker/Testcontainers precondition was verified reachable (`docker info` succeeded) before any task work began, per the plan's precondition note.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `GET /api/sessions/{sessionId}/votes/roster` is live and proven (11 tests, full-suite regression green) -- 06-03/06-04 (swipe deck, waiting screen) can bind to it directly.
- RSLT-01 remains **not yet marked complete** in REQUIREMENTS.md: `gsd-tools query requirements.ready-ids` confirms it is still `blocked` -- 06-03 and 06-04 also declare RSLT-01 in their frontmatter, and the shared-ID gate (#2388) correctly withholds marking it `Complete` until every declaring plan has a `*-SUMMARY.md`. This is expected, not a gap.
- No blockers for the next plan in this phase.

---
*Phase: 06-frontend-spa*
*Completed: 2026-09-06*

## Self-Check: PASSED

- `src/test/kotlin/org/example/muvimatchr/voting/SessionRosterTest.kt` -- FOUND on disk.
- `src/main/kotlin/org/example/muvimatchr/session/ParticipantRepository.kt` -- FOUND, contains `findBySession_IdOrderByCreatedAtAsc`.
- `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt` -- FOUND, contains `findVoteCountsByParticipant`.
- `src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt` -- FOUND, contains `computeRoster`, `SessionRoster`, `ParticipantProgress`.
- `src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt` -- FOUND, contains `getRoster()`, `SessionRosterResponse`, `ParticipantProgressResponse`.
- Commits `a8a0159`, `fcf9512`, `d97178f`, `134d6d6` -- all present via `git log --oneline --all --grep="06-02"`.
- Re-ran all acceptance criteria from both tasks: `findActiveParticipantIds` count = 2 (PASS), no `COALESCE`/`last_voted_at` outside repository (PASS), `AS uuid` count in `VoteRepository.kt` = 8 >= 5 (PASS), no `Thread.sleep` in `SessionRosterTest.kt` (PASS), `@get:JsonProperty` count in `VoteController.kt` = 3 (PASS), no `isComplete` inside `SessionRosterResponse` (PASS).
- Re-ran plan-level `<verification>`: `./gradlew build` -- BUILD SUCCESSFUL, full Phase 1-5 suite green including `SessionRosterTest` (11/11), `MatchAggregationServiceTest`, `VoteControllerTest`, `SessionStatusBroadcastTest`.
- No missing items.
