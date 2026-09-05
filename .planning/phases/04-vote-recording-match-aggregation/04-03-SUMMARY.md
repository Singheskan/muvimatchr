---
phase: 04-vote-recording-match-aggregation
plan: 03
subsystem: api
tags: [kotlin, spring-boot, jpa, postgres, native-query, testcontainers]

# Dependency graph
requires:
  - phase: 04-01
    provides: VoteRepository.findActiveParticipantIds/countFinishedParticipants (live, non-sticky activity roster), MatchAggregationService.computeStatus, VoteController status endpoint, SessionService.pinDeck/pinnedMovies
provides:
  - VoteRepository.findUnanimousMovieIds (HAVING COUNT(DISTINCT) = participantCount, database-ordered)
  - VoteRepository.findLikeCountsBySession (unfiltered by roster/unanimity, database-ordered count DESC, movieId ASC)
  - MatchAggregationService.unanimousMovieIds/perMovieLikeCounts, folded into computeStatus
  - SessionVoteStatus.matchedMovieIds/likeCounts, VoteStatusResponse.matchedMovieIds/likeCounts
  - MatchAggregationServiceTest — 12-test behavioural suite pinning unanimity, live roster and per-movie counts
affects: [04-04-concurrency-test, 05-realtime-updates, 06-frontend-results-view]

# Actuals (#2632)
actuals:
  tokens: 6926
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Two aggregate native @Query reads sharing the same active-roster inputs as the existing completion query (findActiveParticipantIds/countFinishedParticipants), keeping unanimity and completion as two projections of one roster computation"
    - "Deterministic idle-participant testing: backdate vote.voted_at / participant.created_at via jdbcTemplate rather than Thread.sleep, exercising the real (production-default) inactivity window instead of a shrunken test-only one"
    - "Database-established ORDER BY for both new aggregate queries (movie_id ascending for unanimity, count DESC/movie_id ASC for like counts) -- verified by a structural grep gate forbidding sortedBy/sortedWith/Comparator in MatchAggregationService.kt so the two orderings can never silently drift apart"

key-files:
  created:
    - src/test/kotlin/org/example/muvimatchr/voting/MatchAggregationServiceTest.kt
  modified:
    - src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt
    - src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt
    - src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt

key-decisions:
  - "unanimousMovieIds returns an empty list immediately for an empty active-participant collection, before ever calling the repository -- an empty IN (:participantIds) list is rejected by Postgres, and empty-roster-means-empty-match-list is also the correct product answer (D-07)."
  - "perMovieLikeCounts takes no participant-id or active-roster filter at all -- an idle participant's earlier likes still count toward every movie's tally, and the query already returns database-established count-descending/movieId-ascending order so RSLT-03's future ranked list needs no schema change."

patterns-established:
  - "Pattern 4: two aggregate reads (unanimity, like-counts) computed fresh from the vote table on every computeStatus call, mirroring the phase's established live-read-model discipline -- no caching, no sticky flags, both branches of the empty-roster early-return populated identically to the non-empty path"

requirements-completed: [VOTE-04, VOTE-05, RSLT-03]

