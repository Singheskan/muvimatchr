---
phase: 04-vote-recording-match-aggregation
plan: 02
subsystem: api
tags: [kotlin, spring-boot, jackson, jpa]

# Dependency graph
requires:
  - phase: 04-01
    provides: session.genre/pinned_deck/deck_pinned_at columns, SessionService.pinDeck, DeckController's pinning branch
provides:
  - genre promoted from a per-request deck query parameter to session state, validated at the two endpoints that set it (create-session, replace-filters)
  - DeckController.getDeck binding no request parameters at all -- region/providerIds/genre all read from session.*
  - SessionService.replaceFilters returning 409 once deckPinnedAt is set, proven to leave the stored configuration unchanged
affects: [04-03-unanimous-match-and-like-counts, 04-04-concurrency-test]

# Actuals (#2632)
actuals:
  tokens: 8400
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Genre validated where it enters (SessionController.createSession/replaceFilters), never at the read endpoint that consumes it (DeckController) -- mirrors the region/providerIds precedent already established in Phase 3"
    - "Post-pin write lock as a single guard clause: SessionService.replaceFilters checks deckPinnedAt as the first statement after the session load, before any field is touched, so a refusal can never leave a partial write"

key-files:
  created: []
  modified:
    - src/main/kotlin/org/example/muvimatchr/session/SessionController.kt
    - src/main/kotlin/org/example/muvimatchr/session/SessionService.kt
    - src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt
    - src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt
    - src/test/kotlin/org/example/muvimatchr/session/SessionFiltersTest.kt

key-decisions:
  - "CreateSessionRequest.providerIds and SessionFiltersRequest.providerIds changed from `List<Int> = emptyList()` to `List<Int>? = null` -- a Jackson-Kotlin-module defaults-constructor limitation rejected `{\"genre\": 28}` (providerIds omitted, genre supplied) with a 400, even though the plan's own required behavior specifies this exact sparse body must return 201. A nullable type with every call site normalising via `?: emptyList()` sidesteps the fragile synthetic defaults-constructor path Jackson otherwise invokes for a non-nullable parameter with a Kotlin default."
  - "The D-03 invariant test (conflicting genre query parameter is inert) uses a single session and a single deck GET, not a two-session comparison -- a second session sharing the same (genre, providerIds, region) combination would silently hit the first session's deck-cache row and consume none of its own enqueued MockWebServer fixtures, corrupting every later test's response-queue ordering for the rest of the class. The one-call assertion (outbound `with_genres` matches the session's stored value, not the query parameter) is sufficient proof and avoids the cache collision entirely."

patterns-established:
  - "Pattern: sparse-JSON-body DTOs should prefer nullable constructor parameters over `= emptyList()`-style Kotlin defaults, normalising to the empty collection at the call site instead -- see Deviations for the concrete failure this avoids."

requirements-completed: [VOTE-01, VOTE-04]

coverage:
  - id: D1
    description: "Genre is stored on the session row and can be set at session creation and replaced through the filters endpoint by any participant, exactly like region and providerIds"
    requirement: "VOTE-04"
    verification:
      - kind: integration
        ref: "DeckControllerTest.kt#GET deck for a session created with a genre returns real upstream movie data translated into the app's own DTOs"
        status: pass
      - kind: integration
        ref: "SessionFiltersTest.kt#filters are freely replaceable by any participant before the deck is pinned and refused together with a 409 once it is, leaving the stored configuration unchanged"
        status: pass
    human_judgment: false
  - id: D2
    description: "The deck endpoint derives genre only from the session row -- a genre supplied as a request query parameter changes neither the outbound catalog query nor the returned deck"
    requirement: "VOTE-04"
    verification:
      - kind: integration
        ref: "DeckControllerTest.kt#a genre query parameter on the deck GET is inert -- the outbound discover request comes only from the session's stored genre"
        status: pass
    human_judgment: false
  - id: D3
    description: "An unknown genre id is rejected with 400 at the endpoint that sets it, before it is written to the session row or reaches an outbound catalog URL"
    requirement: "VOTE-04"
    verification:
      - kind: integration
        ref: "SessionFiltersTest.kt#creating a session with an unknown genre id returns 400 and writes no session row"
        status: pass
    human_judgment: false
  - id: D4
    description: "Region, streaming providers and genre are all freely replaceable while deck_pinned_at is null"
    requirement: "VOTE-04"
    verification:
      - kind: integration
        ref: "SessionFiltersTest.kt#filters are freely replaceable by any participant before the deck is pinned and refused together with a 409 once it is, leaving the stored configuration unchanged"
        status: pass
    human_judgment: false
  - id: D5
    description: "Once deck_pinned_at is non-null, a filters replacement is refused with 409 and the session's region, providerIds and genre are all left exactly as they were"
    requirement: "VOTE-01"
    verification:
      - kind: integration
        ref: "SessionFiltersTest.kt#filters are freely replaceable by any participant before the deck is pinned and refused together with a 409 once it is, leaving the stored configuration unchanged"
        status: pass
    human_judgment: false
  - id: D6
    description: "Two participants of the same session always resolve the same genre, because there is no per-client value for it to differ on"
    requirement: "VOTE-04"
    verification:
      - kind: integration
        ref: "DeckControllerTest.kt#a genre query parameter on the deck GET is inert -- the outbound discover request comes only from the session's stored genre"
        status: pass
      - kind: integration
        ref: "DeckControllerTest.kt#two different participants of the same session receive identical deck filtering"
        status: pass
    human_judgment: false

