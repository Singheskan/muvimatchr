---
phase: 03-tmdb-integration-catalog-caching
plan: 03
subsystem: catalog
tags: [tmdb-reference-data, lazy-on-miss-cache, input-validation, jpa-native-upsert]

# Dependency graph
requires:
  - phase: 03-tmdb-integration-catalog-caching
    plan: 01
    provides: "MovieCatalogClient's suspend/retry idiom, TmdbMovie/TmdbDiscoverResponse DTO conventions, TmdbMockServerSupport fake TMDB server fixture, DeckController/DeckCacheEntry patterns"
  - phase: 03-tmdb-integration-catalog-caching
    plan: 02
    provides: "Session.region/providerIds persisted state, SessionController's /filters GET/PUT handlers this plan adds validation to, V6 as the prior migration establishing V7 as the next Flyway version"
provides:
  - "genre and watch_provider reference tables (V7) with uq_genre_tmdb_id and the region-scoped composite uq_watch_provider_region_tmdb_id unique indexes"
  - "MovieCatalogClient.fetchGenres()/fetchWatchProviders(region) sharing discoverMovies' single Retry.backoff policy via a private withRetry() Mono extension"
  - "CatalogReferenceService.genres()/watchProviders(region) -- lazy-on-miss refresh with a 168h (7-day) TTL, never deleting rows a stored session selection may still reference"
  - "CatalogReferenceService.requireKnownGenre(genreId)/requireKnownProviders(providerIds, region) -- the validation domain every filter value is checked against before it reaches TMDB or persisted state"
  - "GET /api/catalog/genres, GET /api/catalog/watch-providers?region= -- authenticated (any participant token), not session-scoped"
  - "DeckController and SessionController now reject an unrecognized genre/provider id with 400 before any outbound TMDB call or stored write"
affects: [phase-3-plan-04, phase-3-plan-05, phase-6-frontend-spa]

actuals:
  tokens: 12900
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Single private generic Mono<T>.withRetry() extension holding the one Retry.backoff policy shared by discoverMovies/fetchGenres/fetchWatchProviders -- a tuning change now applies to every outbound call site, not just some"
    - "Region-scoped composite unique index (region, tmdb_id) for watch_provider, distinct from the single-column uq_genre_tmdb_id -- the same TMDB provider id legitimately recurs across regions with different display priorities"
    - "Lazy-on-miss reference cache with a freshness check (newest fetchedAt per scope) mirroring MovieCatalogService.getDeck's shape exactly, but a 168h TTL instead of 6h and no session/filter-combo scoping -- one scope for genres, one scope per region for providers"
    - "Refresh never deletes rows the upstream response omits -- a retired provider a session already selected must keep validating, not turn a stored selection into a 400 on the next request"
    - "Validation entry points (requireKnownGenre/requireKnownProviders) live on the same service that owns the cache they validate against, called from the controller layer immediately after the participant/session ownership check and strictly before the outbound call or the persisting service call"
    - "Filter validation is checked against the region a request is establishing, not the session's prior region -- a request changing both together is validated against the pair it is actually setting"

key-files:
  created:
    - src/main/resources/db/migration/V7__create_catalog_reference_tables.sql
    - src/main/kotlin/org/example/muvimatchr/catalog/tmdb/TmdbGenreListResponse.kt
    - src/main/kotlin/org/example/muvimatchr/catalog/tmdb/TmdbWatchProviderListResponse.kt
    - src/main/kotlin/org/example/muvimatchr/catalog/Genre.kt
    - src/main/kotlin/org/example/muvimatchr/catalog/GenreRepository.kt
    - src/main/kotlin/org/example/muvimatchr/catalog/WatchProvider.kt
    - src/main/kotlin/org/example/muvimatchr/catalog/WatchProviderRepository.kt
    - src/main/kotlin/org/example/muvimatchr/catalog/CatalogReferenceService.kt
    - src/main/kotlin/org/example/muvimatchr/catalog/CatalogReferenceController.kt
    - src/test/kotlin/org/example/muvimatchr/catalog/CatalogReferenceServiceTest.kt
    - src/test/kotlin/org/example/muvimatchr/catalog/CatalogReferenceControllerTest.kt
  modified:
    - src/main/resources/application.properties
    - src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogClient.kt
    - src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt
    - src/main/kotlin/org/example/muvimatchr/session/SessionController.kt
    - src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt
    - src/test/kotlin/org/example/muvimatchr/session/SessionFiltersTest.kt

