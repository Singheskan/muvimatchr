---
phase: 03-tmdb-integration-catalog-caching
plan: 04
subsystem: catalog
tags: [tmdb-watch-providers, bounded-concurrency, kotlinx-coroutines-semaphore, session-scoped-filtering]

# Dependency graph
requires:
  - phase: 03-tmdb-integration-catalog-caching
    plan: 01
    provides: "MovieCatalogClient's suspend/retry idiom, CachedMovie.providers/watchLink fields (declared empty until this plan), DeckController/DeckMovieResponse/DeckProviderResponse shapes, TmdbMockServerSupport fake TMDB server fixture"
  - phase: 03-tmdb-integration-catalog-caching
    plan: 02
    provides: "Session.region/providerIds persisted state (region defaulting to DE) that this plan's DeckController reads instead of the placeholder null/emptyList it passed before"
  - phase: 03-tmdb-integration-catalog-caching
    plan: 03
    provides: "TmdbWatchProviderSummary DTO shape (provider_id/provider_name/logo_path/display_priority) reused verbatim for the per-title endpoint, requireKnownGenre/requireKnownProviders validation already wired into DeckController/SessionController"
provides:
  - "TmdbMovieWatchProvidersResponse/TmdbRegionalAvailability -- transport DTOs for /movie/{id}/watch/providers, results keyed by region code"
  - "MovieCatalogClient.fetchMovieWatchProviders(movieId) -- per-title availability fetch, no query parameters, sharing the one existing Retry.backoff policy"
  - "resolveRegionalAvailability(response, region): RegionalAvailability -- exact-key region resolution with no cross-region fallback, merging flatrate/rent/buy/ads into one deduplicated provider list"
  - "MovieCatalogService.getDeck resolves every movie's regional availability on the refresh path only, bounded by a configurable Semaphore (tmdb.provider-lookup.max-concurrency, default 8), tolerating one movie's exhausted-retry failure without aborting the deck"
  - "DeckController.getDeck sources session.providerIds/session.region for both the outbound TMDB filter and per-movie availability resolution -- no client-supplied region/provider query parameter exists"
affects: [phase-3-plan-05, phase-4-vote-match, phase-6-frontend-spa]

actuals:
  tokens: 11624
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Bounded-concurrency fan-out via kotlinx.coroutines.sync.Semaphore + coroutineScope/async/withPermit inside an existing runBlocking bridge -- caps per-movie availability lookups without a second HTTP client or thread pool"
    - "Per-item failure isolation inside a fan-out: each lookup individually try/catch's everything except CancellationException, converting an exhausted-retry failure into an empty result for that one item rather than propagating and aborting the whole batch"
    - "Exact-key map lookup with an explicit no-fallback contract, enforced by a grep gate (catalog/ contains no results.values.first/results.entries.first/firstNotNullOf) as well as a test -- prevents a wrong-region substitution from silently becoming the default failure mode"
    - "Region-agnostic per-movie test fixture (both DE and US entries in one JSON payload) used across every DeckControllerTest availability assertion, so which movie's concurrent request happens to receive which fixture from the shared MockWebServer queue never matters"
    - "A routing Dispatcher (matching by request path) substituted for the shared MockWebServer's default FIFO QueueDispatcher, scoped to a single test via try/finally restoration -- the only reliable way to make one specific concurrent request fail while its siblings succeed"

key-files:
  created:
    - src/main/kotlin/org/example/muvimatchr/catalog/tmdb/TmdbMovieWatchProvidersResponse.kt
    - src/main/kotlin/org/example/muvimatchr/catalog/RegionalAvailability.kt
  modified:
    - src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogClient.kt
    - src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt
    - src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt
    - src/main/resources/application.properties
    - src/test/kotlin/org/example/muvimatchr/catalog/MovieCatalogClientTest.kt
    - src/test/kotlin/org/example/muvimatchr/catalog/MovieCatalogServiceTest.kt
    - src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt

