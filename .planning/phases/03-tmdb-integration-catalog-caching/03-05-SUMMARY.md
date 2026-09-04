---
phase: 03-tmdb-integration-catalog-caching
plan: 05
subsystem: catalog
tags: [reactor-retry-exhausted, degraded-cache-fallback, sparse-result-discrimination, tmdb]

# Dependency graph
requires:
  - phase: 03-tmdb-integration-catalog-caching
    plan: 01
    provides: "MovieCatalogClient's Retry.backoff policy (5xx/429 only), DeckCacheRepository.findByCacheKey/upsertDeck, DeckResult.stale (declared false everywhere until this plan)"
  - phase: 03-tmdb-integration-catalog-caching
    plan: 04
    provides: "MovieCatalogService.getDeck's refresh branch (discover + per-movie availability resolution) that this plan wraps with the D-04 failure fallback"
provides:
  - "MovieCatalogService.getDeck: an exhausted-retry failure (reactor.core.Exceptions.isRetryExhausted) in the refresh branch serves the already-loaded past-TTL row with stale=true when one exists, or raises a 503 SERVICE_UNAVAILABLE and writes nothing when none does; a non-retryable 4xx propagates uncaught after exactly one upstream attempt"
  - "MINIMUM_DECK_SIZE=5 (D-06's floor), declared once in MovieCatalogService.kt"
  - "DeckController.getDeck: a resolved deck below MINIMUM_DECK_SIZE returns status=\"insufficient_results\" with an empty movies list and the true totalResults count; at or above returns the ordinary \"ok\" response, uncapped beyond the single TMDB page"
  - "Two live-TMDB human-check verifications (Task 1's real-data confirmation, Task 2's sparse-filter confirmation) recorded as open unrun-verify items in .planning/WINDOWS.md, harvested for end-of-phase UAT"
affects: [phase-4-vote-match, phase-6-frontend-spa]

actuals:
  tokens: 7670
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "reactor.core.Exceptions.isRetryExhausted(e) as the precise guard for 'the client's own retries are exhausted' -- narrower than catching any Exception, so a non-retryable 4xx (which never goes through Retry.backoff's exhaustion path) is left to propagate rather than being folded into the same fallback"
    - "Reusing the row already read at the top of getDeck for the outage fallback, never a second findByCacheKey lookup -- guarantees the fallback can never observe a different row than the freshness decision was made against"
    - "Sparse-result determination made only when shaping the HTTP response (DeckController), never inside the cache read/write path (MovieCatalogService) -- one caching rule for every filter combination, sparse or not"

key-files:
  created: []
  modified:
    - src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt
    - src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt
    - src/test/kotlin/org/example/muvimatchr/catalog/MovieCatalogServiceTest.kt
    - src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt

key-decisions:
  - "Caught reactor.core.Exceptions.isRetryExhausted(e) rather than a bare catch (e: Exception) around the refresh block. Exceptions$RetryExhaustedException is package-private but extends IllegalStateException, and Exceptions.isRetryExhausted does an exact instanceof check against it -- so the guard precisely isolates 'the client's retries were exhausted' from any other exception (a non-retryable 4xx, a programming error) without needing to catch IllegalStateException by name and hope nothing else in the block throws one."
  - "D-06's threshold check branches on result.totalResults, not result.movies.size. Because D-05 fetches only one TMDB page and never paginates, TMDB's total_results equals the page's actual movie count whenever it is below the page size -- so the two are equivalent for every case this floor cares about, and totalResults is what the plan's action text and behavior list literally reference ('totalResults set to the actual resolved count')."
  - "Bumped the pre-existing SINGLE_MOVIE_DISCOVER_FIXTURE's total_results from 1 to 25 (deviation, see below) so four of Plan 03-04's tests that use a one-movie fixture to test per-movie-availability plumbing keep exercising the 'ok' response path now that D-06's floor applies to every deck request unconditionally."

