---
status: testing
phase: 03-tmdb-integration-catalog-caching
source: [03-VERIFICATION.md]
started: 2026-09-04T18:07:29Z
updated: 2026-09-04T18:07:29Z
---

## Current Test

number: 1
name: Confirm ROADMAP Phase 3 criterion 1's word "real" against live TMDB (genre-filtered and provider/region-filtered deck fetch)
expected: |
  GET /api/sessions/{sessionId}/deck?genre=28 returns ~20 recognisable current/popular movies (not fixture titles)
  with leading-slash posterPath values, genreIds containing 28, plausible voteAverage; after PUT
  /api/sessions/{sessionId}/filters with a real region+provider, the deck narrows and each movie's providers
  array names the selected service with a non-null watchLink; a repeated identical fetch is served from cache
  (fetchedAt unchanged, faster response)
awaiting: user response

## Tests

### 1. Live-TMDB real-data confirmation
expected: |
  GET /api/sessions/{sessionId}/deck?genre=28 returns ~20 recognisable current/popular movies (not fixture titles)
  with leading-slash posterPath values, genreIds containing 28, plausible voteAverage; after PUT
  /api/sessions/{sessionId}/filters with a real region+provider, the deck narrows and each movie's providers
  array names the selected service with a non-null watchLink; a repeated identical fetch is served from cache
  (fetchedAt unchanged, faster response)
result: [pending]

### 2. Live-TMDB sparse-filter confirmation
expected: |
  A niche genre + small regional provider combination that resolves to fewer than 5 real movies returns
  status=insufficient_results with an empty movies array and a truthful count; no unfiltered/popular titles leak in
result: [pending]

### 3. TMDB monetization-type omitted-parameter default (COVERAGE.md assumption A1)
expected: |
  TMDB's omitted-parameter default when with_watch_providers is sent without with_watch_monetization_types is
  confirmed to be the broadest match (any streaming availability type), matching the assumption COVERAGE.md's
  OPT-OUT decision relies on
result: [pending]

### 4. Connectivity-class failure retry/degradation (CR-04 fix)
expected: |
  A connection-level failure (java.io.IOException subtype — unreachable host, connection refused, or timeout
  with no response) is retried via the same Retry.backoff cycle as an HTTP 5xx, and on exhaustion falls through
  to MovieCatalogService.getDeck's stale-fallback/503 path exactly as an HTTP 5xx failure does
result: [pending]

## Summary

total: 4
passed: 0
issues: 0
pending: 4
skipped: 0
blocked: 0

## Gaps

All four items require a real TMDB_API_TOKEN, unavailable in the dev environment where this phase was
executed and verified. Pre-existing open items in .planning/WINDOWS.md (ids 1-3). Every ROADMAP success
criterion has direct, passing automated coverage against a MockWebServer fixture; these four items confirm
behavior against the live TMDB API specifically.
