---
phase: 03-tmdb-integration-catalog-caching
fixed_at: 2026-09-04T17:14:24Z
review_path: .planning/phases/03-tmdb-integration-catalog-caching/03-REVIEW.md
iteration: 1
findings_in_scope: 8
fixed: 8
skipped: 0
status: all_fixed
---

# Phase 03: Code Review Fix Report

**Fixed at:** 2026-09-04T17:14:24Z
**Source review:** .planning/phases/03-tmdb-integration-catalog-caching/03-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 8 (fix_scope: critical_warning — 4 Critical + 4 Warning; Info findings excluded)
- Fixed: 8
- Skipped: 0

**Verification environment:** All fixes were applied and verified inside an isolated git worktree
(`.claude/worktrees/rf-03-80275-1788541572`, branch `gsd-reviewfix/03-80275`), created per the
`workflow.use_worktrees` default (`true`). `./gradlew compileKotlin`/`compileTestKotlin` ran after
every edit; the targeted test classes for CR/WR fixes with test-observable behavior
(`MovieCatalogClientTest`, `DeckCacheRepositoryTest`, `MovieCatalogServiceTest`) and, finally, the
full `./gradlew test` suite ran clean (exit 0) before the worktree's commits were fast-forwarded
onto `main` and the worktree was torn down. The numbers below are reproducible from the current
`main` checkout — the worktree no longer exists.

## Fixed Issues

### CR-01: Deck cache key can exceed the `cache_key VARCHAR(128)` column, crashing every deck fetch for a large provider selection

**Files modified:** `src/main/kotlin/org/example/muvimatchr/catalog/CacheKey.kt`, `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt`
**Commit:** `6d24aab`
**Applied fix:** `buildDeckCacheKey` now hashes the sorted, comma-joined provider-id list (SHA-256
hex digest) instead of embedding the raw list, bounding the key length regardless of how many
providers are selected — the digest keeps the key well under the `VARCHAR(128)` limit even at
TMDB's largest real-world provider counts. Also wrapped the `upsertDeck` call in
`MovieCatalogService.getDeck` in a `try/catch (DataIntegrityViolationException)` so a caching
write failure degrades gracefully (the freshly-fetched deck is still returned to the caller)
instead of surfacing as an unhandled 500 after the TMDB call has already succeeded.

### CR-02: Session provider selection can exceed the `provider_ids VARCHAR(255)` column, and the failure is silently misdiagnosed as a join-code collision

**Files modified:** `src/main/kotlin/org/example/muvimatchr/session/SessionController.kt`, `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt`
**Commit:** `3779f83`
**Applied fix:** `SessionController.validateProviderIds` now rejects requests with more than 20
provider ids (400 Bad Request) — a single choke point that also protects the deck cache key
(CR-01). `SessionService.createSession`'s join-code retry loop now inspects
`e.mostSpecificCause.message` and only swallows `DataIntegrityViolationException` when it
originates from the `uq_session_join_code` constraint, rethrowing anything else so an oversized
provider list (or any other constraint violation) surfaces as its real cause instead of a
misleading "could not allocate a join code" error.

### CR-03: `GET /api/catalog/watch-providers` accepts an unvalidated `region` parameter, unlike every other region-accepting endpoint

**Files modified:** `src/main/kotlin/org/example/muvimatchr/catalog/CatalogReferenceController.kt`
**Commit:** `5e31b91`
**Applied fix:** Added `@Validated` at the class level and `@Pattern(regexp = "^[A-Z]{2}$")` on the
`region` request parameter of `watchProviders`, matching the constraint already enforced on
`CreateSessionRequest`/`SessionFiltersRequest`. An out-of-format region now fails fast with a 400
instead of reaching TMDB or the `region VARCHAR(2)` column. Existing tests already send `"DE"`, a
valid two-letter code, so no test changes were required.

### CR-04: Network-level TMDB failures bypass the retry filter and the documented degradation ladder entirely