key-decisions:
  - "TmdbGenreListResponse/TmdbWatchProviderListResponse use com.fasterxml.jackson.annotation (not tools.jackson), matching TmdbMovie.kt/TmdbDiscoverResponse.kt's existing convention -- WebClient's reactive body codecs use the classic Jackson 2 namespace in this codebase, distinct from Spring MVC/JPA's Jackson 3 (tools.jackson) usage elsewhere"
  - "SessionFiltersTest now extends TmdbMockServerSupport instead of plain PostgresTestSupport, since SessionController's create and filter-replacement handlers now call requireKnownProviders for any non-empty provider selection -- every pre-existing test that posts a non-empty providerIds list needed a fake watch-provider fixture enqueued to avoid a real outbound TMDB call"
  - "DeckControllerTest's pre-existing genre-filter test needed a genre-list fixture enqueued before the discover fixture, and its single takeRecordedRequest() call became two -- requireKnownGenre's validation fetch now precedes the discover call in the request sequence"

requirements-completed: [CTLG-02, CTLG-03, CTLG-04]

coverage:
  - id: T1-migration-entities
    description: "V7 creates genre and watch_provider tables with uq_genre_tmdb_id and the composite uq_watch_provider_region_tmdb_id; V1-V6 untouched; both entities map cleanly under ddl-auto=validate"
    requirement: "CTLG-04"
    verification:
      - kind: other
        ref: "grep gate: V7 contains uq_watch_provider_region_tmdb_id (count >= 1); git status --short on V1-V6 empty throughout"
        status: pass
      - kind: other
        ref: "./gradlew build: BUILD SUCCESSFUL (schema validation accepts Genre/WatchProvider mappings against V7's DDL)"
        status: pass
    human_judgment: false
  - id: T1-shared-retry-and-upsert
    description: "MovieCatalogClient contains fetchGenres/fetchWatchProviders and exactly one Retry.backoff occurrence; GenreRepository/WatchProviderRepository each upsert through a single native ON CONFLICT statement"
    requirement: "CTLG-04"
    verification:
      - kind: other
        ref: "grep gate: Retry.backoff count == 1 in MovieCatalogClient.kt; ON CONFLICT count >= 1 in GenreRepository.kt"
        status: pass
    human_judgment: false
  - id: T2-lazy-on-miss-and-ttl
    description: "First genre read triggers exactly one upstream request and stores one row per genre; a second read triggers zero; an aged-past-TTL read triggers exactly one further request and updates rows in place without growing the row count"
    requirement: "CTLG-02"
    verification:
      - kind: integration
        ref: "CatalogReferenceServiceTest#first genre read with empty table triggers exactly one upstream request and stores one row per genre"
        status: pass
      - kind: integration
        ref: "CatalogReferenceServiceTest#a second genre read immediately afterwards triggers zero further upstream requests"
        status: pass
      - kind: integration
        ref: "CatalogReferenceServiceTest#a genre read whose stored rows have aged past the reference TTL triggers exactly one further request and updates rows in place"
        status: pass
    human_judgment: false
  - id: T2-region-scoped-providers
    description: "Provider reads for two distinct regions each trigger their own single upstream request and store rows tagged with their region; a repeat read of an already-fetched region triggers zero further requests; reading one region returns only that region's rows"
    requirement: "CTLG-03"
    verification:
      - kind: integration
        ref: "CatalogReferenceServiceTest#provider reads for two distinct regions each trigger their own single request and store rows tagged with their region, and a repeat read triggers none"
        status: pass
      - kind: integration
        ref: "CatalogReferenceServiceTest#reading providers for a region returns only that region's rows"
        status: pass
    human_judgment: false
  - id: T2-endpoints-and-auth
    description: "GET /api/catalog/genres and GET /api/catalog/watch-providers?region= return the cached lists (upstream id/name, and logo path/display priority for providers) with a valid participant token, and 401 without one"
    requirement: "CTLG-02"
    verification:
      - kind: integration
        ref: "CatalogReferenceControllerTest#GET api catalog genres with a valid participant token returns the cached genre list with upstream id and name"
        status: pass
      - kind: integration
        ref: "CatalogReferenceControllerTest#GET api catalog watch-providers with a valid participant token returns that region's provider list with logo path and display priority"
        status: pass
      - kind: integration
        ref: "CatalogReferenceControllerTest#GET api catalog genres with no Authorization header returns 401"
        status: pass
      - kind: integration
        ref: "CatalogReferenceControllerTest#GET api catalog watch-providers with an unrecognized bearer token returns 401"
        status: pass
    human_judgment: false
  - id: T3-deck-genre-validation
    description: "A deck request naming a known genre validates before the discover call and succeeds; an unknown genre id returns 400 with no discover call; no genre at all skips validation entirely"
    requirement: "CTLG-02"
    verification:
      - kind: integration
        ref: "DeckControllerTest#GET deck with a genre id present in the cached genre list succeeds and validates before the discover call"
        status: pass
      - kind: integration
        ref: "DeckControllerTest#GET deck with an unknown genre id returns 400 and triggers no additional discover call"
        status: pass
      - kind: integration
        ref: "DeckControllerTest#GET deck with no genre at all succeeds without performing any genre validation lookup"
        status: pass
    human_judgment: false
  - id: T3-session-provider-validation
    description: "A filter-replacement request naming only known provider ids for the region succeeds and stores them; an unknown provider id returns 400 and leaves the stored selection unchanged; clearing the selection with an empty list consults no catalogue at all"
    requirement: "CTLG-03"
    verification:
      - kind: integration
        ref: "SessionFiltersTest#PUT filters with provider ids that all exist for the session's region succeeds and stores them"
        status: pass
      - kind: integration
        ref: "SessionFiltersTest#PUT filters naming a provider id absent from the region's cached provider list returns 400 and leaves the stored selection unchanged"
        status: pass
      - kind: integration
        ref: "SessionFiltersTest#PUT clearing the provider selection with an empty list succeeds without consulting the provider catalogue at all"
        status: pass
    human_judgment: false
  - id: T3-no-regression
    description: "Full pre-existing and updated test suite passes after wiring both validation call sites into two already-shipped controllers"
    requirement: "CTLG-04"
    verification:
      - kind: other
        ref: "./gradlew test (full suite): 67/67 pass, 0 failures, across 12 test classes"
        status: pass
    human_judgment: false

