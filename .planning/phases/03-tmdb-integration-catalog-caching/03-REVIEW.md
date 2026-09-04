---
phase: 03-tmdb-integration-catalog-caching
reviewed: 2026-09-04T13:38:15Z
depth: standard
files_reviewed: 37
files_reviewed_list:
  - build.gradle.kts
  - src/main/kotlin/org/example/muvimatchr/catalog/CacheKey.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/CatalogReferenceController.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/CatalogReferenceService.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/CatalogWebClientConfig.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/DeckCacheEntry.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/DeckCacheRepository.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/Genre.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/GenreRepository.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogClient.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/RegionalAvailability.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/WatchProvider.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/WatchProviderRepository.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/tmdb/TmdbDiscoverResponse.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/tmdb/TmdbGenreListResponse.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/tmdb/TmdbMovie.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/tmdb/TmdbMovieWatchProvidersResponse.kt
  - src/main/kotlin/org/example/muvimatchr/catalog/tmdb/TmdbWatchProviderListResponse.kt
  - src/main/kotlin/org/example/muvimatchr/session/IntListConverter.kt
  - src/main/kotlin/org/example/muvimatchr/session/Session.kt
  - src/main/kotlin/org/example/muvimatchr/session/SessionController.kt
  - src/main/kotlin/org/example/muvimatchr/session/SessionService.kt
  - src/main/resources/application.properties
  - src/main/resources/db/migration/V5__create_deck_cache_entry.sql
  - src/main/resources/db/migration/V6__add_session_region_and_providers.sql
  - src/main/resources/db/migration/V7__create_catalog_reference_tables.sql
  - src/test/kotlin/org/example/muvimatchr/catalog/CatalogReferenceControllerTest.kt
  - src/test/kotlin/org/example/muvimatchr/catalog/CatalogReferenceServiceTest.kt
  - src/test/kotlin/org/example/muvimatchr/catalog/DeckCacheRepositoryTest.kt
  - src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt
  - src/test/kotlin/org/example/muvimatchr/catalog/MovieCatalogClientTest.kt
  - src/test/kotlin/org/example/muvimatchr/catalog/MovieCatalogServiceTest.kt
  - src/test/kotlin/org/example/muvimatchr/session/SessionFiltersTest.kt
  - src/test/kotlin/org/example/muvimatchr/session/SessionRepositoryTest.kt
  - src/test/kotlin/org/example/muvimatchr/session/SessionServiceTest.kt
  - src/test/kotlin/org/example/muvimatchr/support/TmdbMockServerSupport.kt
findings:
  critical: 4
  warning: 4
  info: 2
  total: 10
status: issues_found
---

# Phase 03: Code Review Report

**Reviewed:** 2026-09-04T13:38:15Z
**Depth:** standard
**Files Reviewed:** 37
**Status:** issues_found

## Summary

This phase adds TMDB catalog integration (genres, watch providers, per-title availability) and
deck caching on top of Phase 2's session model. The retry/degradation design for the deck path
(`MovieCatalogService.getDeck`) is thoughtfully documented and mostly correctly implemented, and
the cache-key/atomic-upsert pattern is sound for its intended concurrent-refresh scenario. However,
tracing the actual column widths, validation boundaries, and exception-handling paths surfaces four
BLOCKER-level defects: two independent ways a legitimately-validated provider selection can still
blow past a downstream `VARCHAR` column and crash the request (one of which is masked by an
overly-broad `catch` that reports a misleading error), one endpoint that skips the region-format
validation every sibling endpoint applies, and a gap in the retry filter that lets the exact
"TMDB is unreachable" scenario the phase was built to survive bypass the fallback path entirely.
None of these are hypothetical — each is reachable via valid, plausible production input (TMDB's
real per-region provider catalogues comfortably exceed the thresholds involved) and none of them
have test coverage.

## Critical Issues

### CR-01: Deck cache key can exceed the `cache_key VARCHAR(128)` column, crashing every deck fetch for a large provider selection