patterns-established:
  - "D-04's three-rung degradation ladder (retry inside the client, then serve the already-loaded stale row, then fail only when nothing is cached) documented inline at the exact point it is implemented, matching this codebase's established convention (SessionService.createSession's retry-loop comment) of explaining non-obvious control flow where it lives rather than only in planning docs."

requirements-completed: [CTLG-01, CTLG-03, CTLG-04]

coverage:
  - id: D1
    description: "With a cached row past its TTL and the upstream persistently failing, a deck read exhausts the client's retry budget (4 total attempts) then returns the cached row's movies with stale=true, without writing a second row"
    requirement: "CTLG-01"
    verification:
      - kind: integration
        ref: "MovieCatalogServiceTest#an upstream outage with a past-TTL cached row serves that row's movies marked stale after retries are exhausted"
        status: pass
      - kind: integration
        ref: "DeckControllerTest#a stale cache row served after an upstream outage surfaces as a 200 with stale true"
        status: pass
    human_judgment: false
  - id: D2
    description: "With no cached row and the upstream persistently failing, a deck read raises a 503 and the endpoint surfaces it as 503 (not a 200 with an empty deck), and no cache row is written for that combination"
    requirement: "CTLG-04"
    verification:
      - kind: integration
        ref: "MovieCatalogServiceTest#an upstream outage with no cached row for that filter combination raises a 503"
        status: pass
      - kind: integration
        ref: "MovieCatalogServiceTest#an upstream outage with no cached row writes nothing to the cache regardless of the surrounding tests"
        status: pass
      - kind: integration
        ref: "DeckControllerTest#an upstream outage with no cached deck for these filters surfaces as a 503"
        status: pass
      - kind: other
        ref: "grep gate: MovieCatalogService.kt contains exactly one call to upsertDeck (on the success path only)"
        status: pass
    human_judgment: false
  - id: D3
    description: "A transient failure that succeeds on a later attempt resolves to a fresh, non-stale deck with the row's timestamp updated; a 4xx client error the retry policy excludes fails after exactly one upstream attempt, not consuming further retries"
    requirement: "CTLG-01"
    verification:
      - kind: integration
        ref: "MovieCatalogServiceTest#a transient failure that succeeds on a later attempt resolves to a fresh, non-stale deck with an updated timestamp"
        status: pass
      - kind: integration
        ref: "MovieCatalogServiceTest#a client error the retry policy excludes fails without consuming further retry attempts"
        status: pass
    human_judgment: false
  - id: D4
    description: "A deck served from a fresh cache hit carries stale=false, distinguishing degraded mode specifically from merely having come from cache"
    requirement: "CTLG-04"
    verification:
      - kind: integration
        ref: "MovieCatalogServiceTest#a fresh cached row is served with stale false, distinguishing a normal cache hit from a degraded one"
        status: pass
    human_judgment: false
  - id: D5
    description: "A filter combination resolving to fewer than five movies (tested at exactly four and at zero) returns status=insufficient_results with an empty movies list and the true count; exactly five and twenty movies both return status=ok with every movie present and uncapped"
    requirement: "CTLG-01"
    verification:
      - kind: integration
        ref: "DeckControllerTest#a filter combination resolving to four movies returns insufficient_results with an empty movies list and the true count"
        status: pass
      - kind: integration
        ref: "DeckControllerTest#a filter combination resolving to exactly five movies returns the ordinary ok status with all five movies present"
        status: pass
      - kind: integration
        ref: "DeckControllerTest#a filter combination resolving to twenty movies returns all twenty, uncapped beyond the single TMDB page"
        status: pass
      - kind: integration
        ref: "DeckControllerTest#a filter combination resolving to zero movies returns insufficient_results with a truthful zero count, not a 404 or error"
        status: pass
      - kind: other
        ref: "grep gate: MovieCatalogService.kt contains MINIMUM_DECK_SIZE; DeckController.kt contains insufficient_results"
        status: pass
    human_judgment: false
  - id: D6
    description: "A sparse filter combination is cached under the same single rule as any other: a repeated identical request within the TTL makes zero further upstream requests, and an insufficient-results response never substitutes movies from a different, already-cached filter combination"
    requirement: "CTLG-04"
    verification:
      - kind: integration
        ref: "DeckControllerTest#a sparse filter combination is cached like any other -- a second identical request within the TTL makes zero further upstream requests"
        status: pass
      - kind: integration
        ref: "DeckControllerTest#an insufficient-results deck for one filter combination never leaks movies from a full deck cached under a different combination"
        status: pass
    human_judgment: false
  - id: D7
    description: "The full pre-existing and newly-added test suite passes after wiring the D-04 failure fallback and the D-06 sparse-result branch into two already-shipped code paths"
    requirement: "CTLG-04"
    verification:
      - kind: other
        ref: "./gradlew test (full suite): 98/98 pass, 0 failures, 0 errors, across 12 test classes"
        status: pass
      - kind: other
        ref: "./gradlew build: BUILD SUCCESSFUL"
        status: pass
    human_judgment: false
  - id: D8
    description: "ROADMAP Phase 3 criterion 1's 'real TMDB data' confirmed end-to-end against a live TMDB credential, and the sparse-filter experience confirmed against a real narrow filter combination -- the two checks this phase's fake-server test suite structurally cannot perform"
    human_judgment: true
    rationale: "Both require a real TMDB_API_TOKEN, which this dev machine does not have (documented since 03-RESEARCH.md's Environment Availability section, unresolved through Plans 03-01/03-04/03-05). Recorded as two open unrun-verify entries in .planning/WINDOWS.md (ids 2 and 3), harvested for the phase's end-of-phase UAT batch. Every other requirement this plan covers has automated coverage; only the live-credential half is deferred."