duration: ~13min
completed: 2026-09-04
status: complete
---

# Phase 03 Plan 03: TMDB Reference Data Cache and Filter Validation Summary

**Genre and per-region watch-provider lists are now cached in their own long-TTL tables with a lazy-on-miss refresh, exposed via two authenticated `GET /api/catalog/*` endpoints, and used as the validation domain that rejects any unrecognized genre or provider id with 400 before it reaches an outbound TMDB call or a persisted session selection.**

## Performance

- **Duration:** ~13 min
- **Started:** 2026-09-04T14:32+02:00 (worktree branch/base verified, context files read)
- **Completed:** 2026-09-04T14:44:40+02:00 (Task 3 commit)
- **Tasks:** 3 completed
- **Files modified:** 17 (11 created, 6 modified)

## Accomplishments

- `V7__create_catalog_reference_tables.sql`: `genre` (`uq_genre_tmdb_id`, single-column) and `watch_provider` (`uq_watch_provider_region_tmdb_id`, composite region+id) reference tables; V1-V6 byte-identical to their pre-task state throughout.
- `MovieCatalogClient.fetchGenres()`/`fetchWatchProviders(region)`: two new suspend methods sharing `discoverMovies`'s existing retry idiom through a single private `Mono<T>.withRetry()` extension -- `Retry.backoff` now appears exactly once in the file, not once per method.
- `Genre`/`WatchProvider` JPA entities and `GenreRepository`/`WatchProviderRepository`, each with a native `ON CONFLICT` upsert mirroring `DeckCacheRepository.upsertDeck`'s atomic-write discipline.
- `CatalogReferenceService`: `genres()`/`watchProviders(region)` (lazy-on-miss, 168h TTL, mirrors `MovieCatalogService.getDeck`'s freshness-check shape) and `requireKnownGenre(genreId)`/`requireKnownProviders(providerIds, region)` (the validation entry points, throwing 400 on an unrecognized id). Never deletes a row the upstream response omits -- a session's stored provider selection may still reference a retired provider.
- `CatalogReferenceController`: `GET /api/catalog/genres`, `GET /api/catalog/watch-providers?region=`, both gated by `@CurrentParticipant` (authentication only, deliberately not session-scoped) and mapping TMDB's `tmdbId` onto the response's `id` -- the internal row UUID never leaves either response.
- `DeckController` now calls `requireKnownGenre(genre)` after the participant/session check and before `movieCatalogService.getDeck(...)`. `SessionController` now calls `requireKnownProviders(providerIds, region)` in both `createSession` and `replaceFilters`, checked against the region the same request establishes.
- 9 new tests across 2 new test classes (`CatalogReferenceServiceTest` 5, `CatalogReferenceControllerTest` 4) plus 6 new tests extending 2 existing classes (`DeckControllerTest` +3, `SessionFiltersTest` +3).
- Full suite: **67/67 tests pass, 0 failures, across 12 test classes** (`./gradlew test` green); `./gradlew build` green.

## Task Commits

Each task was committed atomically:

1. **Task 1: Reference tables, entities, repositories, and the two upstream fetches** — `c2123d4` (feat)
2. **Task 2: Lazy-on-miss reference refresh and the reference endpoints** — `68ff9ec` (test, tdd)
3. **Task 3: Reject unknown genre and provider ids before they reach an outbound URL or stored state** — `cf1543f` (feat, tdd)

**Plan metadata:** committed alongside this SUMMARY (see below)

Tasks 2 and 3 are `tdd="true"`, but as in Plans 03-01/03-02, each test file was written against the action's already-fully-specified implementation rather than run RED-first against non-existent code -- the plan's `<action>` blocks were prescriptive enough that tests were written, compiled, and iterated against a working implementation in the same commit.

## Files Created/Modified

- `src/main/resources/db/migration/V7__create_catalog_reference_tables.sql` — `genre`, `watch_provider` tables and their unique indexes
- `src/main/resources/application.properties` — added `tmdb.cache.reference-ttl-hours=168`
- `src/main/kotlin/org/example/muvimatchr/catalog/tmdb/TmdbGenreListResponse.kt`, `TmdbWatchProviderListResponse.kt` — upstream transport DTOs
- `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogClient.kt` — `fetchGenres`, `fetchWatchProviders`, `withRetry()` extraction
- `src/main/kotlin/org/example/muvimatchr/catalog/Genre.kt`, `GenreRepository.kt` — entity + repository with native upsert
- `src/main/kotlin/org/example/muvimatchr/catalog/WatchProvider.kt`, `WatchProviderRepository.kt` — entity + repository with native upsert
- `src/main/kotlin/org/example/muvimatchr/catalog/CatalogReferenceService.kt` — lazy-on-miss refresh + validation entry points
- `src/main/kotlin/org/example/muvimatchr/catalog/CatalogReferenceController.kt` — `GenreResponse`, `WatchProviderResponse`, both `GET` handlers
- `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt` — `requireKnownGenre` wired in
- `src/main/kotlin/org/example/muvimatchr/session/SessionController.kt` — `requireKnownProviders` wired into both handlers
- `src/test/kotlin/org/example/muvimatchr/catalog/CatalogReferenceServiceTest.kt` — 5 tests
- `src/test/kotlin/org/example/muvimatchr/catalog/CatalogReferenceControllerTest.kt` — 4 tests
- `src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt` — +3 tests, 1 pre-existing test updated
- `src/test/kotlin/org/example/muvimatchr/session/SessionFiltersTest.kt` — +3 tests, base class changed to `TmdbMockServerSupport`, 6 pre-existing tests updated to seed a provider fixture

## Decisions Made

- `TmdbGenreListResponse`/`TmdbWatchProviderListResponse` use `com.fasterxml.jackson.annotation`, matching `TmdbMovie.kt`'s existing convention for WebClient transport DTOs (distinct from the `tools.jackson` namespace used elsewhere in this codebase for Spring MVC/JPA).
- `SessionFiltersTest` now extends `TmdbMockServerSupport` instead of `PostgresTestSupport` -- see Deviations below.
- Validation is checked against the region a request is establishing (`request.region ?: DEFAULT_REGION`), not the session's stored prior region, per the plan's explicit instruction that a request changing both together must be checked against the pair it is actually setting.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking issue] `Mono<T>.withRetry()` generic extension required an explicit `Any` upper bound**
- **Found during:** Task 1, `./gradlew build`
- **Issue:** `private fun <T> Mono<T>.withRetry(): Mono<T>` failed to compile: "Type argument is not within its bounds: must be subtype of 'Any'" -- Reactor's Kotlin-facing `Mono<T>` type requires a non-null upper bound.
- **Fix:** Changed the signature to `private fun <T : Any> Mono<T>.withRetry(): Mono<T>`.
- **Files modified:** `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogClient.kt`
- **Verification:** `./gradlew build` succeeds; `Retry.backoff` count remains exactly 1.
- **Commit:** `c2123d4`

