---
phase: 04-vote-recording-match-aggregation
plan: 01
subsystem: api
tags: [kotlin, spring-boot, jpa, postgres, native-query, jackson]

# Dependency graph
requires:
  - phase: 03-tmdb-catalog
    provides: MovieCatalogService.getDeck, DeckController, CachedMovie/CachedProvider shapes, DeckCacheEntry JSONB pattern
  - phase: 01-persistence
    provides: Vote entity, VoteRepository.upsertVote (native ON CONFLICT DO UPDATE), unique constraint on (session_id, participant_id, movie_id)
  - phase: 02-session-lobby
    provides: Session/Participant entities, @CurrentParticipant bearer-token resolution, no-host-role convention
provides:
  - Session-level deck pinning (session.genre/pinned_deck/deck_pinned_at, V8 migration)
  - SessionRepository.lockForUpdate (native FOR UPDATE row lock)
  - SessionService.pinDeck/pinnedMovies/pinnedMovieIds
  - VoteRepository.findActiveParticipantIds/countFinishedParticipants (live, non-sticky activity roster)
  - VoteService.recordVote (single @Transactional lock -> upsert -> aggregate-read boundary)
  - MatchAggregationService.computeStatus (pure read-model, no in-JVM state)
  - VoteController (POST .../votes, GET .../votes/status)
affects: [04-02-genre-filter-locking, 04-03-unanimous-match-and-like-counts, 04-04-concurrency-test]