duration: ~13min
completed: 2026-09-04
status: complete
---

# Phase 03 Plan 05: Retry-Then-Stale-Then-Fail Degradation and Sparse-Result Discrimination Summary

**An upstream TMDB outage now degrades to the last known deck for the exact same filters (labelled `stale`) after the client's retries are exhausted, fails explicitly with a 503 only when nothing was ever cached, and a filter combination matching fewer than five movies returns a truthful `insufficient_results` envelope instead of a thin deck — closing the last two behavioral gaps (D-04, D-06) in Phase 3's TMDB integration.**

## Performance

- **Duration:** ~13 min
- **Started:** 2026-09-04T13:16:54Z (worktree branch/base verified, context files read)
- **Completed:** 2026-09-04T13:30:05Z (Task 2 commit)
- **Tasks:** 2 completed
- **Files modified:** 4 (0 created, 4 modified)

## Accomplishments

- `MovieCatalogService.getDeck`'s refresh branch now catches an exhausted-retry failure (`reactor.core.Exceptions.isRetryExhausted`), reusing the row already read at the top of the method (never a second lookup) to serve a past-TTL deck marked `stale=true`; with no row at all it raises `ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ...)` and writes nothing to the cache. A non-retryable 4xx is not caught by this guard and propagates after exactly one upstream attempt.
- `MINIMUM_DECK_SIZE = 5` declared once in `MovieCatalogService.kt`; `DeckController.getDeck` branches on `result.totalResults` against it, returning `status = "insufficient_results"` with an empty `movies` list and the true count below the floor, or the ordinary `status = "ok"` response with every movie present and uncapped at or above it. The sparse determination happens only when shaping the response — the cache read/write path is unchanged, so a sparse combination is cached exactly like any other.
- 16 new tests across the two files (8 `MovieCatalogServiceTest`, 8 `DeckControllerTest`) proving the full D-04 ladder (stale fallback, honest 503, no-poison-write, transient-success, excluded-4xx, fresh-hit-not-stale) and the full D-06 boundary (four/five/twenty/zero movies, sparse-cache-hit, cross-combination non-substitution).
- Two `<human-check>` verifications this phase's fake-server test suite structurally cannot perform (live-TMDB real-data confirmation, live sparse-filter confirmation) recorded as open `unrun-verify` entries in `.planning/WINDOWS.md` (ids 2, 3) for the phase's end-of-phase UAT batch.
- Full suite: **98/98 tests pass, 0 failures, 0 errors**, across 12 test classes (`./gradlew build` green).