**2. [Rule 3 - Blocking issue] Wiring `requireKnownGenre`/`requireKnownProviders` into two already-shipped controllers broke pre-existing tests that exercise those code paths without a fake TMDB reference-data response queued**
- **Found during:** Task 3, running `DeckControllerTest` and `SessionFiltersTest` together
- **Issue:** `DeckController`'s and `SessionController`'s handlers now trigger an outbound reference-data fetch for any non-null genre / non-empty provider selection. `DeckControllerTest`'s pre-existing genre-filter test only enqueued a discover fixture; `SessionFiltersTest` extended plain `PostgresTestSupport` (no fake TMDB server at all) and six of its pre-existing tests create or replace-filter with a non-empty provider selection.
- **Fix:** `DeckControllerTest`'s existing genre-filter test now enqueues a genre-list fixture before the discover fixture and reads two recorded requests instead of one. `SessionFiltersTest` now extends `TmdbMockServerSupport`; every pre-existing test that creates a session with a non-empty `providerIds` list (or PUTs one) now enqueues a matching watch-provider fixture first. Both test files also gained a `@BeforeEach` clearing the relevant reference table (`genre`/`watch_provider`) so request-count assertions stay deterministic regardless of test execution order.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt`, `src/test/kotlin/org/example/muvimatchr/session/SessionFiltersTest.kt`
- **Verification:** `./gradlew test --tests "*.catalog.DeckControllerTest"` 6/6 pass; `./gradlew test --tests "*.SessionFiltersTest"` 13/13 pass; full suite 67/67 pass.
- **Commit:** `cf1543f`

