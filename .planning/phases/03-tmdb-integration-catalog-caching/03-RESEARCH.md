# Phase 3: TMDB Integration & Catalog Caching - Research

**Researched:** 2026-09-03
**Domain:** Third-party JSON API integration (TMDB) + server-side caching, Kotlin/Spring Boot 4.1.1
**Confidence:** HIGH (TMDB endpoint shapes and Spring Boot 4.1 dependency coordinates verified directly against official docs and Maven Central; cache schema and retry tuning are design proposals, appropriately flagged)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Region for streaming-availability filtering is a field on `Session`, set by whoever creates the session. It's optional at creation — if not set, it defaults to Germany (`DE`). — **Reversibility:** reversible — adding a nullable/defaulted column to `Session` via a new Flyway migration has no impact on Phase 1/2 data.
- **D-02:** The region can be changed later by **any** participant in the session, not just the creator. This deliberately preserves Phase 2's decision that there's no host/creator role or special privileges (see `02-CONTEXT.md` D-03) — the app has no concept of "who created this session" gating any action today, and this phase does not introduce one. — **Reversibility:** costly — if a future phase decides region-editing should be creator-only, that requires introducing a creator-identity concept that doesn't exist anywhere else in the app yet, not just a permission check on this one field.
- Changing the region does not retroactively affect votes already cast in the session — that's a Phase 4 concern (vote/match logic), not this phase's.
- **D-03:** When no streaming-provider filter is applied, region is irrelevant to deck sourcing and the deck is TMDB's `/discover/movie` results filtered only by genre (if set), ordered by TMDB's own popularity/rating signal. No custom "what will this group like" logic is built — this explicitly avoids re-opening PROJECT.md's out-of-scope call on ML-based taste modeling/personalization. Streaming-provider filtering is opt-in, not a default always-on scope.
- **D-04:** On a TMDB call failure (slow, rate-limited, down) for a deck request: retry a few times with exponential backoff + jitter (per STACK.md's `WebClient` + `retryWhen` recommendation) first. If retries are exhausted, serve the existing cached deck for that filter combo even if it's past its freshness TTL. Only return an explicit error to the caller if there is no cached deck at all for that filter combo yet.
- **D-05:** A deck is one fixed page of TMDB `/discover/movie` results (~20 movies) per filter combination — no multi-page pre-fetching.
- **D-06:** If a filter combination (genre + provider + region) matches fewer than 5 movies, the deck endpoint returns an explicit "not enough movies, try broader filters" response instead of a thin deck. 5+ matches are returned as-is, uncapped beyond the single TMDB page.
- **D-07:** The deck cache is keyed by filter combination (genre, provider, region) and **shared across sessions** — two sessions requesting the same filters get the same cached deck rather than each maintaining its own copy. Matches ARCHITECTURE.md's recommendation to cache per filter combo, not per session. — **Reversibility:** costly — switching to per-session cache scoping later means re-keying the cache table/lookup and re-deriving whatever cache-hit assertions Phase 3's own tests rely on (success criterion 4 is "second request for same filters within TTL doesn't re-hit TMDB").
- **D-08:** A cached deck is considered fresh for a few hours (~6h) before a refetch from TMDB is triggered on next request. Genre and watch-provider *reference* lists (not the movie deck itself) are separately cached long-term per ARCHITECTURE.md/PITFALLS.md guidance, since those catalogs are effectively static for weeks — that reference-data TTL is Claude's discretion, not discussed as a separate decision here.
- **D-09** (added during this research pass, 2026-09-04): Provider selection is **multi-select, session-level** — same pattern as region (D-01/D-02). `Session` needs a providers field capable of holding zero, one, or multiple TMDB provider IDs, not a single nullable `providerId` column. TMDB's `with_watch_providers` param natively accepts a comma-separated ID list with OR semantics — pass the session's full provider selection through as one query param, no per-provider TMDB calls needed for filtering.
- **D-10** (added during this research pass, 2026-09-04): Each movie in the deck response includes its own resolved streaming-provider list (not filter-only) — see "Open Questions" resolution below; supersedes this document's original Open Question #1.

### Claude's Discretion

- Exact deck cache TTL number as a tunable config value beyond "~6h" — treat as adjustable, not hard-locked.
- Genre/watch-provider reference-data cache TTL and refresh mechanism (scheduled job vs lazy-on-miss).
- Exact Flyway migration shape for the new `Session.region` column (nullable with app-level default vs `NOT NULL DEFAULT 'DE'` at the DB level).
- HTTP client choice mechanics (`WebClient` vs Boot 3.2+ `RestClient`) and exact retry/backoff parameters (attempt count, base delay) — STACK.md already recommends `WebClient` + `retryWhen`, exact tuning is implementation detail.
- Cache table/entity shape (`CachedMovie`, genre, provider tables) — already sketched in ARCHITECTURE.md's package structure, not re-litigated here.
- Response shape for the "not enough movies" case (HTTP status code, JSON envelope) — implementation detail, not a product decision.

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope. (The "movie selection beyond streaming" question was explored and resolved directly within this phase — see D-03 — rather than deferred, since it was really asking how the *existing* genre+ranking scope should behave, not proposing new scope.)
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CTLG-01 | Deck is sourced from TMDB (titles, posters, genres, streaming providers) | `/discover/movie` request/response shape verified (Code Examples); note Open Question #1 on whether per-movie provider display requires additional `/movie/{id}/watch/providers` calls beyond filtering |
| CTLG-02 | Participant can filter the deck by genre | `with_genres` param verified against official docs; `MovieCatalogClient.discoverMovies` example shows exact query-param construction |
| CTLG-03 | Participant can filter the deck by streaming availability (region-aware) | `with_watch_providers` + `watch_region` params verified; Pitfall 1 flags the one genuine doc gap (monetization-type default) as a pre-implementation confirmation checkpoint |
| CTLG-04 | TMDB responses are cached server-side (not re-fetched per deck load) | Cache Table Design (Pattern 1), Architecture Diagram, and Validation Architecture's CTLG-04 test row (call-count assertion against a mocked TMDB server) |
| CTLG-05 | TMDB API key never reaches the frontend (backend-proxied) | Pattern 2 (Bearer-token `WebClient` bean, server-side only), Security Domain V13/V14, and Validation Architecture's CTLG-05 test row (`DeckControllerTest` asserting no token in response) |
</phase_requirements>

## Summary

This phase adds a `catalog/` package that calls TMDB's `/discover/movie`, `/genre/movie/list`, and `/watch/providers/movie` endpoints server-side, caches the results in Postgres, and exposes a deck-fetch endpoint to (a future) frontend. All exact TMDB request/response shapes below were fetched directly from `developer.themoviedb.org` this session, not from training memory or forum posts. The single most important non-obvious finding: **Spring Boot 4.1.1 ships a purpose-built `spring-boot-starter-webclient` artifact** (distinct from the full `spring-boot-starter-webflux` STACK.md originally pointed at) that provides `WebClient.Builder` auto-configuration for outbound-only HTTP calls without pulling in a reactive server stack — confirmed to exist at the project's exact Boot version (4.1.1) via direct Maven Central metadata lookup. Use this instead of `spring-boot-starter-webflux`.

The TMDB rate-limit picture is confirmed via official docs: hard rate limiting was disabled in 2019; current guidance is a soft ~40 req/s ceiling with `429` as the only enforcement signal — exactly matching STACK.md/ARCHITECTURE.md's existing (already-HIGH-confidence) claim. The image-CDN "20 simultaneous connections per IP" figure, however, could **not** be found in official docs during this session (the `image-basics` doc page is silent on it) — it remains sourced only from TMDB's community support forum, so it stays at the same confidence tier PITFALLS.md already assigned it (community-forum, not official docs). This is a genuine absence, not a confirmation gap: I checked the page that would carry this guidance if it existed, and it doesn't.

**Primary recommendation:** Add `spring-boot-starter-webclient:4.1.1` (not `-webflux`) + `kotlinx-coroutines-reactor:1.11.0` to `build.gradle.kts`; build `MovieCatalogClient` as a suspend-function wrapper around a `WebClient` bean configured with `Authorization: Bearer` auth and `Retry.backoff` on `WebClientResponseException` subtypes for 429/5xx; cache the deck as one JSONB row per filter-combo cache key with a `fetched_at` timestamp, and reference data (genres, watch providers) as separate long-TTL tables.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| TMDB deck fetch (genre/provider/region filter, popularity sort) | API/Backend (`catalog/` package) | Database/Storage (cache tables) | TMDB credentials must never leave the backend (CTLG-05); backend owns the outbound HTTP call and translates TMDB's JSON into the app's own DTOs (ARCHITECTURE.md Anti-Pattern 3) |
| Deck/reference-data caching (TTL, shared-across-sessions cache-combo lookup) | Database/Storage | API/Backend (cache-read/write logic) | Cache rows are the durable, restart-survivable record (per this project's own persistence-first architecture rule); the service layer only decides freshness, it does not hold the cache in memory |
| Region selection & storage (`Session.region`) | Database/Storage (`session` table column) | API/Backend (`SessionService`) | Region is session-scoped persisted state per D-01/D-02, not a per-request client parameter — it must survive restart same as other Session fields |
| Deck-fetch endpoint exposure | API/Backend | — | REST endpoint under existing `/api/sessions/...` prefix; no frontend or CDN tier involved in this phase (Phase 6 consumes it later) |
| TMDB outage degradation (retry, serve-stale-cache) | API/Backend (`MovieCatalogClient`/`MovieCatalogService`) | Database/Storage (stale cache is still a DB read) | Retry/backoff is a client-tier concern; "serve what's cached" is a storage-tier fallback the service layer orchestrates (D-04) |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-boot-starter-webclient` | 4.1.1 | Outbound reactive HTTP client for TMDB calls | `[VERIFIED: repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-webclient/maven-metadata.xml]` — this exact version exists on Maven Central and matches the project's current Boot version. Its POM (fetched directly) declares `spring-boot-starter`, `spring-boot-starter-jackson`, `spring-boot-reactor`, `spring-boot-webclient`, `reactor-netty-http:1.3.7` — a client-only dependency set, not the full `spring-boot-starter-webflux` reactive-server stack. `[VERIFIED: repo1.maven.org .../spring-boot-starter-webclient/4.1.1/spring-boot-starter-webclient-4.1.1.pom]` |
| `kotlinx-coroutines-reactor` | 1.11.0 | `.awaitBody<T>()` / suspend-function bridge from `WebClient`'s reactive `Mono` to idiomatic Kotlin coroutines | `[VERIFIED: repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-reactor/maven-metadata.xml]` — current latest/release version confirmed directly against Maven Central. Already recommended in STACK.md; version bumped here from that research's unspecified "latest" placeholder to a concrete, dated-current number. |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `com.squareup.okhttp3:mockwebserver3` | 5.5.0 | Fake HTTP server for testing `MovieCatalogClient`'s retry/backoff/timeout behavior without hitting real TMDB | `[VERIFIED: repo1.maven.org/maven2/com/squareup/okhttp3/mockwebserver3/maven-metadata.xml]`. Needed because this dev environment has no `TMDB_API_TOKEN` set (see Environment Availability) — retry/backoff and "serve stale cache on failure" (D-04) cannot be integration-tested against the real API without a live key, and even with a key you don't want tests depending on live TMDB uptime/rate limits. |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `spring-boot-starter-webclient` | `spring-boot-starter-webflux` (STACK.md's original pick) | Both work — `-webflux` also transitively pulls in `spring-boot-webclient`/`spring-webflux`, but additionally configures a reactive embedded server (Netty as a *server*), which this app never uses (it's Spring MVC + Servlet, not reactive-end-to-end). `-webclient` is the narrower, purpose-built starter Boot 4 introduced specifically for "I only need an outbound reactive HTTP client" — less unused surface, same `WebClient.Builder` auto-config. |
| `WebClient` (reactive) | `RestClient` (Boot 3.2+ synchronous client, `spring-boot-starter-restclient` in Boot 4) | `RestClient` is blocking/synchronous — simpler mental model, no coroutines bridge needed, and Boot 4.1.1 has a confirmed, dedicated `spring-boot-starter-restclient:4.1.1` `[VERIFIED: repo1.maven.org .../spring-boot-starter-restclient/maven-metadata.xml]`. STACK.md already chose `WebClient` for its native `retryWhen`/backoff ergonomics (Reactor's `Retry.backoff` is a first-class, well-documented API); `RestClient` retries would need a manual loop or a wrapper like Spring Retry's `@Retryable`. Since this phase's core risk (D-04's retry-then-serve-stale behavior) is exactly what `WebClient` + `retryWhen` is built for, staying with `WebClient` is still the better fit — flagging `RestClient` here only because Boot 4.1 makes it an equally "native," non-deprecated option if a future maintainer prefers synchronous code. |
| JSONB deck-cache row per filter combo (this research's proposal) | Normalized `cached_movie` + `deck_cache_movie` join table | Normalized rows let a single movie be deduplicated across filter combos and queried individually later (e.g. Phase 4 vote-by-movie-id lookups). JSONB is simpler to implement and matches D-07's cache semantics exactly (one row = one filter combo's frozen TMDB page) — recommended for this phase; a normalized `cached_movie` table can still exist for reference/dedup purposes without changing the deck-cache's freshness/TTL logic. See Cache Table Design below. |

**Installation:**
```bash
# build.gradle.kts additions
implementation("org.springframework.boot:spring-boot-starter-webclient")   // NOT spring-boot-starter-webflux
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.11.0")

testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
```

**Version verification:** All four version numbers above (`spring-boot-starter-webclient` matches Boot's managed version so no explicit version string is needed in `build.gradle.kts` — `io.spring.dependency-management` already pins it to 4.1.1 project-wide; `kotlinx-coroutines-reactor:1.11.0` and `mockwebserver3:5.5.0` need explicit versions since they aren't Spring-Boot-managed) were confirmed this session via direct `curl` against `repo1.maven.org` `maven-metadata.xml` files — not `npm view`/`pip index` since this is a Gradle/Maven-Central project; the ecosystem-appropriate equivalent was used.

## Package Legitimacy Audit

> The `gsd-tools package-legitimacy check` seam only supports `npm|pypi|crates` ecosystems; this is a Gradle/Maven project, so that seam does not apply. In its place, every package recommended above was independently verified by fetching its `maven-metadata.xml` and/or POM directly from `repo1.maven.org` (Maven Central's canonical host) — a stronger check than a registry-existence probe, since it also confirms the exact version and (for `spring-boot-starter-webclient`) the declared dependency tree.

| Package | Registry | Verified Via | Verdict | Disposition |
|---------|----------|--------------|---------|-------------|
| `org.springframework.boot:spring-boot-starter-webclient` | Maven Central | `curl repo1.maven.org/.../maven-metadata.xml` + POM fetch, confirms 4.1.1 exists, first-party Spring org | OK | Approved |
| `org.jetbrains.kotlinx:kotlinx-coroutines-reactor` | Maven Central | `curl repo1.maven.org/.../maven-metadata.xml`, first-party JetBrains org, already used transitively in this ecosystem | OK | Approved |
| `com.squareup.okhttp3:mockwebserver3` | Maven Central | `curl repo1.maven.org/.../maven-metadata.xml`, first-party Square org, widely-used test tool (test-scope only, never ships to production) | OK | Approved |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### System Architecture Diagram

```
Deck-fetch request (from a session, carrying optional genre/provider filters)
    │
    ▼
DeckController  (new: catalog/DeckController.kt, under existing /api/sessions/{id}/... prefix)
    │  resolves Session.region + Session.providers (multi-select, D-09) from DB
    ▼
MovieCatalogService.getDeck(genreId?, providerIds: List<Int>, region)
    │
    ├─► 1. Build cache key from (genreId, providerIds sorted+joined, region)
    │
    ├─► 2. DeckCacheRepository.findByKey(cacheKey)
    │        │
    │        ├─ HIT + fresh (< 6h old)  ──► return cached movies (incl. per-movie providers, D-10), SKIP TMDB calls
    │        │
    │        ├─ HIT + stale (≥ 6h old)  ──► attempt refresh (steps 3-3b); on TMDB failure, serve this stale row (D-04)
    │        │
    │        └─ MISS                     ──► attempt refresh (steps 3-3b); on TMDB failure with no row at all, return error (D-04)
    │
    ├─► 3. MovieCatalogClient.discoverMovies(genreId?, providerIds, region)
    │        │  Authorization: Bearer <token>, GET /3/discover/movie?with_genres=..&with_watch_providers=<comma-joined ids>&watch_region=..&sort_by=popularity.desc
    │        │  wrapped in retryWhen(Retry.backoff(...).filter { it is WebClientResponseException && (it.statusCode.is5xxServerError || it.statusCode == TOO_MANY_REQUESTS) })
    │        ▼
    │      TMDB /discover/movie  ──► JSON { page, results: [...], total_results, total_pages }
    │
    ├─► 3b. (D-10) For each of the up to ~20 movies in the discover result: MovieCatalogClient.fetchWatchProviders(movieId)
    │        │  GET /3/movie/{id}/watch/providers — same retry/backoff wrapper as step 3, per movie
    │        ▼
    │      Resolve each movie's regional provider list (flatrate/rent/buy/ads for Session.region) and attach to the movie record before caching
    │
    ├─► 4. if total_results < 5 (D-06) ──► return "not enough movies" response (no cache write, or write a marker row — planner's call)
    │
    ├─► 5. DeckCacheRepository.upsert(cacheKey, movies JSONB [incl. per-movie providers], fetched_at=now())
    │
    └─► 6. return deck to caller
```

### Recommended Project Structure

```
src/main/kotlin/org/example/muvimatchr/catalog/
├── DeckController.kt          # REST: GET /api/sessions/{sessionId}/deck?genre=&provider=
├── MovieCatalogClient.kt      # WebClient wrapper: discoverMovies(), fetchGenres(), fetchWatchProviders()
├── MovieCatalogService.kt     # cache-aware deck-building logic; owns the D-04/D-06 fallback rules
├── CatalogWebClientConfig.kt  # @Bean WebClient (base URL, Bearer auth header, timeouts)
├── DeckCacheEntry.kt          # JPA entity: cache_key, movies (JSONB), fetched_at
├── DeckCacheRepository.kt     # Spring Data JPA repository, native upsert query
├── Genre.kt / GenreRepository.kt                  # long-TTL reference cache
├── WatchProvider.kt / WatchProviderRepository.kt   # long-TTL reference cache
└── tmdb/
    ├── TmdbDiscoverResponse.kt   # page, results, total_results, total_pages
    ├── TmdbMovie.kt              # id, title, poster_path, genre_ids, vote_average, popularity, release_date
    ├── TmdbGenreListResponse.kt  # genres: [{ id, name }]
    └── TmdbWatchProviderResponse.kt  # results: [{ provider_id, provider_name, logo_path, display_priority }]
```

### Pattern 1: Filter-combo cache key as a single string column, not a NULL-tolerant composite unique constraint

**What:** Compute a deterministic string cache key from the filter combo, e.g. `"genre:28|provider:8|region:DE"`, using a fixed literal (`"none"`) for absent filters, and put a `UNIQUE` index on that single column — rather than a composite `UNIQUE (genre_id, provider_id, region)` constraint.
**When to use:** Whenever "no filter" must be treated as one single, reusable cache bucket per D-07/D-08.
**Why:** Postgres `UNIQUE` constraints treat `NULL` as distinct from every other `NULL` (multiple rows with `genre_id = NULL` are all allowed to coexist), so a naive nullable composite unique constraint would let duplicate "no genre filter" cache rows accumulate instead of being upserted into one row. A single non-null string key sidesteps this entirely and is trivial to build/parse in Kotlin.
**Example (updated for D-09 multi-provider selection — providers is a list, not a single nullable id; sort before joining so the same set always produces the same key regardless of selection order):**
```kotlin
// catalog/CacheKey.kt
fun buildDeckCacheKey(genreId: Int?, providerIds: List<Int>, region: String?): String {
    val providerPart = if (providerIds.isEmpty()) "none" else providerIds.sorted().joinToString(",")
    val regionPart = if (providerIds.isEmpty()) "none" else (region ?: "none") // region irrelevant with no provider filter, per D-03
    return "genre:${genreId ?: "none"}|provider:$providerPart|region:$regionPart"
}
```
```sql
-- Flyway migration (next number after V4, see "Codebase Specifics" below)
CREATE TABLE deck_cache_entry (
    id UUID PRIMARY KEY,
    cache_key VARCHAR(128) NOT NULL,
    movies JSONB NOT NULL,
    total_results INT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_deck_cache_entry_key ON deck_cache_entry (cache_key);
```

### Pattern 2: Bearer-token WebClient bean, TMDB base URL and token from config

**What:** One `@Bean WebClient` configured once with `baseUrl("https://api.themoviedb.org/3")` and a default `Authorization: Bearer` header, injected wherever `MovieCatalogClient` needs it.
**Source:** `[VERIFIED: developer.themoviedb.org/docs/authentication-application]` — fetched directly this session. Exact header: `Authorization: Bearer <access_token>` (the *Read Access Token* from TMDB's API settings page, not the shorter legacy `api_key`).
```kotlin
// catalog/CatalogWebClientConfig.kt
@Configuration
class CatalogWebClientConfig(
    @Value("\${tmdb.api.base-url}") private val baseUrl: String,
    @Value("\${tmdb.api.read-access-token}") private val token: String,
) {
    @Bean
    fun tmdbWebClient(builder: WebClient.Builder): WebClient =
        builder.baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .build()
}
```
```properties
# application.properties additions
tmdb.api.base-url=https://api.themoviedb.org/3
tmdb.api.read-access-token=${TMDB_API_TOKEN}
tmdb.cache.deck-ttl-hours=6
```
`[VERIFIED: current application.properties, read this session — file contains only spring.application.name, spring.datasource.*, spring.jpa.hibernate.ddl-auto=validate, spring.flyway.enabled=true; no TMDB config present yet]`

### Pattern 3: Retry with exponential backoff + jitter, filtered to retryable statuses only

**What:** `Retry.backoff(maxAttempts, minBackoff)` with `.jitter(...)`, `.maxBackoff(...)`, and `.filter(...)` scoping retries to 429/5xx only — never retrying a 4xx client error (bad request params, auth failure), which would just fail identically on every attempt.
**Source:** `[VERIFIED: projectreactor.io/docs/core/release/reference/coreFeatures/error-handling.html]`, fetched this session — confirms `Retry.backoff(long, Duration)` factory plus fluent `.jitter(double)`/`.maxBackoff(Duration)`/`.filter(Predicate)`.
```kotlin
// catalog/MovieCatalogClient.kt
suspend fun discoverMovies(genreId: Int?, providerIds: List<Int>, region: String?): TmdbDiscoverResponse =
    webClient.get()
        .uri { uriBuilder ->
            uriBuilder.path("/discover/movie")
                .queryParam("sort_by", "popularity.desc")
                .apply { genreId?.let { queryParam("with_genres", it) } }
                .apply {
                    // D-09: providerIds is the session's full multi-select; TMDB's with_watch_providers
                    // takes a comma-separated list natively (OR semantics) — one query param, no per-provider calls.
                    if (providerIds.isNotEmpty()) {
                        queryParam("with_watch_providers", providerIds.joinToString(","))
                        queryParam("watch_region", region)
                    }
                }
                .build()
        }
        .retrieve()
        .bodyToMono(TmdbDiscoverResponse::class.java)
        .retryWhen(
            Retry.backoff(3, Duration.ofMillis(500))
                .maxBackoff(Duration.ofSeconds(5))
                .jitter(0.5)
                .filter { it is WebClientResponseException &&
                    (it.statusCode.is5xxServerError || it.statusCode == HttpStatus.TOO_MANY_REQUESTS) }
        )
        .awaitSingle()
```
Attempt count (3) and base delay (500ms) are tunable per CONTEXT.md's "Claude's Discretion" note — not locked values.

### Anti-Patterns to Avoid

- **Passing `with_watch_providers` without `watch_region`:** TMDB's own docs pair these two parameters explicitly ("use in conjunction with `watch_region`") `[VERIFIED: developer.themoviedb.org/reference/discover-movie]`. D-03 already encodes the correct behavior (region is irrelevant *only* when no provider filter is set) — don't accidentally send a provider filter with a null/blank region string.
- **Storing full TMDB image URLs in the cache:** store only `poster_path` (e.g. `/abc123.jpg`) as TMDB returns it, not a pre-built `https://image.tmdb.org/t/p/w500/abc123.jpg` string. The base URL and available sizes come from `/configuration` (`images.secure_base_url`, `poster_sizes: [w92, w154, w185, w342, w500, w780, original]` `[VERIFIED: developer.themoviedb.org/reference/configuration-details]`) and should be composed at the point of use (Phase 6, frontend) — hardcoding a size now means a schema/data change later if the UI wants a different size.
- **Re-fetching genre/watch-provider reference lists on every deck request:** these change rarely; ARCHITECTURE.md Anti-Pattern 3 already flags this — cache them in their own long-TTL tables (`Genre`, `WatchProvider`), refreshed lazily-on-miss or via a scheduled job (both are Claude's Discretion per CONTEXT.md — a lazy-on-miss check is simpler to implement correctly in this phase and needs no scheduler dependency).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Exponential backoff + jitter retry loop | A manual `for` loop with `Thread.sleep`/`delay()` and manually-computed backoff intervals | Reactor's `Retry.backoff(...)` | Battle-tested, handles jitter/max-backoff/attempt-exhaustion callbacks correctly; a hand-rolled loop is exactly the kind of "looks done but isn't" logic that's easy to get subtly wrong (e.g. forgetting jitter, causing thundering-herd retries) |
| TMDB → app DTO mapping | Passing TMDB's raw `TmdbMovie` JSON DTO straight to the deck-cache JSONB column or the controller response | A translation step (even a thin one) that maps into the app's own response shape | ARCHITECTURE.md Anti-Pattern 3 — couples the whole app to TMDB's exact field names; if TMDB adds/renames a field, only the `tmdb/` DTO package should need to change |
| Cache-key uniqueness for nullable filter combos | Composite `UNIQUE (genre_id, provider_id, region)` with nullable columns | Single non-null string cache key (Pattern 1 above) | Postgres treats `NULL <> NULL` in unique constraints — a naive composite key silently allows duplicate "no filter" rows |

**Key insight:** Every "don't hand-roll" here maps to a specific already-happened failure mode in this exact codebase or a specific documented TMDB/Postgres gotcha — none of these are generic caution, they're targeted at concrete risks in this phase's scope.

## Runtime State Inventory

**N/A — not a rename/refactor/migration phase.** This phase adds new tables and a new package; it does not rename or move existing runtime state. Skipped per the trigger condition in the verification protocol.

## Common Pitfalls

### Pitfall 1: `with_watch_providers` behavior when `with_watch_monetization_types` is omitted is undocumented

**What goes wrong:** A deck request filtered by provider (D-06's "provider + region" combo) might silently include/exclude rent/buy/free listings depending on an undocumented default, producing a deck that doesn't match what a user expects "available on Netflix" to mean.
**Why it happens:** `[ASSUMED — official docs checked this session and are silent on the default]` `developer.themoviedb.org/reference/discover-movie` documents `with_watch_monetization_types` as a separate, optional parameter (`flatrate, free, ads, rent, buy`) but does not state what happens when `with_watch_providers` is supplied without it. This is a genuine documentation gap, not a claim I'm downgrading — the page was fetched and re-checked specifically for this.
**How to avoid:** Recommend defaulting to **not** sending `with_watch_monetization_types` at all (broadest match — TMDB's own examples pass `with_watch_providers` alone in most tutorials) and treat this as a confirmation checkpoint: verify actual behavior against a live TMDB call during implementation (the dev machine currently has no `TMDB_API_TOKEN` — see Environment Availability — so this must be confirmed once a token is obtained, before relying on it for CTLG-03's acceptance criterion).
**Warning signs:** Deck results for a provider filter include movies only available to rent/buy when the product intent (implied by "streaming availability") is subscription (`flatrate`) access.

### Pitfall 2: Boot 4.1.0→4.1.1 auto-configuration churn around `RestClient.Builder`/`WebClient.Builder`

**What goes wrong:** A `NoSuchBeanDefinitionException` for `WebClient.Builder` (or `RestClient.Builder`) at startup, even though the relevant starter is on the classpath.
**Why it happens:** `[VERIFIED: github.com/spring-projects/spring-boot/issues/50768]` — a real, reported issue where `RestClient.Builder` stopped being auto-configured between Boot 4.0.6 and 4.1.0 for some starter combinations, tied to Boot 4's modularization of `RestClientAutoConfiguration`/`WebClientAutoConfiguration` into their own packages (`org.springframework.boot.restclient.autoconfigure` / `org.springframework.boot.webclient.autoconfigure`, confirmed via the Boot 4.1.1 API docs). This project already hit an analogous Boot-4-modularization surprise in Phase 2 (`@AutoConfigureMockMvc` moving to `spring-boot-webmvc-test` — see STATE.md).
**How to avoid:** Add `spring-boot-starter-webclient` explicitly (not relying on it being pulled in transitively by something else), and if `WebClient.Builder` injection fails at startup, check that this starter — not just `spring-webflux` alone — is present.
**Warning signs:** Startup failure mentioning `WebClient$Builder` or `RestClient$Builder` bean not found, despite `implementation("org.springframework.boot:spring-boot-starter-webflux")` being present (STACK.md's original, more generic recommendation) — the fix is the more specific `-webclient` starter.

### Pitfall 3: Deck cache TTL check racing a concurrent refresh

**What goes wrong:** Two near-simultaneous deck requests for the same stale filter combo both see "stale," both call TMDB, both attempt to write the cache row — a native `INSERT ... ON CONFLICT DO UPDATE` upsert (same pattern as `VoteRepository.upsertVote`, already established in this codebase) handles this safely, but a naive "find, then insert-or-update in two steps" would not.
**Why it happens:** This is the same class of bug PITFALLS.md's Pitfall 2 already documents for vote completion — check-then-act without a single atomic statement.
**How to avoid:** Use a native `@Modifying @Query` upsert for `DeckCacheRepository`, mirroring `VoteRepository.upsertVote`'s existing pattern in this codebase `[VERIFIED: ARCHITECTURE.md Pattern 2, matches this project's established convention]`.
**Warning signs:** Duplicate `deck_cache_entry` rows for the same `cache_key`, or a `DataIntegrityViolationException` under concurrent load if the upsert isn't atomic.

## Code Examples

### `/discover/movie` request (verified exact shape)
```
GET https://api.themoviedb.org/3/discover/movie?with_genres=28&with_watch_providers=8&watch_region=DE&sort_by=popularity.desc&page=1
Authorization: Bearer <TMDB_READ_ACCESS_TOKEN>
```
`[VERIFIED: developer.themoviedb.org/reference/discover-movie, fetched this session]`

Response (fields relevant to this phase):
```json
{
  "page": 1,
  "results": [
    {
      "id": 550,
      "title": "Fight Club",
      "poster_path": "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg",
      "genre_ids": [18, 53],
      "vote_average": 8.4,
      "popularity": 61.4,
      "release_date": "1999-10-15",
      "overview": "...",
      "backdrop_path": "/..."
    }
  ],
  "total_pages": 500,
  "total_results": 10000
}
```

### `/genre/movie/list` (reference data, cached long-term)
```
GET https://api.themoviedb.org/3/genre/movie/list?language=en
Authorization: Bearer <TMDB_READ_ACCESS_TOKEN>
```
```json
{ "genres": [ { "id": 28, "name": "Action" } ] }
```
`[VERIFIED: developer.themoviedb.org/reference/genre-movie-list, fetched this session]`

### `/watch/providers/movie` (reference data, cached long-term)
```
GET https://api.themoviedb.org/3/watch/providers/movie?watch_region=DE
Authorization: Bearer <TMDB_READ_ACCESS_TOKEN>
```
```json
{
  "results": [
    {
      "provider_id": 8,
      "provider_name": "Netflix",
      "logo_path": "/....jpg",
      "display_priority": 2,
      "display_priorities": { "DE": 1, "US": 4 }
    }
  ]
}
```
`[VERIFIED: developer.themoviedb.org/reference/watch-providers-movie-list, fetched this session]`

### `/movie/{id}/watch/providers` (per-title availability — used if per-title lookup is ever needed beyond the discover-page deck)
```
GET https://api.themoviedb.org/3/movie/550/watch/providers
Authorization: Bearer <TMDB_READ_ACCESS_TOKEN>
```
```json
{
  "id": 550,
  "results": {
    "DE": {
      "link": "https://www.themoviedb.org/movie/550-fight-club/watch?locale=DE",
      "flatrate": [
        { "logo_path": "/....jpg", "provider_id": 8, "provider_name": "Netflix", "display_priority": 1 }
      ],
      "rent": [ ], "buy": [ ], "ads": [ ]
    }
  }
}
```
`[VERIFIED: developer.themoviedb.org/reference/movie-watch-providers, fetched this session]`. Note: `/discover/movie` results do **not** include watch-provider data inline — provider filtering happens server-side via `with_watch_providers`, but if the deck response needs to *display* "available on X" per movie (beyond just filtering), a separate per-movie `/watch/providers` call (or a batch strategy) is needed. This phase's success criteria (per ROADMAP) only require *filtering* by provider, not displaying provider badges per card — confirm with the planner whether per-movie provider display is in scope before adding N+1 per-movie calls to the deck-fetch path.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| TMDB hard rate limit (40 req/10s) | Soft, undocumented ceiling (~40 req/s), enforced via `429` only | 2019-12-16 `[VERIFIED: developer.themoviedb.org/docs/rate-limiting]` | Confirms STACK.md/ARCHITECTURE.md's existing claim was already correct and current — no change needed to those documents' guidance, just re-confirmed against the live official page this session. |
| `spring-boot-starter-webflux` as "the" way to get `WebClient` in a non-reactive-server app | `spring-boot-starter-webclient` — a dedicated, narrower starter | Introduced in Boot 4.0 line (present at 4.0.0 through 4.1.1, confirmed via Maven Central version list) | STACK.md predates this project's confirmed 4.1.1 pin and recommended the older, broader `-webflux` starter; this research supersedes that one line item with the narrower starter. |

**Deprecated/outdated:**
- `RestTemplate` — still fully deprecated for new code; not used anywhere in this recommendation (already excluded by STACK.md/PITFALLS.md, re-confirmed).
- Passing `api_key` as a query parameter on every TMDB call — TMDB's own docs present Bearer-token auth as the default in current examples; query-param `api_key` still works but leaks into logs/URLs (STACK.md already flags this; not contradicted by anything found this session).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `with_watch_providers` without `with_watch_monetization_types` returns the broadest match (not scoped to `flatrate` only) | Common Pitfalls #1 | CTLG-03's "streaming availability" filter could silently include rent/buy titles a user wouldn't consider "available," or exclude subscription titles if TMDB actually defaults narrower than assumed — must be confirmed against a live call once `TMDB_API_TOKEN` is available, before this phase's success criterion 3 is verified |
| A2 | Recommended `retryWhen` tuning (3 attempts, 500ms base delay, 5s max backoff, 0.5 jitter) is reasonable for this app's scale | Pattern 3 / Code Examples | Low risk — CONTEXT.md explicitly marks exact retry parameters as Claude's Discretion; wrong tuning only affects latency-under-failure, not correctness |
| A3 | JSONB single-row-per-filter-combo cache design (vs. normalized `cached_movie` + join table) is the better fit for this phase | Alternatives Considered, Pattern 1 | Low-medium risk — CONTEXT.md marks cache table shape as Claude's Discretion; if a later phase needs per-movie querying independent of a filter combo (e.g. Phase 4 looking up a specific `movieId` by TMDB id), the JSONB design would need a follow-up migration to also maintain a normalized `cached_movie` table — flagged here so the planner can decide up front whether to build both now |
| A4 | Deck-fetch endpoint should live under the existing `/api/sessions/{sessionId}/...` prefix, reading region from the session row server-side | Architecture Patterns diagram | Low risk — endpoint shape is Claude's Discretion; matches this codebase's existing `/api/sessions` convention (`SessionController.kt`, verified this session) but the planner may choose a different path structure |

**If this table is empty:** N/A — see entries above; all are appropriately scoped per CONTEXT.md's "Claude's Discretion" list or are genuine documentation gaps in TMDB's own official docs.

## Open Questions

1. **RESOLVED (2026-09-04, user decision):** Each movie in the deck response includes its own streaming-provider list — matches the literal ROADMAP success-criterion-1 wording ("titles, posters, genres, and streaming providers"). Provider is NOT filter-only.
   - **Planner instruction:** `MovieCatalogService` must batch-fetch `/movie/{id}/watch/providers` for all movies in a deck (~20) on cache-miss/refresh only (never per individual deck-fetch request), then store the resolved per-movie provider list inside the same deck-cache row (JSONB) alongside the `/discover/movie` data. This raises `MovieCatalogClient`'s call count per cache refresh from 1 to up to 21 (1 discover call + up to 20 per-movie watch-provider calls) — the 6h TTL (D-08) means this cost is paid at most once per filter-combo per 6h window, not per user request. Retry/backoff (D-04, Pattern 3) applies to each of these calls individually.

2. **"Not enough movies" response shape (HTTP status + envelope)** — explicitly Claude's Discretion per CONTEXT.md, not resolved here. Recommend a `200 OK` with a discriminated envelope (e.g. `{ "status": "insufficient_results", "matchCount": N, "minimumRequired": 5 }`) over a `4xx`, since a sparse-but-valid filter combo is not a client error — but this is a recommendation, not a locked decision, and the planner should confirm it doesn't conflict with how Phase 6's frontend will need to branch on it.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java (JDK) | Build/run | ✓ | OpenJDK/Corretto 25.0.4 | Note: STATE.md records this repo needs `JAVA_HOME` pinned to a Temurin 21 install for Gradle 8.14.3's daemon — the default JDK 25 on this machine is not what Gradle uses. Export `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` before any `./gradlew` invocation, per existing STATE.md guidance (not re-probed here — already documented and machine-specific). |
| Docker (Colima) | Testcontainers Postgres integration tests | ✓ | Docker Engine 29.7.2, Colima context | STATE.md also records `DOCKER_HOST`/`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` env vars are needed on this machine for Ryuk — not re-probed here, already documented. |
| `TMDB_API_TOKEN` (TMDB API Read Access Token) | All `MovieCatalogClient` calls, both dev/manual verification and any live-integration tests | ✗ | — | **No fallback for real TMDB calls.** Unit/integration tests of retry/backoff/caching logic must use `mockwebserver3` (recommended above) against a fake HTTP server rather than the real TMDB API. A human must obtain a free TMDB API key + Read Access Token (developer.themoviedb.org account signup) and set it as an env var before this phase's success criteria (which explicitly require *real* TMDB data, not mocks, per ROADMAP criterion 1) can be manually verified end-to-end. This blocks final human verification, not implementation or automated testing. |

**Missing dependencies with no fallback:**
- `TMDB_API_TOKEN` for the final "returns real TMDB titles" manual verification step (ROADMAP success criterion 1) — automated tests can and should proceed via `mockwebserver3` regardless.

**Missing dependencies with fallback:**
- None beyond the above (Java/Docker are both present and already documented as working with machine-specific env exports).

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (`kotlin-test-junit5`) + Spring Boot Test, `[VERIFIED: build.gradle.kts, read this session]` |
| Config file | none — `tasks.withType<Test> { useJUnitPlatform() }` in `build.gradle.kts` |
| Quick run command | `./gradlew test --tests "org.example.muvimatchr.catalog.*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| CTLG-01 | Deck endpoint returns real-shaped TMDB movie data (title/poster/genres) | integration (mocked TMDB via `mockwebserver3`) | `./gradlew test --tests "*.catalog.MovieCatalogServiceTest"` | ❌ Wave 0 |
| CTLG-02 | Genre filter narrows results (`with_genres` param sent correctly) | unit (assert outgoing request params) | `./gradlew test --tests "*.catalog.MovieCatalogClientTest"` | ❌ Wave 0 |
| CTLG-03 | Provider + region filter narrows results (`with_watch_providers`/`watch_region` sent correctly) | unit (assert outgoing request params) | `./gradlew test --tests "*.catalog.MovieCatalogClientTest"` | ❌ Wave 0 |
| CTLG-04 | Second request within TTL doesn't re-hit TMDB (call-count assertion) | integration (mocked TMDB server, assert request count) | `./gradlew test --tests "*.catalog.MovieCatalogServiceTest"` | ❌ Wave 0 |
| CTLG-05 | No TMDB key in any response body/header sent to a caller | integration (`@WebMvcTest`/`MockMvc`, assert response body/headers don't contain the token) | `./gradlew test --tests "*.catalog.DeckControllerTest"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "org.example.muvimatchr.catalog.*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `src/test/kotlin/org/example/muvimatchr/catalog/MovieCatalogClientTest.kt` — covers CTLG-02, CTLG-03; needs `mockwebserver3` wired up (add dependency, per Supporting Libraries above)
- [ ] `src/test/kotlin/org/example/muvimatchr/catalog/MovieCatalogServiceTest.kt` — covers CTLG-01, CTLG-04; needs a fake/mocked `MovieCatalogClient` or `mockwebserver3` + Testcontainers Postgres (reuse `PostgresTestSupport.kt`, already exists per `[VERIFIED: src/test/kotlin/org/example/muvimatchr/support/PostgresTestSupport.kt exists, confirmed via find this session]`)
- [ ] `src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt` — covers CTLG-05; `@AutoConfigureMockMvc` via `spring-boot-webmvc-test` (Boot 4 modularized location, already a working pattern in `ParticipantControllerTest.kt`)
- [ ] `mockwebserver3:5.5.0` — new test dependency, not yet in `build.gradle.kts`

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | Deck-fetch endpoint's participant-identity model is unchanged from Phase 2 (existing token scheme); no new auth surface introduced by this phase |
| V5 Input Validation | yes | Validate `genre` query param is a known TMDB genre id (int, exists in cached `Genre` table) and `provider` similarly against cached `WatchProvider` table, before forwarding to TMDB — prevents malformed/arbitrary values reaching the upstream call and keeps error responses meaningful rather than a raw TMDB 4xx passthrough |
| V9 Communications | yes | All TMDB calls over HTTPS (`https://api.themoviedb.org`) — never configure the `WebClient` bean to disable TLS verification; default `WebClient`/Reactor Netty TLS behavior is already correct out of the box, no custom trust manager needed |
| V13 API and Web Service | yes | CTLG-05's core requirement — TMDB Bearer token must live only in `WebClient`'s default header (server-side), never in a response body, response header, or any value the frontend receives; the token in `application.properties` must be `${TMDB_API_TOKEN}` (env-var interpolated), never a committed literal, mirroring this project's existing `spring.datasource.password` pattern (also env-appropriate, currently a placeholder committed value for local dev only per `[VERIFIED: application.properties, read this session]`) |
| V14 Configuration | yes | `TMDB_API_TOKEN` must never be logged — audit `MovieCatalogClient`/`CatalogWebClientConfig` for any `logger.debug`/`println` that might dump request headers, which would leak the Bearer token into application logs (PITFALLS.md Security Mistakes table already flags this class of issue generally) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| TMDB API key exposure via frontend bundle or leaked response | Information Disclosure | Backend-only proxy (this phase's entire architecture); test explicitly asserts the token never appears in a `DeckControllerTest` response body/headers (see Validation Architecture) |
| SSRF-adjacent: unvalidated `genre`/`provider` query params forwarded directly into the TMDB URL | Tampering | V5 input validation above — validate against cached reference tables before building the outbound TMDB request, rather than string-concatenating raw user input into query params (Spring's `UriBuilder.queryParam(...)` already handles proper encoding, but value *validity*, not just encoding, still needs an app-level check) |
| Secrets in logs (TMDB token) | Information Disclosure | V14 above — no header/request logging at DEBUG or higher without redaction |

## Sources

### Primary (HIGH confidence)
- `developer.themoviedb.org/reference/discover-movie` — fetched directly this session, `/discover/movie` params (`with_genres`, `with_watch_providers`, `watch_region`, `sort_by`, `page`) and response shape (`page`, `results[]`, `total_pages`, `total_results`; per-movie `id`, `title`, `poster_path`, `genre_ids`, `vote_average`, `popularity`)
- `developer.themoviedb.org/reference/discover-movie` (second fetch, sort_by enum + vote_count params) — `sort_by` full enum list, `with_watch_monetization_types` values, `vote_count.gte`/`vote_count.lte`
- `developer.themoviedb.org/reference/genre-movie-list` — fetched directly this session, `/genre/movie/list` shape
- `developer.themoviedb.org/reference/watch-providers-movie-list` — fetched directly this session, `/watch/providers/movie` shape
- `developer.themoviedb.org/reference/movie-watch-providers` — fetched directly this session, `/movie/{id}/watch/providers` shape
- `developer.themoviedb.org/docs/rate-limiting` — fetched directly this session, confirms 2019-12-16 hard-limit removal, current soft ~40 req/s guidance
- `developer.themoviedb.org/docs/authentication-application` — fetched directly this session, exact Bearer header format
- `developer.themoviedb.org/reference/configuration-details` — fetched directly this session, image base URLs + `poster_sizes` enum
- `developer.themoviedb.org/docs/image-basics` — fetched directly this session; confirms official docs are **silent** on any image-CDN connection limit (used to keep the "20 connections/IP" claim at its existing, non-upgraded confidence tier)
- `repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-webclient/maven-metadata.xml` + POM at `4.1.1` — fetched directly this session via `curl`, confirms exact version exists and its dependency tree
- `repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-restclient/`, `spring-boot-webclient/`, `spring-boot-restclient/` `maven-metadata.xml` — fetched directly this session, confirms all four Boot-4-modularized HTTP-client artifacts exist at 4.1.1
- `repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-reactor/maven-metadata.xml` — fetched directly this session, confirms latest/release = 1.11.0
- `repo1.maven.org/maven2/com/squareup/okhttp3/mockwebserver3/maven-metadata.xml` — fetched directly this session, confirms latest/release = 5.5.0
- `projectreactor.io/docs/core/release/reference/coreFeatures/error-handling.html` — fetched directly this session, `Retry.backoff` API shape
- `github.com/spring-projects/spring-boot/issues/50768` — fetched directly this session, confirms real Boot 4.0.6→4.1.0 `RestClient.Builder` auto-configuration regression report
- Direct `Read` of this repo's `src/main/kotlin/org/example/muvimatchr/session/Session.kt`, `build.gradle.kts`, `src/main/resources/application.properties`, `src/main/resources/db/migration/V1__create_session.sql` through `V4__add_participant_token.sql`, and `src/test/kotlin/org/example/muvimatchr/...` (all test files) — this session, confirms exact current codebase state referenced throughout this document

### Secondary (MEDIUM confidence)
- `docs.spring.io/spring-boot/api/java/org/springframework/boot/webclient/autoconfigure/WebClientAutoConfiguration.html` — WebSearch + WebFetch summary, cross-checked against the primary Maven Central POM fetch above
- `www.rickenbazolo.com/en/blog/spring-boot-4-restclient` — third-party blog explaining the Boot 4 `RestClientAutoConfiguration` package relocation; used only as supporting narrative, the load-bearing claim (artifact existence + version) is independently `[VERIFIED]` via Maven Central directly

### Tertiary (LOW confidence)
- TMDB community/support forum posts on image-CDN "20 simultaneous connections per IP" — `[CITED: TMDB community forum, not official docs]`, unchanged from PITFALLS.md's original flag; official docs checked this session and found silent on this specific number, so it cannot be upgraded to VERIFIED

## Metadata

**Confidence breakdown:**
- Standard stack (HTTP client starter choice + versions): HIGH — every version/artifact claim verified directly against Maven Central this session
- TMDB endpoint shapes: HIGH — every endpoint fetched directly from official developer docs this session
- Cache schema design: MEDIUM — a reasoned proposal consistent with this codebase's existing upsert pattern (`VoteRepository`), but not itself sourced from an external authority; explicitly flagged as Claude's Discretion per CONTEXT.md
- Pitfalls: HIGH for the Boot-4 auto-config regression (primary GitHub issue source) and TMDB rate-limiting (official docs); MEDIUM/ASSUMED for the `with_watch_monetization_types` default-behavior gap (genuine doc silence, flagged as Open Question / Assumption)

**Research date:** 2026-09-03
**Valid until:** 2026-10-03 (30 days — TMDB API and Spring Boot 4.1.x are both relatively stable; re-verify if Boot 4.2 GA ships before this phase executes, since a `4.2.0-M1` milestone for the webclient starter was observed on Maven Central during this research, indicating active development on this exact dependency)

---
*Phase: 3-TMDB Integration & Catalog Caching*
*Researched: 2026-09-03*