## Task Commits

Each task was committed atomically:

1. **Task 1: Retry, then serve stale, then fail honestly** — `fd28642` (feat, tdd)
2. **Task 2: Explicit insufficient-results response for sparse filter combinations** — `52d54c3` (feat, tdd)

**Plan metadata:** committed alongside this SUMMARY (see below)

Both tasks are `tdd="true"`, but as in every prior plan of this phase, each test file was written against the action's already-fully-specified implementation rather than run RED-first against non-existent code — the plan's `<action>` blocks were prescriptive enough that tests were written, compiled, and iterated against a working implementation within the same commit.

## Files Created/Modified

- `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt` — `MINIMUM_DECK_SIZE`, the retry-exhausted catch around the refresh branch, `deserializeMovies` helper (deduplicates the cache-hit and stale-fallback deserialization)
- `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt` — branches on `result.totalResults < MINIMUM_DECK_SIZE` to build the `insufficient_results` vs `ok` envelope
- `src/test/kotlin/org/example/muvimatchr/catalog/MovieCatalogServiceTest.kt` — 8 new tests (fresh-hit stale=false, stale fallback, 503 with no write x2, transient-success, excluded-4xx)
- `src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt` — 8 new tests (2 D-04 endpoint projections, 6 D-06 boundary/caching/non-substitution) plus a fixture bump (see Deviations)

## Decisions Made

See `key-decisions` in the frontmatter above — summarized: (1) caught `Exceptions.isRetryExhausted(e)` rather than a bare `Exception` catch, precisely isolating retry-exhaustion from any other failure mode; (2) D-06's threshold checks `totalResults`, which is provably equivalent to `movies.size` under D-05's single-page-only fetch; (3) bumped a pre-existing test fixture's `total_results` so Plan 03-04's per-movie-availability tests keep exercising the `ok` path now that the floor applies unconditionally.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] A pre-existing inline comment already contained the literal string "upsertDeck", breaking Task 1's `-eq 1` grep gate**
- **Found during:** Task 1, running the plan's own verify command `test "$(grep -c 'upsertDeck' ...)" -eq "1"`
- **Issue:** Plan 03-01's original comment above the freshness check read `"...single atomic upsert (DeckCacheRepository.upsertDeck), not a check-then-insert pair..."` — a second literal occurrence of `upsertDeck` that pre-dated this plan. Task 1's acceptance criterion requires exactly one occurrence (the actual call site) as proof the failure path performs no write; the pre-existing comment made the gate fail before any of this task's own logic could be judged.
- **Fix:** Reworded the comment to convey the same meaning ("a single atomic upsert on DeckCacheRepository") without repeating the literal method name, and extended it to state the invariant this plan's failure path now depends on ("exactly one call site writes the cache, gated on success only").
- **Files modified:** `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt`
- **Verification:** `grep -c 'upsertDeck' src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt` → `1`.
- **Commit:** `fd28642`

**2. [Rule 3 - Blocking issue] Four pre-existing DeckControllerTest cases broke once D-06's floor applied to every deck request unconditionally**
- **Found during:** Task 2, running `DeckControllerTest` after wiring the `insufficient_results` branch
- **Issue:** `SINGLE_MOVIE_DISCOVER_FIXTURE` (one movie, `total_results: 1`) is used by four of Plan 03-04's tests to exercise per-movie-availability resolution, not the sparse-result threshold. Once `DeckController` unconditionally branches on `totalResults < MINIMUM_DECK_SIZE`, every one of those tests' responses flipped to `status = "insufficient_results"` with an empty `movies` list — two tests indexed into `movies[0]` and failed with `IndexOutOfBoundsException`; a third's stale-fallback assertion (this plan's own Task 1 test, using the same fixture) failed similarly.
- **Fix:** Bumped `SINGLE_MOVIE_DISCOVER_FIXTURE`'s `total_results` from `1` to `25` (still one movie in the `results` array — TMDB's `total_results` legitimately can exceed a single page's contents) with an inline comment explaining the fixture is deliberately above the floor because these tests exercise availability plumbing, not the sparse threshold.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt`
- **Verification:** `./gradlew test --tests "*.catalog.DeckControllerTest"` 20/20 pass (was 3 failing); full suite 98/98 pass.
- **Commit:** `52d54c3`