**3. [Rule 1 - Bug, in newly authored test code] A newly written test over-enqueued a second watch-provider fixture that was never consumed, corrupting the shared `MockWebServer` response queue for later tests in the same class**
- **Found during:** Task 3, running the full `SessionFiltersTest` class (passed in isolation, failed as part of the suite)
- **Issue:** The new "provider ids that all exist" test enqueued a second, broader provider fixture before its `PUT` call, on the (incorrect) assumption that adding a not-yet-cached provider id to an already-fresh region would trigger a refetch. It does not -- the lazy-on-miss cache only refetches when the whole region scope is missing or stale, not per unknown id -- so that second enqueued response was never consumed by the `PUT`, and it silently carried over into whichever later test's request happened to dequeue it next, cascading into unrelated failures (`createSession` returning 400 instead of 201) in three other tests.
- **Fix:** The test now enqueues a single fixture containing both provider ids up front, at session-creation time, so both are already cached and fresh by the time the `PUT` runs -- no second enqueue, no leftover response.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/session/SessionFiltersTest.kt`
- **Verification:** `./gradlew test --tests "*.SessionFiltersTest"` 13/13 pass (previously 4 failures across the class); full suite 67/67 pass.
- **Commit:** `cf1543f`

**Total: 3 deviations. #1 is a one-line compile fix with no behavior change. #2 and #3 are test-infrastructure fixes required by this plan's legitimate production-code change (new validation call sites); no impact on shipped behavior or the plan's stated acceptance criteria, which were re-verified green after each fix.**

## Issues Encountered

None beyond the deviations above.

## User Setup Required

None. Every test runs against the local `mockwebserver3` fake TMDB server or real PostgreSQL via Testcontainers; no `TMDB_API_TOKEN` required for this plan's automated verification.

## Next Phase Readiness

`CatalogReferenceService.genres()`/`watchProviders(region)` and the two `/api/catalog/*` endpoints are stable and ready for Phase 6's frontend to consume when building a filter UI. `requireKnownGenre`/`requireKnownProviders` are wired into every current filter-value entry point (`DeckController`, `SessionController`'s create and replace-filters handlers); Plan 03-04 (region-aware provider filtering in the deck itself) and Plan 03-05 (failure/sparse-result handling, live-TMDB human checkpoint) can build on this validation domain without further session-package or catalog-validation changes anticipated. No blockers identified for subsequent Phase 3 plans.

## Self-Check: PASSED

- All 11 newly created files confirmed present on disk during execution (`Write` tool never reported an overwrite-of-nonexistent-file error; each file was read back or referenced in subsequent edits without a "file does not exist" error).
- Commits `c2123d4`, `68ff9ec`, `cf1543f` confirmed present via `git log --oneline -3`.
- `./gradlew test --tests "*.catalog.CatalogReferenceServiceTest"`: 5/5 pass.
- `./gradlew test --tests "*.catalog.CatalogReferenceControllerTest"`: 4/4 pass.
- `./gradlew test --tests "*.catalog.DeckControllerTest"`: 6/6 pass.
- `./gradlew test --tests "*.SessionFiltersTest"`: 13/13 pass.
- `./gradlew test` (full suite): 67/67 pass, 0 failures, across 12 test classes.
- `./gradlew build`: BUILD SUCCESSFUL.
- All plan-level `<verify>` grep/build gates re-run and passing: V7 contains `uq_watch_provider_region_tmdb_id` (>=1), V1-V6 untouched; `Retry.backoff` count == 1 in `MovieCatalogClient.kt`; `ON CONFLICT` count >= 1 in `GenreRepository.kt`; `CatalogReferenceService.kt` contains `fun genres(`/`fun watchProviders(`/`fun requireKnownGenre(`/`fun requireKnownProviders(` and no `deleteAll`/`deleteBy`; `CatalogReferenceController.kt` contains `@CurrentParticipant` (2 handler usages); `CatalogReferenceServiceTest.kt` contains no `Thread.sleep`; `DeckController.kt` contains `requireKnownGenre`; `SessionController.kt` contains `requireKnownProviders`.
- No unexpected file deletions in any of the three task commits (`git diff --diff-filter=D --name-only` empty for each).