coverage:
  - id: D1
    description: "A movie is reported as a match only when every currently-active participant liked it -- a movie liked by exactly all-but-one of N active participants is never reported, and a movie liked by exactly all N is reported (N is the boundary, not excluded)"
    requirement: "VOTE-04"
    verification:
      - kind: integration
        ref: "MatchAggregationServiceTest.kt#three participants who all liked every movie in a three-movie deck are complete with all three movies matched"
        status: pass
      - kind: integration
        ref: "MatchAggregationServiceTest.kt#a movie liked by two of three active participants is excluded from matchedMovieIds"
        status: pass
    human_judgment: false
  - id: D2
    description: "\"Has everyone finished?\" is computed live against currently-joined AND active participants -- a late joiner after others finish flips isComplete back to false until they finish too"
    requirement: "VOTE-05"
    verification:
      - kind: integration
        ref: "MatchAggregationServiceTest.kt#isComplete flips from true to false when a late joiner arrives after the others finished, and back to true once they finish"
        status: pass
    human_judgment: false
  - id: D3
    description: "An inactive participant (backdated past the inactivity window, on last-vote or on created_at with zero votes) drops out of both activeCount and the unanimity requirement, with no trace once excluded -- and one new vote re-includes them in both on the very next call"
    requirement: "VOTE-05"
    verification:
      - kind: integration
        ref: "MatchAggregationServiceTest.kt#a participant backdated past the inactivity window is excluded from activeCount and not required for unanimity"
        status: pass
      - kind: integration
        ref: "MatchAggregationServiceTest.kt#an idle participant who casts one new vote is immediately back in activeCount and the unanimity requirement"
        status: pass
      - kind: integration
        ref: "MatchAggregationServiceTest.kt#a zero-vote participant backdated past the window on created_at is excluded, one within the window is included"
        status: pass
      - kind: integration
        ref: "MatchAggregationServiceTest.kt#with every participant idle, activeCount is zero, matchedMovieIds is empty, isComplete is false, and likeCounts still reports every like"
        status: pass
    human_judgment: false
  - id: D4
    description: "Per-movie like counts cover every participant's likes with no roster or unanimity filter -- an idle participant's earlier likes still count, ordered by count descending then movieId ascending, identical across repeated calls, absent for movies nobody liked, empty for a no-vote session"
    requirement: "RSLT-03"
    verification:
      - kind: integration
        ref: "MatchAggregationServiceTest.kt#an idle participant's earlier likes still appear in likeCounts at full count while excluded from activeCount"
        status: pass
      - kind: integration
        ref: "MatchAggregationServiceTest.kt#likeCounts orders by count descending then movieId ascending, and repeated calls are identical"
        status: pass
      - kind: integration
        ref: "MatchAggregationServiceTest.kt#a pinned-deck movie that nobody liked is absent from likeCounts"
        status: pass
      - kind: integration
        ref: "MatchAggregationServiceTest.kt#a session with a pinned deck and no votes reports empty likeCounts and empty matchedMovieIds"
        status: pass
    human_judgment: false
  - id: D5
    description: "matchedMovieIds is deterministically ordered (ascending movieId) and identical across repeated calls with no intervening writes"
    requirement: "VOTE-04"
    verification:
      - kind: integration
        ref: "MatchAggregationServiceTest.kt#two calls to computeStatus with no intervening writes return identical matchedMovieIds"
        status: pass
    human_judgment: false

duration: 30min
completed: 2026-09-05
status: complete
---

# Phase 4 Plan 3: Unanimous Match & Per-Movie Like Counts Summary

**Two new native aggregate reads (`findUnanimousMovieIds` HAVING-equality, `findLikeCountsBySession` unfiltered) folded into `MatchAggregationService.computeStatus`, exposed as `matchedMovieIds`/`likeCounts` on the status response, proven by a 12-test behavioural suite covering the all-but-one boundary, late-joiner flip, and deterministic idle drop-out/re-inclusion.**

## Performance

- **Duration:** ~30 min
- **Started:** 2026-09-05T07:25:00Z (approx.)
- **Completed:** 2026-09-05T07:54:58Z
- **Tasks:** 2
- **Files modified:** 4 (1 created, 3 modified)

