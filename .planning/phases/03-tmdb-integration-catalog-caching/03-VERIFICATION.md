---
phase: 03-tmdb-integration-catalog-caching
verified: 2026-09-04T18:07:29Z
status: passed
score: 5/5 must-haves verified
behavior_unverified: 0
overrides_applied: 0
human_verification:

  - test: "Confirm ROADMAP Phase 3 criterion 1's word 'real' against live TMDB (genre-filtered and provider/region-filtered deck fetch)"
    expected: "GET /api/sessions/{sessionId}/deck?genre=28 returns ~20 recognisable current/popular movies (not fixture titles) with leading-slash posterPath values, genreIds containing 28, plausible voteAverage; after PUT /api/sessions/{sessionId}/filters with a real region+provider, the deck narrows and each movie's providers array names the selected service with a non-null watchLink; a repeated identical fetch is served from cache (fetchedAt unchanged, faster response)"
    why_human: "Every automated test in this phase runs against a local MockWebServer fixture. No test in the suite ever calls the real api.themoviedb.org. This is the only unverified piece of ROADMAP criterion 1's word 'real' — recorded as open WINDOWS.md item #2, unresolved because TMDB_API_TOKEN was unavailable on the dev machine during execution."
  - test: "Confirm the sparse-filter (insufficient_results) experience against live TMDB with a genuinely narrow real-world filter combination"
    expected: "A niche genre + small regional provider combination that resolves to fewer than 5 real movies returns status=insufficient_results with an empty movies array and a truthful count; no unfiltered/popular titles leak in"
    why_human: "Automated tests construct sparse results from fixtures, not real TMDB query results. Recorded as open WINDOWS.md item #3."
  - test: "Confirm TMDB's actual default behavior when with_watch_providers is sent without with_watch_monetization_types (COVERAGE.md OPT-OUT assumption A1)"
    expected: "TMDB's omitted-parameter default is confirmed to be the broadest match (any streaming availability type), matching the assumption COVERAGE.md's OPT-OUT decision relies on"
    why_human: "TMDB's own docs are silent on this default; only a live call can confirm it. Recorded as open WINDOWS.md item #1."
  - test: "Exercise a genuine TMDB connectivity failure (unreachable host, connection refused, or timeout with no response) and confirm the retry-then-stale-fallback ladder engages"
    expected: "A connection-level failure (java.io.IOException subtype) is retried via the same Retry.backoff cycle as an HTTP 5xx, and on exhaustion falls through to MovieCatalogService.getDeck's stale-fallback/503 path exactly as an HTTP 5xx failure does"
    why_human: "CR-04's fix (commit 3dd200b) broadens the retry filter to include java.io.IOException, but the review-fix report itself flags this as untested: the existing MockWebServer-based suite only simulates HTTP 5xx responses, never a genuine connection-level failure. No unit test exercises this path."
---

# Phase 03: TMDB Integration & Catalog Caching Verification Report

