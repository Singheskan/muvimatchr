---
status: complete
phase: 03-tmdb-integration-catalog-caching
source: [03-VERIFICATION.md]
started: 2026-09-04T18:07:29Z
updated: 2026-09-04T18:46:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Live-TMDB real-data confirmation
expected: |
  GET /api/sessions/{sessionId}/deck?genre=28 returns ~20 recognisable current/popular movies (not fixture titles)
  with leading-slash posterPath values, genreIds containing 28, plausible voteAverage; after PUT
  /api/sessions/{sessionId}/filters with a real region+provider, the deck narrows and each movie's providers
  array names the selected service with a non-null watchLink; a repeated identical fetch is served from cache
  (fetchedAt unchanged, faster response)
result: pass
notes: |
  Confirmed against real TMDB with a genuine TMDB_API_TOKEN. Genre-filtered deck returned real,
  current titles (e.g. "Spider-Man: Brand New Day", 2026 release) with correct genreIds, leading-slash
  posterPath, plausible voteAverage. Setting session filters to Netflix (DE) narrowed the deck
  (49688 -> 693 total results) with plausible titles (LOTR trilogy, etc).
  Found and fixed a real bug along the way: every movie's `providers`/`watchLink` were empty/null
  despite correct upstream filtering. Root cause: TmdbRegionalAvailability's Kotlin data class
  defaults never applied when TMDB omits a monetization-category JSON key entirely (which it does
  for ~all `ads` and ~40% of `rent`/`buy` across a movie's ~126 regions) — Jackson passed null into
  a non-nullable constructor parameter and threw, silently caught upstream as an empty result.
  Fixed (commit 831ba1e) by making the fields nullable + coalescing to empty at the call site, with
  a new regression test reproducing the exact missing-key shape. Re-verified live post-fix: every
  movie in a provider-filtered deck now carries the correct providers array (Netflix + others) and
  a non-null watchLink pointing at TMDB's own watch page for that title/region.

### 2. Live-TMDB sparse-filter confirmation
expected: |
  A niche genre + small regional provider combination that resolves to fewer than 5 real movies returns
  status=insufficient_results with an empty movies array and a truthful count; no unfiltered/popular titles leak in
result: pass
notes: |
  Found a genuinely sparse real-world combo: Genre 37 (Western) + provider 444 (Dekkoo, DE) = 1
  real TMDB result. Hitting GET /deck?genre=37 with a session filtered to Dekkoo returned
  {"status":"insufficient_results","totalResults":1,"movies":[]} exactly as specified — empty
  movies array, truthful count, no leaked unfiltered titles.

### 3. TMDB monetization-type omitted-parameter default (COVERAGE.md assumption A1)
expected: |
  TMDB's omitted-parameter default when with_watch_providers is sent without with_watch_monetization_types is
  confirmed to be the broadest match (any streaming availability type), matching the assumption COVERAGE.md's
  OPT-OUT decision relies on
result: pass
notes: |
  Confirmed via direct comparison against TMDB using a transactional-only provider (Google Play
  Movies, id 3, DE) to properly discriminate (Netflix is flatrate-only and doesn't distinguish).
  Omitted parameter: 18898 total_results. Explicit `flatrate` only: 10938. Explicit `buy` only:
  18898. Explicit `rent|buy`: 18898. The omitted-parameter result exactly matches the broadest
  explicit combination and clearly exceeds the narrowest — confirms TMDB's undocumented default is
  the broadest match, validating COVERAGE.md's OPT-OUT assumption A1.

### 4. Connectivity-class failure retry/degradation (CR-04 fix)
expected: |
  A connection-level failure (java.io.IOException subtype) is retried via the same Retry.backoff cycle as an
  HTTP 5xx, and on exhaustion falls through to MovieCatalogService.getDeck's stale-fallback/503 path exactly
  as an HTTP 5xx failure does
result: pass
notes: |
  Found a real bug: simulating a genuine connection-refused failure (TMDB base URL pointed at a
  closed local port) returned an unhandled 500 in ~0.2s with zero retries — CR-04's fix checked
  `throwable is java.io.IOException`, but Spring WebClient wraps every request-phase I/O failure in
  WebClientRequestException (a RuntimeException), with the real IOException only reachable via
  `.cause`. Fixed (commit 831ba1e) by walking the bounded cause chain instead of checking only the
  outermost throwable, with a focused unit test reproducing the exact wrapped-exception shape.
  Re-verified live post-fix, both halves of the degradation ladder: (a) no cached row for the
  filter combo -> retried (~4.2s) then correctly returned 503 with the expected message; (b) a
  cached row forced past its 6h TTL via direct DB update -> retried (~2.8s) then correctly served
  the stale row with stale=true and the original (unrefreshed) fetchedAt.

## Summary

total: 4
passed: 4
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

None. All four items passed against the live TMDB API. Two real defects were found and fixed
during this verification pass (not gaps in the phase's own deliverables, but genuine bugs the
MockWebServer-only test suite could not catch) — see commit 831ba1e for details.