## Accomplishments
- `VoteRepository.findUnanimousMovieIds` answers VOTE-04 exactly as the discipline requires: `HAVING COUNT(DISTINCT v.participant_id) = :participantCount` is an equality against the full active count (never a partial-agreement threshold), and the result is `ORDER BY v.movie_id` in the database itself, not re-sequenced in Kotlin afterwards (enforced by a structural grep gate).
- `VoteRepository.findLikeCountsBySession` answers RSLT-03 with a projection deliberately unfiltered by active roster or unanimity — an idle participant's earlier likes still count toward every movie's tally — ordered `COUNT(*) DESC, movie_id ASC` so tied movies never have an unspecified, call-to-call-varying order.
- `MatchAggregationService.computeStatus` folds both projections into `SessionVoteStatus`, including the empty-active-roster early-return branch (now also populating `matchedMovieIds: []` and a real, roster-independent `likeCounts`), and `unanimousMovieIds` short-circuits on an empty id collection before ever issuing a query — Postgres rejects an empty `IN ()` list, and empty-roster-means-empty-match is also the correct product answer.
- `VoteStatusResponse` gains `matchedMovieIds`/`likeCounts` (via a new `MovieLikeCountResponse` DTO), mapped at the controller's single `toResponse()` extension — no new endpoint, no new guard.
- `MatchAggregationServiceTest` (12 tests, `PostgresTestSupport` subclass, in-process fixtures via `sessionService.pinDeck`/`voteRepository.upsertVote`, idleness established by backdating `voted_at`/`created_at` via `jdbcTemplate` rather than sleeping) pins every rule in D-06/D-07 and every clause of VOTE-04/VOTE-05/RSLT-03: all-but-one exclusion, all-N-inclusion, the late-joiner `isComplete` flip and its recovery, idle drop-out on both `voted_at` and zero-vote `created_at`, immediate re-inclusion on the next vote with no trace left behind, idle participants' likes still counted, tie-break ordering stability across repeated calls, and the pinned-no-votes empty-result case.

## Task Commits

1. **Task 1: Unanimous-match and per-movie like-count projections over the live roster** - `3732da4` (feat)
2. **Task 2: Prove the live roster — all-but-one, late joiner, idle drop-out, and the counts that survive it** - `d64ce27` (test)

## Files Created/Modified
- `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt` - `findUnanimousMovieIds`, `findLikeCountsBySession` (both native, named `@Param` bindings)
- `src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt` - `unanimousMovieIds`, `perMovieLikeCounts`, `MovieLikeCount` data class, extended `SessionVoteStatus`
- `src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt` - `MovieLikeCountResponse` data class, extended `VoteStatusResponse`, updated `toResponse()` mapping
- `src/test/kotlin/org/example/muvimatchr/voting/MatchAggregationServiceTest.kt` - 12-test behavioural suite (new file)

## Decisions Made
- `unanimousMovieIds`'s empty-collection early return is required, not defensive — see frontmatter `key-decisions` for the full Postgres-`IN ()`-rejection rationale.
- `perMovieLikeCounts` deliberately carries no roster/unanimity filter — see frontmatter `key-decisions` for the RSLT-03 forward-compatibility rationale.
- Idleness in tests is established by backdating `vote.voted_at`/`participant.created_at` rows via `jdbcTemplate`, never `Thread.sleep`, so the suite exercises the real production-configured 60-second timeout deterministically rather than a shrunken test-only window.

## Deviations from Plan

None — plan executed exactly as written. Both tasks' `<action>` sections were implemented as specified; all `<verify>` automated checks (build, structural greps, test run) passed on the first attempt with no fix cycles required.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- `MatchAggregationService.computeStatus` now returns the complete `SessionVoteStatus` shape (deckSize/activeCount/finishedCount/isComplete/matchedMovieIds/likeCounts) that Plan 04-04's concurrency test and Phase 5's real-time push both depend on — no further shape changes anticipated before Phase 6's ranked-list UI consumes `likeCounts` directly.
- Plan 04-04 (two-simultaneous-final-votes concurrency test) can build directly on `VoteService.recordVote`'s existing `FOR UPDATE` lock and this plan's `matchedMovieIds`/`likeCounts` outputs — no changes needed to reach that plan's starting point.
- No blockers. The `voting.inactivity-timeout-seconds` config key remains shrinkable for tests per D-06's discretion note, though this plan's suite deliberately did not use that discretion (backdating instead, to exercise the real window).

---
*Phase: 04-vote-recording-match-aggregation*
*Completed: 2026-09-05*

## Self-Check: PASSED

All created/modified files confirmed present on disk; both task commit hashes (`3732da4`, `d64ce27`) confirmed present in `git log`.