**Files modified:** `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogClient.kt`
**Commit:** `3dd200b`
**Applied fix:** Broadened `withRetry()`'s filter to also retry `java.io.IOException` (the
supertype of `ConnectException`, Reactor Netty's `PrematureCloseException`/`ReadTimeoutException`,
etc.), not just `WebClientResponseException` with a 5xx/429 status. This routes genuine
connectivity failures through the same Reactor `retryWhen` cycle as HTTP error responses, so on
exhaustion they are wrapped the same way (`Exceptions.isRetryExhausted(e)` becomes true) and
correctly reach `MovieCatalogService.getDeck`'s existing stale-fallback/503 degradation path
instead of propagating as an unhandled exception.
**Note:** This is a retry/error-classification change with runtime-only observable effects
(connection failures are not exercised by the existing MockWebServer-based test suite, which only
simulates HTTP 5xx responses, per the review's own observation). Status: **fixed — recommend human
verification** of the retry-then-degrade behavior against a real connectivity failure (e.g. an
unreachable TMDB host) before considering this fully closed.

## Warnings

### WR-01: Reference-data refresh (genres/watch providers) has no fallback on TMDB failure, unlike the deck-cache path

**Files modified:** `src/main/kotlin/org/example/muvimatchr/catalog/CatalogReferenceService.kt`
**Commit:** `faedf04`
**Applied fix:** Wrapped the `fetchGenres()`/`fetchWatchProviders()` refresh calls in
`ensureGenresFresh()`/`ensureWatchProvidersFresh()` in `try/catch`, mirroring
`MovieCatalogService.getDeck`'s degradation design: on failure, if stale rows already exist
(`newest != null`), the exception is swallowed and the stale rows already in the table are served;
only a first-ever request with no existing rows rethrows.

### WR-02: `tmdbWebClient` has no configured connect/response timeout

**Files modified:** `src/main/kotlin/org/example/muvimatchr/catalog/CatalogWebClientConfig.kt`
**Commit:** `197a0df`
**Applied fix:** Built the `WebClient` bean with an explicit `ReactorClientHttpConnector` wrapping
a Reactor Netty `HttpClient` configured with a 5-second connect timeout
(`ChannelOption.CONNECT_TIMEOUT_MILLIS`) and a 10-second response timeout
(`responseTimeout(Duration.ofSeconds(10))`), so a connection that is accepted but never answered no
longer hangs the request indefinitely. `reactor-netty-http` was confirmed present transitively via
`spring-boot-starter-webclient`.

### WR-03: `tmdb.provider-lookup.max-concurrency` is used unvalidated and can deadlock every deck refresh if misconfigured

**Files modified:** `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt`
**Commit:** `5326f5c`
**Applied fix:** Added a Kotlin property setter on `providerLookupConcurrency` that clamps the
`@Value`-injected value to `coerceAtLeast(1)`, so a misconfigured `0` or negative value fails safe
(minimum concurrency of 1) instead of hanging every `Semaphore.withPermit` call indefinitely.

### WR-04: `DeckResult.fetchedAt` returned to the caller does not match the timestamp actually persisted

**Files modified:** `src/main/kotlin/org/example/muvimatchr/catalog/DeckCacheRepository.kt`, `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt`, `src/test/kotlin/org/example/muvimatchr/catalog/DeckCacheRepositoryTest.kt`
**Commit:** `8055f5b`
**Applied fix:** `DeckCacheRepository.upsertDeck` now takes `fetchedAt: Instant` as a bound
parameter (`:fetchedAt`) instead of evaluating SQL `now()` separately on both the insert and
conflict-update paths. `MovieCatalogService.getDeck` passes the same `Instant` it already computes
and returns in `DeckResult`, so the persisted row and the value handed back to the API caller are
now guaranteed identical. `DeckCacheRepositoryTest`'s four direct call sites were updated to pass
an `Instant.now()` argument to match the new signature (a required, mechanical follow-on to keep
the test suite compiling — not itself a review finding).

## Skipped Issues

None — all 8 in-scope findings (CR-01 through CR-04, WR-01 through WR-04) were fixed.

Info findings IN-01 (`GenreRepository.findByTmdbId` dead code) and IN-02 (no startup-time TMDB
credential check) were excluded per `fix_scope: critical_warning` and were not attempted.

---

_Fixed: 2026-09-04T17:14:24Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