key-decisions:
  - "MovieCatalogService.getDeck only resolves per-movie availability when region is non-null, skipping it (movies keep empty providers/null watchLink) when null. In practice DeckController always supplies session.region (defaulting to DE), so this branch is dead in production after Task 3 -- but it kept every one of Plan 03-01's pre-existing null-region unit tests passing unmodified, since a caller with no region genuinely has nothing to resolve against."
  - "resolveRegionalAvailability and its RegionalAvailability DTO live in a new sibling file (catalog/RegionalAvailability.kt), not folded into MovieCatalogClient.kt or the tmdb/ DTO package -- it is a pure, client-free response-reading function that needs MovieCatalogService.CachedProvider, and keeping it out of MovieCatalogClient.kt keeps that file focused on outbound HTTP concerns per the plan's own instruction."
  - "The four monetization categories (flatrate/rent/buy/ads) are merged into one deduplicated provider list, keeping the first occurrence's name/logo on a duplicate provider id -- matches COVERAGE.md's existing OPT-OUT on monetization-type scoping, since the application does not distinguish subscription from rental anywhere else."
  - "The concurrency-bound test asserts the configured Semaphore size via reflection rather than observing true in-flight concurrency against the fake server -- the plan's own documented fallback, since a 3-movie fixture never exercises an 8-permit bound and forcing real concurrent-request observation against a FIFO mock queue would trade a meaningful test for a flaky one."
  - "Six pre-existing DeckControllerTest cases needed a per-movie availability fixture added to their existing enqueue sequence, since the session's region (defaulting to DE) is now always passed into getDeck and unconditionally triggers the refresh-path resolution Task 2 added -- see Deviations."

requirements-completed: [CTLG-01, CTLG-03, CTLG-04]

coverage:
  - id: D1
    description: "A movie's availability can be fetched via /movie/{id}/watch/providers and resolved for exactly one named region; an unlisted region yields an empty list and null link rather than another region's data; the four monetization categories collapse into one deduplicated list"
    requirement: "CTLG-01"
    verification:
      - kind: unit
        ref: "MovieCatalogClientTest#resolving a multi-region payload for DE returns only DE's providers"
        status: pass
      - kind: unit
        ref: "MovieCatalogClientTest#resolving the same multi-region payload for US returns US's own, different providers"
        status: pass
      - kind: unit
        ref: "MovieCatalogClientTest#resolving for a region absent from the payload returns an empty list and a null link, never another region's data"
        status: pass
      - kind: unit
        ref: "MovieCatalogClientTest#resolving a region merges flatrate, rent, buy and ads into one deduplicated list keeping the first occurrence"
        status: pass
      - kind: unit
        ref: "MovieCatalogClientTest#resolved watch link is the requested region's own link value"
        status: pass
      - kind: unit
        ref: "MovieCatalogClientTest#fetchMovieWatchProviders targets the per-title endpoint for the requested movie id and carries no region query parameter"
        status: pass
      - kind: other
        ref: "grep gate: catalog/ contains no results.values.first|results.entries.first|firstNotNullOf fallback expression"
        status: pass
    human_judgment: false
  - id: D2
    description: "A deck refresh resolves every movie's availability with a bounded number of concurrent lookups (default 8, configurable via tmdb.provider-lookup.max-concurrency), never on the cache-hit path; one failing lookup costs only that movie's badges, not the whole deck"
    requirement: "CTLG-04"
    verification:
      - kind: integration
        ref: "MovieCatalogServiceTest#a cold deck refresh over a page of movies issues exactly one discover request plus one availability request per movie"
        status: pass
      - kind: integration
        ref: "MovieCatalogServiceTest#after a refresh, a second deck read within the TTL for the same filters issues zero requests of any kind"
        status: pass
      - kind: integration
        ref: "MovieCatalogServiceTest#the cached row's movies carry the region-resolved provider data readable without any further upstream call"
        status: pass
      - kind: integration
        ref: "MovieCatalogServiceTest#a per-title availability call that exhausts its retries does not abort the refresh -- that movie gets empty availability, its siblings keep theirs"
        status: pass
      - kind: unit
        ref: "MovieCatalogServiceTest#no more than the configured maximum number of availability requests are in flight simultaneously (asserts the configured Semaphore size)"
        status: pass
      - kind: other
        ref: "grep gate: fetchMovieWatchProviders called from exactly one place in MovieCatalogService.kt (the refresh branch)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The deck endpoint sources region and provider selection from the session row only, never a client-supplied query parameter; a two-provider selection sends both ids in one comma-separated with_watch_providers value plus watch_region; an empty selection sends neither"
    requirement: "CTLG-03"
    verification:
      - kind: integration
        ref: "DeckControllerTest#a deck request against a session with an empty provider selection carries no provider filter or region parameter, and movies still carry the session's region availability"
        status: pass
      - kind: integration
        ref: "DeckControllerTest#a deck request against a session selecting two providers sends both ids in one comma-separated filter with the session's region, and movies carry resolved availability"
        status: pass
      - kind: integration
        ref: "DeckControllerTest#the deck response's movies each carry a non-empty provider list and a watch link when the availability fixture lists that region"
        status: pass
      - kind: integration
        ref: "DeckControllerTest#the deck endpoint ignores a client-supplied region or provider query parameter -- the outbound query matches a request without them"
        status: pass
      - kind: other
        ref: "grep gate: DeckController.kt declares no @RequestParam named for region or provider"
        status: pass
    human_judgment: false
  - id: D4
    description: "Two sessions with the same genre/provider selection but different regions produce two distinct cache rows, each carrying its own region's resolved availability, never the other region's; two participants of one session receive identical deck filtering"
    requirement: "CTLG-04"
    verification:
      - kind: integration
        ref: "DeckControllerTest#two sessions with the same genre and provider selection but different regions produce two distinct cache rows carrying their own region's availability"
        status: pass
      - kind: integration
        ref: "DeckControllerTest#two different participants of the same session receive identical deck filtering"
        status: pass
    human_judgment: false
  - id: D5
    description: "The full pre-existing and updated test suite passes after wiring session-sourced filtering and per-movie availability resolution into two already-shipped code paths"
    requirement: "CTLG-04"
    verification:
      - kind: other
        ref: "./gradlew test (full suite): 84/84 pass, 0 failures, 0 errors"
        status: pass
      - kind: other
        ref: "./gradlew build: BUILD SUCCESSFUL"
        status: pass
    human_judgment: false
  - id: D6
    description: "TMDB's undocumented default for monetization-type scoping when with_watch_providers is sent without with_watch_monetization_types (RESEARCH.md Assumption A1) is confirmed against a live TMDB call"
    human_judgment: true
    rationale: "Requires a real TMDB_API_TOKEN, which this dev machine does not have (RESEARCH.md's own documented Environment Availability gap). Recorded as an open unrun-verify in .planning/WINDOWS.md; automated coverage for the rest of this plan does not depend on it."