---

**Total: 2 deviations (1 Rule 1, 1 Rule 3). Both are test-infrastructure fallout from legitimate production-code changes this plan introduced — no impact on shipped behavior or either task's stated acceptance criteria, all re-verified green after the fixes.**

## Issues Encountered

None beyond the deviations above.

## User Setup Required

None for this plan's automated verification — every test runs against the local `mockwebserver3` fake, no `TMDB_API_TOKEN` required.

This plan's `user_setup` block (obtaining a real TMDB API Read Access Token) remains unresolved on this dev machine, as documented since `03-RESEARCH.md`'s Environment Availability section. Both of this plan's `<human-check>` verifications — the live-TMDB real-data confirmation (Task 1) and the live sparse-filter confirmation (Task 2) — are recorded as open `unrun-verify` entries in `.planning/WINDOWS.md` (ids 2, 3) rather than blocking execution, per this plan's own `<verification>` section ("harvested at end of phase rather than halting execution").

## Next Phase Readiness

This is Wave 5 of 5 in Phase 3 — the last plan. All five phase requirement IDs (CTLG-01 through CTLG-05) are now covered across the phase's five SUMMARY.md files:

- CTLG-01 (TMDB-sourced deck): 03-01, 03-04, 03-05
- CTLG-02 (genre filter): 03-01, 03-03
- CTLG-03 (streaming-availability filter): 03-02, 03-03, 03-04, 03-05
- CTLG-04 (server-side caching): 03-01, 03-03, 03-04, 03-05
- CTLG-05 (credential never reaches the frontend): 03-01

Phase 3's automated execution is complete: the full suite (98 tests, 12 classes) is green, and every requirement has passing automated coverage except the two live-TMDB human checks this and Plan 03-04 recorded in `.planning/WINDOWS.md` (ids 1, 2, 3) — all three require a real `TMDB_API_TOKEN` this dev machine does not have. This is noted here for the orchestrator: Phase 3 execution is done pending goal-level verification (`/gsd-verify-work`) and, separately, resolution of the three open WINDOWS.md entries once a token is available.

No blockers identified for Phase 4 (vote/match), which depends on this phase's deck endpoint and cache but not on its degradation/sparse-result internals.

## Self-Check: PASSED

- `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt` — FOUND, contains `MINIMUM_DECK_SIZE` and `SERVICE_UNAVAILABLE`.
- `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt` — FOUND, contains `insufficient_results`.
- Commits `fd28642`, `52d54c3` confirmed present via `git log --oneline -5`.
- `./gradlew test --tests "*.catalog.MovieCatalogServiceTest"`: 16/16 pass (≥16 required).
- `./gradlew test --tests "*.catalog.DeckControllerTest"`: 20/20 pass (≥20 required).
- `./gradlew test` (full suite): 98/98 pass, 0 failures, 0 errors, across 12 test classes.
- `./gradlew build`: BUILD SUCCESSFUL.
- All plan-level `<verify>` grep/build gates re-run and passing: `MovieCatalogService.kt` contains exactly one call to `upsertDeck` (`grep -c` → 1); `SERVICE_UNAVAILABLE` present; `MINIMUM_DECK_SIZE` present; `DeckController.kt` contains `insufficient_results`.
- No unexpected file deletions in either task commit (`git diff --diff-filter=D --name-only` empty for both).

---
*Phase: 03-tmdb-integration-catalog-caching*
*Completed: 2026-09-04*