# Actuals (#2632)
actuals:
  tokens: 12960
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Session-scoped pessimistic lock: native `SELECT id FROM session WHERE id = :id FOR UPDATE` written directly into SQL text (Spring Data's @Lock is silently ignored on native queries) as the first statement inside a single @Transactional service method"
    - "Live read-model, no cached progress state: MatchAggregationService recomputes activeCount/finishedCount/isComplete from the database on every call, never storing a sticky flag"
    - "Deck pinning as a branch on an existing GET: DeckController.getDeck checks deckPinnedAt first and either serves the frozen JSONB snapshot or falls through to the existing fetch-then-pin path — no new 'start voting' endpoint"
    - "Kotlin Boolean DTO fields named isXxx need @get:JsonProperty(\"isXxx\") — Jackson's bean introspection otherwise strips the is- prefix and serializes the field as xxx"

key-files:
  created:
    - src/main/resources/db/migration/V8__add_session_deck_pin_and_genre.sql
    - src/main/kotlin/org/example/muvimatchr/voting/VoteService.kt
    - src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt
    - src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt
    - src/test/kotlin/org/example/muvimatchr/voting/VoteControllerTest.kt
  modified:
    - src/main/kotlin/org/example/muvimatchr/session/Session.kt
    - src/main/kotlin/org/example/muvimatchr/session/SessionRepository.kt
    - src/main/kotlin/org/example/muvimatchr/session/SessionService.kt
    - src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt
    - src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt
    - src/main/resources/application.properties
    - src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt

key-decisions:
  - "Pinned-branch totalResults intentionally equals pinnedMovies.size, not the raw upstream TMDB total captured at fetch time -- the pinned deck's true candidate count is what participants actually vote against; the fresh-fetch totalResults (which can exceed the fetched page's movie count) is a different, deliberately-retained metric used only for the MINIMUM_DECK_SIZE threshold decision."
  - "Deck caching (MovieCatalogService) is keyed by filter combination, not session -- discovered mid-Task-2 that a second createSessionWithPinnedDeck call within one test (or across tests) could silently hit an earlier call's cached row and leave its own enqueued MockWebServer fixtures unconsumed, leaking into a later test. Fixed by clearing deckCacheRepository immediately before every pin in the test helper."

patterns-established:
  - "Pattern 1: session-scoped SELECT ... FOR UPDATE + single enclosing @Transactional for the vote-record-then-read sequence"
  - "Pattern 2: live active-roster query (COALESCE(last_voted_at, created_at) > now() - timeout) shared by both the completion and (future) unanimity checks"
  - "Pattern 3: lazy deck pinning as a branch inside the existing deck-read endpoint"

requirements-completed: [VOTE-01, VOTE-02, VOTE-03]

coverage:
  - id: D1
    description: "A session's deck is pinned exactly once, on the first successful deck read, and every later read returns the identical snapshot with zero further upstream catalog calls"
    requirement: "VOTE-01"
    verification:
      - kind: integration
        ref: "VoteControllerTest.kt#a second deck GET for a pinned session returns the identical movie list and makes zero further upstream requests"
        status: pass
      - kind: integration
        ref: "DeckControllerTest.kt#a filter combination resolving to exactly five movies returns the ordinary ok status with all five movies present (deck_pinned_at non-null assertion)"
        status: pass
    human_judgment: false
  - id: D2
    description: "A vote submitted over HTTP is persisted immediately and readable the instant the call returns; a repeat submission for the same participant+movie updates the one row instead of duplicating it"
    requirement: "VOTE-02, VOTE-03"
    verification:
      - kind: integration
        ref: "VoteControllerTest.kt#POST a like for a pinned movie persists exactly one vote row and returns a live status"
        status: pass
      - kind: integration
        ref: "VoteControllerTest.kt#a repeat POST with a different choice updates the existing vote row in place instead of duplicating"
        status: pass
      - kind: integration
        ref: "VoteControllerTest.kt#a repeat POST with the identical choice leaves exactly one vote row"
        status: pass
    human_judgment: false
  - id: D3
    description: "Off-deck movies, other sessions' tokens, and unpinned sessions are all refused before any vote row is written"
    verification:
      - kind: integration
        ref: "VoteControllerTest.kt#POST an off-deck movieId is rejected with 400 and writes no vote row"
        status: pass
      - kind: integration
        ref: "VoteControllerTest.kt#POST with a bearer token issued for a different session is rejected with 404 and writes no vote row in either session"
        status: pass
      - kind: integration
        ref: "VoteControllerTest.kt#POST against a session whose deck has never been pinned is rejected with 409 and writes no vote row"
        status: pass
    human_judgment: false
  - id: D4
    description: "GET .../votes/status reports deckSize/activeCount/finishedCount/isComplete recomputed from the database on every call, correctly reporting not-complete for a pinned-no-votes session and a never-pinned session"
    verification:
      - kind: integration
        ref: "VoteControllerTest.kt#votes status for a pinned session with no votes reports zero finished and not complete"
        status: pass
      - kind: integration
        ref: "VoteControllerTest.kt#votes status for a session whose deck was never pinned reports zero deck size and not complete"
        status: pass
    human_judgment: false
  - id: D5
    description: "A deck resolving below the catalog minimum leaves deck_pinned_at null, and a vote against that session is rejected with 409"
    verification:
      - kind: integration
        ref: "VoteControllerTest.kt#a deck resolving below the catalog minimum leaves deck_pinned_at null and a subsequent vote is rejected with 409"
        status: pass
      - kind: integration
        ref: "DeckControllerTest.kt#a filter combination resolving to four movies returns insufficient_results with an empty movies list and the true count (deck_pinned_at null assertion)"
        status: pass
    human_judgment: false

duration: 25min
completed: 2026-09-05
status: complete
---

# Phase 4 Plan 1: Vote Recording & Match Aggregation - Tracer Slice Summary

**Pinned-deck-to-persisted-vote-to-live-status wired end-to-end: session.pinned_deck JSONB snapshot, native `FOR UPDATE` row-locked vote recording, and a database-recomputed `MatchAggregationService` with no in-JVM state.**

## Performance

- **Duration:** ~25 min
- **Started:** 2026-09-05T06:45:00Z (approx.)
- **Completed:** 2026-09-05T07:00:25Z
- **Tasks:** 2
- **Files modified:** 12 (5 created, 7 modified)

## Accomplishments
- A session's deck is now pinned lazily on the first successful `GET .../deck` read (D-01/D-03/D-04): `session.genre`/`pinned_deck`/`deck_pinned_at` (V8 migration), `SessionService.pinDeck`, and a new branch in `DeckController.getDeck` that serves the frozen JSONB snapshot on every later read with zero further upstream TMDB calls.
- Vote submission is wired end-to-end for the first time: `POST /api/sessions/{id}/votes` → `VoteService.recordVote` → session-row `FOR UPDATE` lock → the existing `VoteRepository.upsertVote` → `MatchAggregationService.computeStatus`, all inside one `@Transactional` boundary (04-RESEARCH.md Pitfalls A/B addressed directly).
- `GET /api/sessions/{id}/votes/status` reports `deckSize`/`activeCount`/`finishedCount`/`isComplete`, recomputed from `vote`/`participant`/`session` on every call — no stored or cached progress anywhere in the `voting` package (verified by a structural grep gate).
- Every vote submission that must not produce a row is refused with a specific status code (400 off-deck, 404 cross-session, 409 unpinned) and each refusal is proven by a test asserting the database was left unchanged.
- Re-based the two `DeckControllerTest` tests that pinning invalidates (stale-fallback, no-leak-across-filter-combinations) onto a second, unpinned session, plus one additional test discovered broken during Task 2 (see Deviations).

## Task Commits

1. **Task 1: One pinned deck, one persisted vote, one live status read — wired end-to-end** - `e4e743b` (feat)
2. **Task 2: Refuse the votes that must not be recorded, and re-base the deck tests that pinning invalidates** - `97d70bb` (test)

_Task 2 was TDD-tagged but the guards it tests (membership 404, unpinned 409, off-deck 400, correct ordering) were already fully implemented as part of Task 1's tracer slice per the plan's own action text — no `VoteController` changes were needed in Task 2's commit, only new tests plus the DeckControllerTest re-base._

## Files Created/Modified
- `src/main/resources/db/migration/V8__add_session_deck_pin_and_genre.sql` - adds nullable `genre`/`pinned_deck`/`deck_pinned_at` columns to `session`
- `src/main/kotlin/org/example/muvimatchr/session/Session.kt` - three new fields mirroring `DeckCacheEntry`'s JSONB pattern
- `src/main/kotlin/org/example/muvimatchr/session/SessionRepository.kt` - `lockForUpdate` native `FOR UPDATE` query
- `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt` - `pinDeck`/`pinnedMovies`/`pinnedMovieIds`
- `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt` - pinning branch + shared `toDeckMovieResponse` mapper
- `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt` - `findActiveParticipantIds`, `countFinishedParticipants`
- `src/main/kotlin/org/example/muvimatchr/voting/VoteService.kt` - `recordVote`, the single locked-transaction boundary
- `src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt` - `computeStatus`, `SessionVoteStatus`
- `src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt` - `POST .../votes`, `GET .../votes/status`
- `src/main/resources/application.properties` - `voting.inactivity-timeout-seconds=60`
- `src/test/kotlin/org/example/muvimatchr/voting/VoteControllerTest.kt` - end-to-end + guard + status coverage (10 tests)
- `src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt` - re-based 3 tests, added `deck_pinned_at` assertions

## Decisions Made
- Pinned-branch `totalResults` reports `pinnedMovies.size`, not the raw upstream TMDB total captured at first fetch — see frontmatter `key-decisions` for the full rationale (this legitimately diverges from the fresh-fetch value once TMDB's page total exceeds the fetched movie count, which surfaced as a broken pre-existing test — see Deviations).
- Boolean DTO field `isComplete` needed an explicit `@get:JsonProperty("isComplete")` — Kotlin generates an `isComplete()` getter, and Jackson's default bean introspection strips the `is` prefix, serializing it as `complete` instead.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Jackson serialized `isComplete` as `complete`, breaking the response contract**
- **Found during:** Task 1, first VoteControllerTest run
- **Issue:** `VoteStatusResponse.isComplete: Boolean` serialized to JSON as `"complete"` (Kotlin's generated `isComplete()` getter + Jackson's is-prefix-stripping bean introspection), not `"isComplete"` as the DTO and plan both name it.
- **Fix:** Added `@get:JsonProperty("isComplete")` (via `com.fasterxml.jackson.annotation.JsonProperty`, matching this codebase's existing annotation convention for its Jackson 3 / `tools.jackson` ObjectMapper) to pin the wire name.
- **Files modified:** `src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt`
- **Verification:** `VoteControllerTest` happy-path assertion on `isComplete` passes.
- **Committed in:** `e4e743b` (Task 1 commit)

**2. [Rule 1 - Bug] A third pre-existing DeckControllerTest test broke as a direct consequence of pinning, beyond the two the plan named**
- **Found during:** Task 2, full-suite verification
- **Issue:** "two different participants of the same session receive identical deck filtering" performs two deck GETs against one session and asserted `totalResults` equality between them. With pinning, the second (now-pinned) read reports `totalResults = pinnedMovies.size` while the first (fresh-fetch) read reported the raw upstream TMDB total from its deliberately-mismatched fixture (`SINGLE_MOVIE_DISCOVER_FIXTURE`: `total_results: 25`, 1 actual movie) — the same category of pinning-invalidates-a-second-GET break the plan called out for two other tests, just not this one.
- **Fix:** Swapped the fixture to `DISCOVER_FIXTURE` (5 movies, `total_results: 5` — the two values already agree), preserving the test's actual "identical deck across participants" intent without weakening any assertion.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt`
- **Verification:** `./gradlew test --tests "*.catalog.DeckControllerTest"` — 21/21 pass.
- **Committed in:** `97d70bb` (Task 2 commit)

**3. [Rule 1 - Bug] Deck-cache test pollution across sessions within a single test (and across tests)**
- **Found during:** Task 2, writing the below-catalog-minimum test
- **Issue:** `MovieCatalogService`'s deck cache is keyed by filter combination (genre, providerIds, region), not session. `createSessionWithPinnedDeck`, when called a second time in one test (or by a later test) with the default filter combination, silently hit the first call's now-cached row instead of performing a fresh fetch — leaving its own enqueued MockWebServer fixtures unconsumed, which then bled into whichever test ran next and produced a wrongly-pinned deck.
- **Fix:** Added `deckCacheRepository.deleteAll()` immediately before every pin inside the `createSessionWithPinnedDeck` test helper (plus a class-level `@BeforeEach` as a second line of defense, matching `DeckControllerTest`'s own precedent).
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/voting/VoteControllerTest.kt`
- **Verification:** `./gradlew test --tests "*.voting.VoteControllerTest"` — 10/10 pass, deterministically, across repeated runs.
- **Committed in:** `97d70bb` (Task 2 commit)

---

**Total deviations:** 3 auto-fixed (all Rule 1 - bugs surfaced by running the tests the plan itself specified)
**Impact on plan:** All three were necessary for the plan's own stated verification (`./gradlew test` passing with zero failures) to actually hold. No scope creep — no new endpoints, tables, or product behavior beyond what the plan specified.

## Issues Encountered
None beyond the three deviations documented above.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- The vote-record → live-status path is proven end-to-end against real Postgres; Plan 04-02 (genre filter locking) and 04-03 (unanimous match + like counts) can build directly on `SessionService.pinnedMovies`/`pinnedMovieIds` and `MatchAggregationService`'s active-roster query without re-deriving either.
- `MatchAggregationService.computeStatus`'s active-roster/finished-count queries are ready for Plan 04-03 to extend with the unanimous-match and per-movie-like-count queries described in 04-RESEARCH.md Pattern 2.
- Plan 04-04's concurrency test (two simultaneous final votes) can build directly on `VoteService.recordVote`'s existing `FOR UPDATE` lock — no changes needed to reach that plan's starting point.
- No blockers. The `voting.inactivity-timeout-seconds` config key is already shrinkable for tests per D-06's discretion note.

---
*Phase: 04-vote-recording-match-aggregation*
*Completed: 2026-09-05*