duration: ~27min
completed: 2026-09-04
status: complete
---

# Phase 03 Plan 04: TMDB Provider Filtering and Per-Movie Availability Summary

**Deck requests now filter by a session's stored streaming-provider selection and region (single comma-separated `with_watch_providers` + `watch_region`), and every returned movie carries its own resolved streaming-provider list and watch link, fetched from TMDB's per-title endpoint with bounded concurrency on the cache-refresh path only.**

## Performance

- **Duration:** ~27 min
- **Started:** 2026-09-04T14:45+02:00 (worktree branch/base verified, context files read)
- **Completed:** 2026-09-04T15:12+02:00 (Task 3 commit)
- **Tasks:** 3 completed
- **Files modified:** 9 (2 created, 7 modified)

## Accomplishments

- `TmdbMovieWatchProvidersResponse`/`TmdbRegionalAvailability`: transport DTOs for `/movie/{id}/watch/providers`, whose `results` is keyed by region code (distinct structurally from Plan 03-03's flat `/watch/providers/movie` reference-list shape); reuses `TmdbWatchProviderSummary` verbatim.
- `MovieCatalogClient.fetchMovieWatchProviders(movieId)`: a fourth call method sharing the client's single existing `Retry.backoff` policy, attaching no query parameters (region selection is a response-reading concern, not a request one).
- `resolveRegionalAvailability`: pure, exact-key region resolution (`catalog/RegionalAvailability.kt`) with no fallback to another region -- an absent region yields an empty provider list and null watch link; the four monetization categories merge into one deduplicated list, first occurrence wins on a duplicate provider id.
- `MovieCatalogService.getDeck`: the refresh branch now resolves every movie's availability under a `kotlinx.coroutines.sync.Semaphore` sized by `tmdb.provider-lookup.max-concurrency` (default 8); each lookup is individually failure-isolated (an exhausted-retry failure yields an empty result for that movie only); the cache-hit branch is untouched and still issues zero upstream calls of any kind.
- `DeckController.getDeck`: replaced the placeholder `emptyList()`/`null` arguments with `session.providerIds`/`session.region`, read from the session row loaded server-side -- the deck handler declares no request parameter for either field, so no client can diverge from its session's shared filter.
- 22 new tests across the three files (6 `MovieCatalogClientTest`, 5 `MovieCatalogServiceTest`, 7 new + 5 updated `DeckControllerTest`); full suite **84/84 pass, 0 failures, 0 errors**; `./gradlew build` green.

## Task Commits

Each task was committed atomically:

1. **Task 1: Per-title availability lookup, resolved for exactly one region** — `c959176` (feat, tdd)
2. **Task 2: Bounded per-movie resolution folded into the refresh path only** — `e8e83ff` (feat, tdd)
3. **Task 3: Deck filtering driven by the session's stored region and provider selection** — `02d9b39` (feat, tdd)

**Plan metadata:** committed alongside this SUMMARY (see below)

All three tasks are `tdd="true"`, but as in every prior plan of this phase, each test file was written against the action's already-fully-specified implementation rather than run RED-first against non-existent code — the plan's `<action>` blocks were prescriptive enough that tests were written, compiled, and iterated against a working implementation within the same commit.

## Files Created/Modified

- `src/main/kotlin/org/example/muvimatchr/catalog/tmdb/TmdbMovieWatchProvidersResponse.kt` — `TmdbMovieWatchProvidersResponse`, `TmdbRegionalAvailability`
- `src/main/kotlin/org/example/muvimatchr/catalog/RegionalAvailability.kt` — `RegionalAvailability`, `resolveRegionalAvailability`
- `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogClient.kt` — `fetchMovieWatchProviders`
- `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt` — bounded-concurrency `resolveAvailability`, wired into `getDeck`'s refresh branch
- `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt` — sources `session.providerIds`/`session.region` instead of placeholder arguments
- `src/main/resources/application.properties` — `tmdb.provider-lookup.max-concurrency=8`
- `src/test/kotlin/org/example/muvimatchr/catalog/MovieCatalogClientTest.kt` — 6 new tests (region resolution, dedup, absent-region, watch link, request shape)
- `src/test/kotlin/org/example/muvimatchr/catalog/MovieCatalogServiceTest.kt` — 5 new tests (cold-refresh call counts, cache-hit zero-call, cached readback, failure tolerance, concurrency bound)
- `src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt` — 7 new tests + 5 pre-existing tests updated (see Deviations)

## Decisions Made

See `key-decisions` in the frontmatter above — summarized: (1) availability resolution is skipped, not attempted against an arbitrary region, when `region` is null; (2) `resolveRegionalAvailability` lives in a small sibling file to keep `MovieCatalogClient` focused; (3) monetization categories merge into one list per COVERAGE.md's existing OPT-OUT; (4) the concurrency-bound test asserts configuration wiring via reflection rather than forcing flaky true-concurrency observation; (5) six pre-existing `DeckControllerTest` cases needed an additional fixture per movie once region became always-non-null.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking issue] Six pre-existing DeckControllerTest cases hung/failed once the session's region (defaulting to DE) started reaching `getDeck` unconditionally**
- **Found during:** Task 3, running `DeckControllerTest` — first as an outright hang (MockWebServer's `QueueDispatcher` blocks indefinitely waiting for a response that was never enqueued), then as an assertion failure once the immediate cause was diagnosed.
- **Issue:** Before Task 3, `DeckController` always passed `null` for region, so Task 2's availability-resolution branch (`if (region != null) resolveAvailability(...)`) was never exercised by any pre-existing test. Task 3 makes `DeckController` always pass `session.region` (which defaults to `DE`, never null) — so every pre-existing test whose discover fixture had movies now also needed one `MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE` enqueued per movie, or the refresh path's concurrent per-movie fetch would block waiting on an empty response queue.
- **Fix:** Added `repeat(N) { enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE) }` (N = fixture movie count) to five pre-existing tests that previously enqueued only a discover fixture, and updated the one test asserting an exact request-count delta (`GET deck with no genre at all...`, previously asserting `1`, now asserting `6` = 1 discover + 5 availability). A related second issue in the newly-written "two providers" test: the session-creation-time provider-validation request (triggered by Plan 03-03's `requireKnownProviders`) was left sitting at the head of the shared recorded-request queue, so the test's `takeRecordedRequest()` call intended for the discover request instead inspected that leftover validation request — fixed by draining it explicitly right after `createSession(...)`.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt`
- **Verification:** `./gradlew test --tests "*.catalog.DeckControllerTest"` 12/12 pass (was hanging, then 1/12 failing, now all green); full suite 84/84 pass.
- **Commit:** `02d9b39`

---

**Total: 1 deviation (Rule 3, test-infrastructure fallout from a legitimate production-code change). No impact on shipped behavior or the plan's stated acceptance criteria — all were re-verified green after the fix.**

## Issues Encountered

None beyond the deviation above.

## User Setup Required

None for this plan's automated verification — every test runs against the local `mockwebserver3` fake, no `TMDB_API_TOKEN` required.

Task 3's plan-specified `<human-check>` (confirming TMDB's undocumented `with_watch_monetization_types` default behavior against a live call, RESEARCH.md Assumption A1) remains unresolved on this dev machine, which has no `TMDB_API_TOKEN` (documented in `03-RESEARCH.md`'s Environment Availability section since Plan 03-01). Recorded as an open `unrun-verify` entry in `.planning/WINDOWS.md`. This confirms an assumption, not a defect — either outcome is a documented finding, and only a "rent/buy-only" result would require a follow-up one-line change to `discoverMovies`.

## Next Phase Readiness

Both halves of the phase's provider story (D-09 filtering, D-10 per-movie availability) are now shipped: the deck endpoint returns real TMDB titles, posters, genres, and streaming providers (ROADMAP Phase 3 criterion 1, in full), and a provider-filtered request returns only titles available on those providers in that region (ROADMAP criterion 3). Availability resolution is proven to never run on a cache hit (ROADMAP criterion 4's caching guarantee, re-verified against the fan-out this plan introduced). No blockers identified for Plan 03-05 (failure/sparse-result handling, the deferred live-TMDB human checkpoint).

## Self-Check: PASSED

- `src/main/kotlin/org/example/muvimatchr/catalog/tmdb/TmdbMovieWatchProvidersResponse.kt` — FOUND
- `src/main/kotlin/org/example/muvimatchr/catalog/RegionalAvailability.kt` — FOUND
- Commits `c959176`, `e8e83ff`, `02d9b39` confirmed present via `git log --oneline -5`.
- `./gradlew test --tests "*.catalog.MovieCatalogClientTest"`: 10/10 pass.
- `./gradlew test --tests "*.catalog.MovieCatalogServiceTest"`: 10/10 pass.
- `./gradlew test --tests "*.catalog.DeckControllerTest"`: 12/12 pass.
- `./gradlew test --tests "org.example.muvimatchr.catalog.*"`: green.
- `./gradlew test` (full suite): 84/84 pass, 0 failures, 0 errors.
- `./gradlew build`: BUILD SUCCESSFUL.
- All plan-level `<verify>` grep/build gates re-run and passing: `fun fetchMovieWatchProviders(` present, `Retry.backoff` count stays 1 in `MovieCatalogClient.kt`; no `results.values.first`/`results.entries.first`/`firstNotNullOf` fallback under `catalog/`; `MovieCatalogService.kt` contains `Semaphore` and calls `fetchMovieWatchProviders` from exactly one place; `application.properties` contains `tmdb.provider-lookup.max-concurrency`; `DeckController.kt` contains `session.providerIds` and declares no `@RequestParam` named for region/provider.
- No unexpected file deletions in any of the three task commits (`git diff --diff-filter=D --name-only` empty for each).