duration: 42min
completed: 2026-09-05
status: complete
---

# Phase 4 Plan 2: Genre Filter Locking Summary

**Genre promoted from a per-request deck query parameter to session state, validated at creation/replace-filters, with a 409 write-lock on all three filters once the deck is pinned.**

## Performance

- **Duration:** ~42 min
- **Started:** 2026-09-05T07:05:00Z (approx.)
- **Completed:** 2026-09-05T07:47:00Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments
- `genre` is now a field on all four session DTOs (`CreateSessionRequest/Response`, `SessionFiltersRequest/Response`), validated via `requireKnownGenre` at the two endpoints that set it -- `SessionController.createSession` and `replaceFilters` -- in the same position region/providerIds are already validated, mirroring the existing Phase 3 precedent.
- `DeckController.getDeck` now takes exactly two parameters (the path variable and the current participant): the genre query-parameter binding is gone, and `session.genre` is read alongside `session.region`/`providerIds` for both the upstream catalog call and `pinDeck`. A deck request can no longer be pointed at a different genre than the one the group agreed on.
- `SessionService.replaceFilters` refuses with `409 CONFLICT` the instant `deckPinnedAt` is non-null, checked as the very first statement after the session loads -- before any field assignment -- so a post-pin request can never partially apply.
- Rebased `DeckControllerTest`'s genre-parameter tests onto session-creation-time genre (the genre-list fixture is now consumed by `createSession`, not the deck GET); relocated the unknown-genre-id rejection test to `SessionFiltersTest` (it now belongs at the endpoint that validates it); added the D-03 invariant test proving a conflicting `genre` query parameter changes neither the outbound TMDB request nor the returned deck.
- Added `SessionFiltersTest` coverage for both sides of the D-02 pin boundary: pre-pin replacement of all three filters by a non-pinning participant, post-pin 409 with the stored row provably unchanged, and `GET .../filters` still succeeding after pinning (reads are never blocked).

## Task Commits

1. **Task 1: Genre becomes session state, and the deck endpoint stops reading it from the request** - `afe45c4` (feat)
2. **Task 2: Filters become read-only the moment the deck is pinned** - `0aaee8c` (test)

## Files Created/Modified
- `src/main/kotlin/org/example/muvimatchr/session/SessionController.kt` - `genre` on all four DTOs; `requireKnownGenre` called before `SessionService` in both `createSession` and `replaceFilters`; `providerIds` changed to nullable to fix a Jackson-Kotlin sparse-body bug (see Deviations)
- `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt` - `createSession`/`replaceFilters` gain a `genreId` parameter; `replaceFilters` refuses with `CONFLICT` when `deckPinnedAt` is already set
- `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt` - genre query-parameter binding and `CatalogReferenceService` dependency removed; reads `session.genre`
- `src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt` - rebased genre-parameter tests onto session-creation time; added the D-03 inertness invariant test; unknown-genre test moved out
- `src/test/kotlin/org/example/muvimatchr/session/SessionFiltersTest.kt` - added a `pinDeck` test helper and coverage for the pre/post-pin filter-lock boundary; added the relocated unknown-genre-id rejection test