**File:** `src/main/kotlin/org/example/muvimatchr/catalog/CacheKey.kt:12-17`, `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt:39-98` (specifically the `upsertDeck` call at line 96), `src/main/resources/db/migration/V5__create_deck_cache_entry.sql:3`

**Issue:** `buildDeckCacheKey` concatenates the genre, every selected provider id (sorted, comma-joined), and the region into one string with no length cap. The column it's written to is `cache_key VARCHAR(128) NOT NULL` (enforced at the database, not just as a Hibernate annotation hint). `Session.providerIds` is stored in its own `VARCHAR(255)` column (`Session.kt:29`) and is only validated for positivity and provider-catalogue membership (`SessionController.validateProviderIds` / `CatalogReferenceService.requireKnownProviders`) — there is no upper bound on *how many* providers a session may select. TMDB's real provider catalogues for major regions (US, GB, DE, …) commonly contain 40-100+ entries; a session that selects even a few dozen valid provider ids (e.g. `"genre:none|provider:2,3,7,8,9,10,15,...|region:US"`) produces a cache key well past 128 characters.

When that happens, `DeckCacheRepository.upsertDeck` (a native `INSERT ... ON CONFLICT`) fails with a Postgres "value too long for type character varying(128)" error. This call sits **outside** the `try { runBlocking { ... } } catch (e: Exception) { ... }` block in `MovieCatalogService.getDeck` (lines 62-91 vs. line 96), so nothing catches it — the exception propagates to Spring's default handler and the request fails with an unhandled 500, *after* the TMDB call has already been made (the fetched data is simply discarded). Every subsequent request for that same filter combination repeats the same wasted TMDB call and 500.

**Fix:** Either bound the number of providers a session may select (reject `providerIds.size` above a sane limit, e.g. 20, in `SessionController.validateProviderIds`) or stop keying the cache on the raw id list — e.g. hash the sorted provider list into a fixed-width key (`genre:$genrePart|provider:${sha256(providerPart)}|region:$regionPart`) so the key is bounded regardless of selection size. Also widen the `try` in `MovieCatalogService.getDeck` (or add a dedicated catch) around `upsertDeck` so a write failure degrades gracefully instead of surfacing a raw 500:
```kotlin
val json = objectMapper.writeValueAsString(movies)
val fetchedAt = Instant.now()
try {
    deckCacheRepository.upsertDeck(UUID.randomUUID(), key, json, totalResults)
} catch (e: DataIntegrityViolationException) {
    // still return the freshly-fetched deck even if it couldn't be cached
}
return DeckResult(movies, totalResults, fetchedAt, stale = false)
```

### CR-02: Session provider selection can exceed the `provider_ids VARCHAR(255)` column, and the failure is silently misdiagnosed as a join-code collision

**File:** `src/main/kotlin/org/example/muvimatchr/session/Session.kt:28-30`, `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt:25-37`, `src/main/kotlin/org/example/muvimatchr/session/SessionController.kt:78-82`

**Issue:** Same root cause as CR-01 one layer up: `SessionController.validateProviderIds` only checks that every id is positive — it never bounds the *count* of ids, and `CatalogReferenceService.requireKnownProviders` only checks catalogue membership, not aggregate length. `IntListConverter` joins the list into a single comma-separated string for the `provider_ids VARCHAR(255)` column (`Session.kt:29`, `V6__add_session_region_and_providers.sql:2`). A session that selects roughly 50+ valid provider ids overflows that column.

In `SessionService.createSession` (lines 25-37), the write happens inside a `repeat(MAX_JOIN_CODE_ATTEMPTS)` loop whose *only* catch clause is:
```kotlin
} catch (e: DataIntegrityViolationException) {
    // collision on uq_session_join_code — retry with a new candidate
}
```
This catch is written to handle join-code uniqueness collisions, but `DataIntegrityViolationException` is also exactly what Postgres throws for the `provider_ids` length overflow. The loop has no way to distinguish the two causes, so an oversized-but-otherwise-valid provider selection is silently retried up to 10 times with the *same* oversized data (wasting 10 DB round trips) and then surfaces as `IllegalStateException("Could not allocate a unique join code after 10 attempts")` — a completely misleading error message that points a future debugger at the join-code generator instead of the actual cause.