**Phase Goal:** The movie deck participants swipe through is sourced from real, current TMDB data, filterable and cached, with TMDB credentials never exposed to the client.
**Verified:** 2026-09-04T18:07:29Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (ROADMAP Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A deck-fetch endpoint returns real TMDB titles, posters, genres, and streaming providers — not placeholder or mock data | ✓ VERIFIED (structural) / human item open | `DeckController.getDeck` sources exclusively from `MovieCatalogService.getDeck` → `MovieCatalogClient.discoverMovies` → real `GET https://api.themoviedb.org/3/discover/movie` (no hardcoded/literal movie list anywhere in `src/main`). `DeckControllerTest#GET deck with a genre filter returns real upstream movie data translated into the app's own DTOs` proves the wiring against a fake server. Confirmation against the actual live TMDB API was never run (WINDOWS.md item #2, open) — see human verification below. |
| 2 | Requesting the deck with a genre filter returns only movies matching that genre | ✓ VERIFIED | `MovieCatalogClient.discoverMovies` sends `with_genres=<id>` only when a genre is supplied; `CatalogReferenceService.requireKnownGenre` rejects unknown ids with 400 before any outbound call. Tests: `DeckControllerTest#GET deck with a genre id present in the cached genre list succeeds and validates before the discover call`, `#GET deck with an unknown genre id returns 400 and triggers no additional discover call`, `MovieCatalogServiceTest#two getDeck calls differing only by genre produce two distinct cache keys and two distinct rows`. |
| 3 | Requesting the deck with a region + streaming-provider filter returns only movies available on that provider in that region | ✓ VERIFIED | `DeckController` sources `session.providerIds`/`session.region` (never a client query param) into `discoverMovies`, which sends `with_watch_providers=<ids>` + `watch_region=<region>`. Tests: `DeckControllerTest#a deck request against a session selecting two providers sends both ids in one comma-separated filter with the session's region...`, `#two sessions with the same genre and provider selection but different regions produce two distinct cache rows carrying their own region's availability`, `#the deck endpoint ignores a client-supplied region or provider query parameter`. |
| 4 | A second deck request for the same filters within the cache TTL does not trigger a new upstream TMDB call | ✓ VERIFIED | `MovieCatalogService.getDeck` reads `DeckCacheRepository.findByCacheKey` and returns immediately when `fetchedAt` is within `deckTtlHours`, with the atomic `INSERT ... ON CONFLICT` upsert as the only write path. Tests: `MovieCatalogServiceTest#two consecutive getDeck calls within the TTL increase the request count by exactly one total`, `#after a refresh, a second deck read within the TTL for the same filters issues zero requests of any kind`, `DeckControllerTest#a sparse filter combination is cached like any other`. |
| 5 | No response reaching the frontend, and no frontend-bundled code, ever contains the TMDB API key — all TMDB calls are backend-proxied | ✓ VERIFIED | `CatalogWebClientConfig.tmdbWebClient` sets the token exactly once as `Authorization: Bearer $token` on the WebClient bean — grep across `src/main/kotlin/org/example/muvimatchr/catalog/` confirms no other file references the token, and no call site passes it as a query parameter. Tests: `DeckControllerTest#GET deck response body never contains the TMDB credential value`, `#GET deck response headers never contain the TMDB credential value`, `MovieCatalogClientTest#discoverMovies sends the credential as a Bearer Authorization header`, `#discoverMovies request URL contains no credential`. |

**Score:** 5/5 truths verified (0 present-but-behavior-unverified). Truth 1 carries an open human-verification item for the live-API confirmation specifically (see below) — this does not change its VERIFIED status since the code path is genuinely and exclusively wired to the real TMDB endpoint (no mock/placeholder data in production code), but the "current, real" *content* claim has not been confirmed against the actual live API in this environment.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/kotlin/.../catalog/MovieCatalogClient.kt` | TMDB HTTP client: discover, genres, watch-providers, per-movie availability | ✓ VERIFIED | All 4 methods present, credential-only-as-header confirmed, retry policy present and broadened (CR-04) |
| `src/main/kotlin/.../catalog/MovieCatalogService.kt` | Cache read/refresh/degrade orchestration | ✓ VERIFIED | TTL check, atomic upsert (CR-01 hashed key + try/catch), retry-exhausted stale fallback, `MINIMUM_DECK_SIZE` |
| `src/main/kotlin/.../catalog/CacheKey.kt` | Bounded, deterministic cache key | ✓ VERIFIED | CR-01: provider list SHA-256 hashed, bounded well under VARCHAR(128) |
| `src/main/kotlin/.../catalog/DeckController.kt` | Authenticated deck endpoint, session-sourced filters, insufficient-results envelope | ✓ VERIFIED | `@CurrentParticipant`, session-sourced region/providers, `insufficient_results` discriminator |
| `src/main/kotlin/.../catalog/CatalogReferenceController.kt` / `CatalogReferenceService.kt` | Genre/watch-provider reference caches | ✓ VERIFIED | Lazy-on-miss TTL refresh; CR-03 region validated; WR-01 stale-fallback added |
| `src/main/kotlin/.../session/SessionController.kt` / `SessionService.kt` | Session region/provider selection state | ✓ VERIFIED | CR-02: provider count bounded (≤20), join-code retry loop no longer misdiagnoses overflow |
| `src/main/resources/db/migration/V5,V6,V7__*.sql` | Deck cache, session filter columns, reference tables | ✓ VERIFIED | Present, applied cleanly (BUILD SUCCESSFUL against real Postgres via Testcontainers) |
| `src/main/kotlin/.../catalog/CatalogWebClientConfig.kt` | Single point where the TMDB credential exists | ✓ VERIFIED | WR-02: bounded connect/response timeouts added |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `DeckController` | `MovieCatalogService` | `movieCatalogService.getDeck(genre, session.providerIds, session.region)` | ✓ WIRED | Session-sourced, not client-sourced |
| `MovieCatalogService` | `MovieCatalogClient` | `runBlocking { movieCatalogClient.discoverMovies(...) }` | ✓ WIRED | Real HTTP call, no bypass |
| `MovieCatalogService` | `DeckCacheRepository` | `findByCacheKey` / `upsertDeck` (native `ON CONFLICT`) | ✓ WIRED | Atomic, race-safe |
| `SessionController` | `CatalogReferenceService` | `requireKnownProviders` / `requireKnownGenre` before any state write or TMDB call | ✓ WIRED | Unknown ids rejected pre-upstream |
| `CatalogWebClientConfig` | `MovieCatalogClient` | `Authorization: Bearer $token` default header on injected `WebClient` | ✓ WIRED | Confirmed no query-param or body usage anywhere |

### Code Review Fix Verification (03-REVIEW.md → 03-REVIEW-FIX.md)

All 8 in-scope findings (4 critical, 4 warning) were independently re-verified present in the current `main` checkout, not just claimed in the fix report:

| ID | Finding | Fix commit | Verified in code |
|----|---------|-----------|-------------------|
| CR-01 | Deck cache key could exceed `VARCHAR(128)` | `6d24aab` | ✓ `CacheKey.kt` hashes provider part (SHA-256); `MovieCatalogService.getDeck` wraps `upsertDeck` in `try/catch (DataIntegrityViolationException)` |
| CR-02 | Session `provider_ids` could exceed `VARCHAR(255)`, misdiagnosed as join-code collision | `3779f83` | ✓ `SessionController.validateProviderIds` caps at 20; `SessionService.createSession` checks `e.mostSpecificCause.message` for `uq_session_join_code` before swallowing |
| CR-03 | `watch-providers` endpoint accepted unvalidated `region` | `5e31b91` | ✓ `@Validated` class-level + `@Pattern(regexp = "^[A-Z]{2}$")` on `region` param |
| CR-04 | Connectivity-class failures bypassed retry/degradation ladder | `3dd200b` | ✓ `withRetry()` filter now includes `throwable is java.io.IOException`. **No test exercises this path** — flagged for human verification below (matches the fix report's own "recommend human verification" note) |
| WR-01 | Reference-data refresh had no fallback on TMDB failure | `faedf04` | ✓ `ensureGenresFresh`/`ensureWatchProvidersFresh` wrapped in try/catch, serve stale rows when `newest != null` |
| WR-02 | No connect/response timeout on `tmdbWebClient` | `197a0df` | ✓ `HttpClient` with `CONNECT_TIMEOUT_MILLIS=5000` and `responseTimeout(10s)` |
| WR-03 | `providerLookupConcurrency` unvalidated, could deadlock | `5326f5c` | ✓ Property setter `coerceAtLeast(1)` |
| WR-04 | `DeckResult.fetchedAt` mismatched persisted timestamp | `8055f5b` | ✓ `upsertDeck` takes bound `fetchedAt: Instant` param, same instant used in `DeckResult` |

All 8 fix commits exist in `git log` with the stated diffs (`git show --stat` confirmed for each).

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full test suite compiles and passes | `./gradlew test` (JAVA_HOME/DOCKER_HOST/TESTCONTAINERS_RYUK_DISABLED exported) | `BUILD SUCCESSFUL in 35s`, 5 tasks (1 executed, 4 up-to-date) | ✓ PASS — independently re-run, not trusted from claim |
| Catalog-scoped tests run | `./gradlew test --tests "*.catalog.*"` | Completed without reported failures | ✓ PASS |
| Test enumeration: caching, filtering, credential-safety, degradation all have dedicated tests | grep of `fun \`...\`` across `DeckControllerTest.kt`, `MovieCatalogServiceTest.kt`, `MovieCatalogClientTest.kt`, `CatalogReferenceServiceTest.kt`, `SessionFiltersTest.kt` | 60+ distinct test names covering every ROADMAP criterion directly (e.g. "response body never contains the TMDB credential value", "increase the request count by exactly one total") | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| CTLG-01 | 03-01, 03-04, 03-05 | Deck sourced from TMDB (titles, posters, genres, streaming providers) | ✓ SATISFIED | `MovieCatalogService`/`MovieCatalogClient`/`DeckController`; providers resolved in 03-04, degradation in 03-05 |
| CTLG-02 | 03-01, 03-03 | Filter by genre | ✓ SATISFIED | `with_genres` param + genre reference cache + unknown-id rejection |
| CTLG-03 | 03-02, 03-03, 03-04 | Filter by streaming availability (region-aware) | ✓ SATISFIED | Session region/provider state (03-02), reference cache (03-03), deck filtering (03-04) |
| CTLG-04 | 03-01, 03-03, 03-04, 03-05 | TMDB responses cached server-side | ✓ SATISFIED | Deck cache TTL + reference-data TTL, both tested for zero-additional-request on repeat read |
| CTLG-05 | 03-01 | TMDB API key never reaches the frontend | ✓ SATISFIED | Bearer-header-only credential, explicit body/header leak tests pass. **Note:** `.planning/REQUIREMENTS.md` still shows CTLG-05 as an unchecked `[ ]` item and "Pending" in the traceability table (lines 24, 91) despite CTLG-01–04 being marked `[x]`/"Complete" — this is a documentation-tracking gap, not a functional gap; the implementation and test evidence are equivalent in strength to the other four. Recommend updating REQUIREMENTS.md's checkbox and traceability status for CTLG-05 to keep the trace accurate. |

**No orphaned requirements:** all five phase-3 requirement IDs (CTLG-01 through CTLG-05) are declared in at least one plan's frontmatter and are present in REQUIREMENTS.md.

### Anti-Patterns Found

None. Scanned all 36 files modified across the phase's commit range (`87b6307..f7dd475`) for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER`, placeholder-language strings, empty-body handlers, and hardcoded-empty stub patterns. Zero matches. The code is heavily and accurately self-documented with inline rationale tied to specific review findings (CR-01..04, WR-01..04) and design decisions (D-01..10).

### Human Verification Required

See frontmatter `human_verification` list. Summary:

1. **Live-TMDB real-data confirmation** (ROADMAP criterion 1's word "real") — open, WINDOWS.md #2. Every automated test runs against MockWebServer; no test in this phase calls the actual `api.themoviedb.org`.
2. **Live-TMDB sparse-filter confirmation** — open, WINDOWS.md #3.
3. **TMDB monetization-type omitted-parameter default (A1 assumption)** — open, WINDOWS.md #1.
4. **Connectivity-class failure retry/degradation (CR-04)** — no automated test exercises `java.io.IOException`-class failures; only HTTP 5xx is simulated. The fix report itself recommends human verification of this path.

All four items require a real `TMDB_API_TOKEN`, which was unavailable in the environment where this phase was executed and verified.

### Gaps Summary

No functional gaps block the phase goal. The implementation is thorough: every ROADMAP success criterion has direct, passing automated test coverage against a fake TMDB server, all 8 code-review findings (4 critical, 4 warning) were fixed with the fixes independently confirmed present in the current codebase (not just claimed), the full test suite passes (`BUILD SUCCESSFUL`, independently re-run), and no anti-patterns or stub code were found across any of the 36 files touched in this phase.

The phase is withheld from `passed` status solely because four items require live-TMDB confirmation that could not be performed in this environment (no `TMDB_API_TOKEN` available) — these are legitimately un-automatable per the plans' own design (`<human-check>` blocks written into 03-04-PLAN.md and 03-05-PLAN.md specifically because "every automated test in this phase runs against a local fake server and cannot establish this"). These are pre-existing open items in `.planning/WINDOWS.md` (ids 1-3), not newly discovered gaps.

One non-blocking documentation discrepancy: `.planning/REQUIREMENTS.md` has not been updated to reflect CTLG-05's completion (still shows unchecked/"Pending" while CTLG-01–04 show "Complete").

---

_Verified: 2026-09-04T18:07:29Z_
_Verifier: Claude (gsd-verifier)_