## Decisions Made
- See frontmatter `key-decisions` for the two decisions made during execution (the `providerIds` nullability fix and the single-session invariant-test design). Both are also covered under Deviations below.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `providerIds` non-nullable Kotlin default broke the plan's own required `{"genre": 28}` request shape**
- **Found during:** Task 1, first `DeckControllerTest` run against the reworked genre tests
- **Issue:** `CreateSessionRequest`'s `providerIds: List<Int> = emptyList()` is a non-nullable type with a Kotlin-generated default. Jackson's Kotlin module invokes a synthetic "defaults-aware" constructor to compute that default when a JSON body omits the property -- and that path is unreliable once the body also supplies a value for a *later* constructor parameter (`genre`) while omitting this one. `POST /api/sessions` with body `{"genre": 28}` (providerIds omitted, genre supplied) failed with `HttpMessageNotReadableException: Parameter specified as non-null is null: ... parameter providerIds`, surfacing as a 400 -- even though the plan's own `<behavior>` block requires this exact body to return 201. No existing test had ever exercised this specific sparse pattern (every prior test either supplied `region`+`providerIds` together, or neither).
- **Fix:** Changed `providerIds` to `List<Int>? = null` in both `CreateSessionRequest` and `SessionFiltersRequest`, normalising to `emptyList()` at each call site (`SessionController.createSession` already did this via `request?.providerIds ?: emptyList()`; `replaceFilters` needed the same elvis-operator normalisation added). A nullable type never invokes Jackson's synthetic defaults-constructor path -- Jackson binds a bare `null` directly regardless of which other properties are present or absent.
- **Files modified:** `src/main/kotlin/org/example/muvimatchr/session/SessionController.kt`
- **Verification:** `DeckControllerTest`'s two genre-at-creation tests and `SessionFiltersTest`'s unknown-genre-id test (all of which POST a body naming only `genre`) pass; full suite green.
- **Committed in:** `afe45c4` (Task 1 commit)

**2. [Rule 1 - Bug] The D-03 invariant test's original two-session design corrupted the shared MockWebServer response queue**
- **Found during:** Task 1, second `DeckControllerTest` full-class run
- **Issue:** The first draft of the inertness invariant test created two sessions storing the identical genre with no other differentiating filter, then asserted both independently fetched from TMDB. Because `MovieCatalogService`'s deck cache is keyed by filter combination (not session), the second session's deck GET silently hit the first session's already-warm cache row and made zero upstream calls -- leaving its own six enqueued MockWebServer fixtures unconsumed. Those fixtures then desynced every subsequent test's request/response pairing for the rest of the class (`TmdbMockServerSupport` only drains the recorded-*request* queue between tests, never the enqueued-*response* queue), producing a wall of unrelated `MismatchedInputException`/wrong-status failures.
- **Fix:** Redesigned the test around a single session and a single deck GET: one request carrying a conflicting `genre` query parameter, asserting the outbound discover call's `with_genres` value comes from the session row (28), not the parameter (18). This is sufficient to satisfy the plan's acceptance criterion and avoids the cache-collision hazard entirely.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt`
- **Verification:** `./gradlew test --tests "*.catalog.DeckControllerTest"` -- 19/19 pass, deterministically across repeated runs.
- **Committed in:** `afe45c4` (Task 1 commit)

**3. [Rule 1 - Bug] Two rebased tests didn't drain the genre-list recorded request created by session creation**
- **Found during:** Task 1, same full-class run as #2
- **Issue:** Once genre validation moved to session-creation time, `createSession(genre:...)` now itself triggers a genre-list TMDB call. Two rebased tests took the *next* recorded request expecting it to be the discover call, but it was actually the still-undrained genre-list request from creation, failing the `with_genres=` assertion.
- **Fix:** Added an explicit `takeRecordedRequest()` immediately after `createSession(...)` in both tests to drain the genre-list request first, matching the existing idiom already used elsewhere in the file for the analogous provider-validation request.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt`
- **Verification:** Same full-class run as #2, now green.
- **Committed in:** `afe45c4` (Task 1 commit)

---

**Total deviations:** 3 auto-fixed (all Rule 1 - bugs surfaced by running the tests the plan itself specified, none of which existed in the codebase before this plan's own changes)
**Impact on plan:** All three were necessary for the plan's own stated verification (`./gradlew test` passing with zero failures, and the plan's own `{"genre": 28}` behavior requirement) to actually hold. No scope creep -- no new endpoints, tables, or product behavior beyond what the plan specified.

## Issues Encountered
None beyond the three deviations documented above.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Genre, region and providerIds are now a single, session-level, lock-at-pin filter set with no remaining per-request divergence path -- Plan 04-03 (unanimous match + like counts) and 04-04 (concurrency test) can build directly on `SessionService.pinnedMovies`/`pinnedMovieIds` and the now-fully-locked filter set without any further changes to the filter-resolution path.
- `SessionService.replaceFilters`'s CONFLICT guard is a single early-return check on `deckPinnedAt`, easily extended if a future phase needs to distinguish *why* filters are locked (it currently doesn't need to).
- No blockers. The same `JAVA_HOME`/`DOCKER_HOST`/`TESTCONTAINERS_RYUK_DISABLED` environment quirks logged in STATE.md from Phase 4 Plan 1 still apply on this machine.

---
*Phase: 04-vote-recording-match-aggregation*
*Completed: 2026-09-05*

## Self-Check: PASSED

All modified/created files confirmed present on disk; both task commit hashes (`afe45c4`, `0aaee8c`) confirmed present in `git log`.