In `SessionService.replaceFilters` (lines 44-50, used by `PUT /api/sessions/{id}/filters`), there is no catch at all — the same overflow simply propagates as an unhandled 500.

**Fix:** Bound `providerIds.size` in `SessionController.validateProviderIds` (this single fix also resolves CR-01), and narrow the retry loop's catch to only swallow the join-code constraint specifically (e.g. check the exception's constraint name, or check `e.cause` for `uq_session_join_code`), rethrowing anything else:
```kotlin
} catch (e: DataIntegrityViolationException) {
    if (e.mostSpecificCause.message?.contains("uq_session_join_code") != true) throw e
    // collision on uq_session_join_code — retry with a new candidate
}
```

### CR-03: `GET /api/catalog/watch-providers` accepts an unvalidated `region` parameter, unlike every other region-accepting endpoint

**File:** `src/main/kotlin/org/example/muvimatchr/catalog/CatalogReferenceController.kt:22-26`, compare `src/main/kotlin/org/example/muvimatchr/session/SessionController.kt:86,100` (`@field:Pattern(regexp = "^[A-Z]{2}$")`), `src/main/kotlin/org/example/muvimatchr/catalog/WatchProvider.kt:23` (`region VARCHAR(2)`)

**Issue:** `SessionController`'s `CreateSessionRequest`/`SessionFiltersRequest` both constrain `region` to `^[A-Z]{2}$` before it ever reaches `CatalogReferenceService`. `CatalogReferenceController.watchProviders`, however, takes `region: String` as a plain `@RequestParam` with no `@Pattern`, no length check, and the controller class has no `@Validated`. That string flows unmodified into `CatalogReferenceService.watchProviders(region)` → `ensureWatchProvidersFresh(region)` → `MovieCatalogClient.fetchWatchProviders(region)` (sent to TMDB as `watch_region`) and, if TMDB returns any rows for it, into `WatchProviderRepository.upsertWatchProvider(..., region, ...)`, which writes to a `region VARCHAR(2)` column.

Any authenticated participant can call this endpoint with an arbitrary `region` value (e.g. a 50-character string, or a region TMDB rejects outright). Either TMDB responds with a non-2xx status (propagating as an unhandled `WebClientResponseException` → 500) or, in the rarer case TMDB echoes back rows for it, the subsequent insert violates the 2-character column width (another unhandled `DataIntegrityViolationException` → 500). Either way this is a public, authenticated-only endpoint that a client can use to generate arbitrary uncontrolled 500s instead of the clean 400 every other region input path enforces.

**Fix:** Apply the same constraint used elsewhere, consistently:
```kotlin
@GetMapping("/watch-providers")
fun watchProviders(
    @RequestParam @Pattern(regexp = "^[A-Z]{2}$") region: String,
    @CurrentParticipant participant: Participant,
): List<WatchProviderResponse> = ...
```
(requires adding `@Validated` at the class level for method-parameter constraints to be enforced on a `@RestController`).

### CR-04: Network-level TMDB failures bypass the retry filter and the documented degradation ladder entirely

**File:** `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogClient.kt:92-101`, `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt:62-91`

**Issue:** `withRetry()`'s filter is:
```kotlin
.filter { throwable ->
    throwable is WebClientResponseException &&
        (throwable.statusCode.is5xxServerError || throwable.statusCode == HttpStatus.TOO_MANY_REQUESTS)
}
```
`WebClientResponseException` is only thrown when TMDB actually returns an HTTP response with an error status. A genuine connectivity failure — connection refused, DNS resolution failure, TLS handshake failure, or a response timeout with no bytes received — throws a different exception type (e.g. `java.net.ConnectException`, Reactor Netty's `PrematureCloseException`, or a `ReadTimeoutException`), none of which match this filter. Reactor's `retryWhen` therefore never retries these, and the original exception propagates directly out of `awaitSingle()`.

Back in `MovieCatalogService.getDeck`'s catch block (lines 75-91), the extensive comment describes a three-step "degradation ladder" whose entire premise is: *"Step 2: `reactor.core.Exceptions.isRetryExhausted(e)` is true only once the client's own retries are exhausted, and only then do we fall back to `existing`."* But `Exceptions.isRetryExhausted(e)` is **only** true for exceptions that actually went through a retry cycle and were exhausted — a connectivity failure that was never retried at all does not satisfy this check, so execution falls through to `throw e`, which is an unhandled exception → 500. This is precisely the "TMDB is temporarily unavailable" scenario the ladder exists to handle gracefully (serve stale cache, or a clean 503 if nothing is cached), yet the most common real-world manifestation of an outage — the upstream host being unreachable rather than returning 5xx — defeats it completely. Every existing test in `MovieCatalogServiceTest`/`DeckControllerTest` that exercises the outage path does so via MockWebServer returning HTTP 500 responses, never a connection-level failure, so this gap has no test coverage either.

**Fix:** Broaden the retry filter to also retry (or the catch to also treat as retry-exhausted-equivalent) connectivity-class exceptions, e.g.:
```kotlin
.filter { throwable ->
    (throwable is WebClientResponseException &&
        (throwable.statusCode.is5xxServerError || throwable.statusCode == HttpStatus.TOO_MANY_REQUESTS)) ||
        throwable is java.io.IOException
}
```
and/or, in `MovieCatalogService.getDeck`'s catch, treat any exception reaching that block (not just `Exceptions.isRetryExhausted`) as eligible for the stale-fallback/503 path, since by that point the client's retry policy has already had its chance:
```kotlin
} catch (e: Exception) {
    if (existing != null) {
        return DeckResult(deserializeMovies(existing), existing.totalResults, existing.fetchedAt, stale = true)
    }
    throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "...")
}
```

## Warnings

### WR-01: Reference-data refresh (genres/watch providers) has no fallback on TMDB failure, unlike the deck-cache path

**File:** `src/main/kotlin/org/example/muvimatchr/catalog/CatalogReferenceService.kt:52-79`

**Issue:** `ensureGenresFresh`/`ensureWatchProvidersFresh` call `runBlocking { movieCatalogClient.fetch...() }` with no `try`/`catch`. If the existing rows are stale (past `referenceTtlHours`) and TMDB is unreachable, the exception propagates straight out of `genres()`/`watchProviders()`/`requireKnownGenre()`/`requireKnownProviders()` uncaught — even though the (slightly stale but still useful) previous rows are still sitting in the table one line away in `genreRepository.findAll()`. This is inconsistent with `MovieCatalogService.getDeck`'s explicit "serve stale data on outage" design elsewhere in this same phase, and it means a TMDB blip can block session creation with any provider filter, or any genre-filtered deck request, even though perfectly serviceable cached reference data exists.

**Fix:** Wrap the refresh calls and fall back to existing (or empty) data on failure, mirroring the deck-cache pattern:
```kotlin
private fun ensureGenresFresh() {
    val newest = genreRepository.findNewestFetchedAt()
    if (newest == null || isStale(newest)) {
        try {
            val response = runBlocking { movieCatalogClient.fetchGenres() }
            response.genres.forEach { genreRepository.upsertGenre(UUID.randomUUID(), it.id, it.name) }
        } catch (e: Exception) {
            if (newest == null) throw e // nothing to fall back to
            // otherwise: serve the stale rows already in the table
        }
    }
}
```

### WR-02: `tmdbWebClient` has no configured connect/response timeout

**File:** `src/main/kotlin/org/example/muvimatchr/catalog/CatalogWebClientConfig.kt:19-23`

**Issue:** The `WebClient` bean is built with only `baseUrl` and the default header — no `clientConnector` with a bounded connect/read/response timeout. If TMDB accepts a connection but never responds, the `Mono` never completes, `awaitSingle()` (and the `runBlocking` wrapping it) blocks indefinitely, and the retry policy never even gets a chance to run (there's nothing to retry — the call simply hangs). Under load this can exhaust the request-handling thread pool.

**Fix:** Configure an explicit timeout on the underlying connector, e.g.:
```kotlin
val httpClient = HttpClient.create()
    .responseTimeout(Duration.ofSeconds(10))
builder.baseUrl(baseUrl)
    .clientConnector(ReactorClientHttpConnector(httpClient))
    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
    .build()
```

### WR-03: `tmdb.provider-lookup.max-concurrency` is used unvalidated and can deadlock every deck refresh if misconfigured

**File:** `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt:36-37,111-112`

**Issue:** `providerLookupConcurrency` is injected via `@Value` with a default of `8` and passed directly to `Semaphore(providerLookupConcurrency)`. `kotlinx.coroutines.sync.Semaphore` requires a positive permit count; a value of `0` (or a negative value) set in `application.properties`/environment either throws at construction or, depending on version behavior, causes every `withPermit` call to block forever, hanging every deck refresh that needs per-movie availability resolution (i.e. every refresh with a non-null region — the common case). There's no startup-time (`@PostConstruct`/`init`) validation catching a bad value early.

**Fix:** Validate the configured value at startup and fail fast, or clamp it to a safe minimum:
```kotlin
@Value("\${tmdb.provider-lookup.max-concurrency:8}")
private var providerLookupConcurrency: Int = 8
    set(value) { field = value.coerceAtLeast(1) }
```

### WR-04: `DeckResult.fetchedAt` returned to the caller does not match the timestamp actually persisted

**File:** `src/main/kotlin/org/example/muvimatchr/catalog/MovieCatalogService.kt:92-97`, `src/main/kotlin/org/example/muvimatchr/catalog/DeckCacheRepository.kt:20-28`

**Issue:** On a successful refresh, `getDeck` computes `val fetchedAt = Instant.now()` in the JVM and returns it in `DeckResult`, but the actual row written by `upsertDeck` sets `fetched_at = now()` inside the SQL statement — a separately-evaluated timestamp, milliseconds later. The value returned to the API caller is therefore never exactly the value stored (and re-read on the next cache hit). This is unlikely to cause a visible defect given millisecond granularity, but it's a latent inconsistency between "what we told the client" and "what we'll actually serve back to the next cache hit."

**Fix:** Either read back the persisted row's `fetched_at` after the upsert, or have `upsertDeck` accept the timestamp as a bound parameter (`:fetchedAt` in place of `now()`) so the same instant is used everywhere.

## Info

### IN-01: `GenreRepository.findByTmdbId` is dead code

**File:** `src/main/kotlin/org/example/muvimatchr/catalog/GenreRepository.kt:13`

**Issue:** This method is declared but never called anywhere in `src/main` or `src/test`. `existsByTmdbId` is used instead for the one place a lookup-by-tmdb-id is needed.

**Fix:** Remove it, or use it in place of `existsByTmdbId` where a null-check would also do (whichever is intended); as written it's unused surface area.

### IN-02: No startup-time check that the TMDB credential is actually configured

**File:** `src/main/resources/application.properties:10`, `src/main/kotlin/org/example/muvimatchr/catalog/CatalogWebClientConfig.kt:16-23`

**Issue:** `tmdb.api.read-access-token=${TMDB_API_TOKEN:}` defaults to an empty string when the environment variable is absent. A deployment that forgets to set `TMDB_API_TOKEN` starts up successfully and only fails at the first real TMDB call, with the `Authorization` header literally `"Bearer "` — a confusing failure mode to diagnose from a 401 alone.

**Fix:** Fail fast at startup if the token is blank, e.g. an `@PostConstruct` check in `CatalogWebClientConfig` that throws `IllegalStateException` when `token.isBlank()`.

---

_Reviewed: 2026-09-04T13:38:15Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
