# Phase 3: TMDB Integration & Catalog Caching - Context

**Gathered:** 2026-09-03
**Status:** Ready for planning

<domain>
## Phase Boundary

The movie deck participants swipe through is sourced from real TMDB data via a backend-proxied deck-fetch endpoint: filterable by genre and by region-aware streaming availability, cached server-side, with TMDB credentials never reaching the client. This phase delivers catalog sourcing and caching only — no voting/swipe recording (Phase 4), no WebSocket live updates (Phase 5), no frontend consumption of this endpoint (Phase 6). Deck *ranking* signal (TMDB popularity/rating) is in scope; any form of ML-based personalization/taste modeling remains out of scope per PROJECT.md.

</domain>

<decisions>
## Implementation Decisions

### Region source
- **D-01:** Region for streaming-availability filtering is a field on `Session`, set by whoever creates the session. It's optional at creation — if not set, it defaults to Germany (`DE`). — **Reversibility:** reversible — adding a nullable/defaulted column to `Session` via a new Flyway migration has no impact on Phase 1/2 data.
- **D-02:** The region can be changed later by **any** participant in the session, not just the creator. This deliberately preserves Phase 2's decision that there's no host/creator role or special privileges (see `02-CONTEXT.md` D-03) — the app has no concept of "who created this session" gating any action today, and this phase does not introduce one. — **Reversibility:** costly — if a future phase decides region-editing should be creator-only, that requires introducing a creator-identity concept that doesn't exist anywhere else in the app yet, not just a permission check on this one field.
- Changing the region does not retroactively affect votes already cast in the session — that's a Phase 4 concern (vote/match logic), not this phase's.

### Streaming-provider selection
- **D-09** (added during plan-phase research prep, 2026-09-04): Which streaming service(s) the group has access to is a **multi-select, session-level field** — same pattern as region (D-01/D-02): set by whoever creates the session, optional, editable later by any participant, no creator-only gating. A session can select zero, one, or multiple providers (e.g. Netflix + Prime Video). TMDB's `with_watch_providers` param natively accepts a comma-separated list of provider IDs with OR semantics, so this maps directly onto one query param — no per-provider TMDB calls needed for filtering itself. — **Reversibility:** costly, same rationale as D-07 (cache is keyed on the resolved filter combo; changing provider-selection scope later means re-deriving cache keys and the CTLG-04 cache-hit test).
- **D-10** (added during plan-phase research prep, 2026-09-04): Each movie in the deck response includes its own resolved streaming-provider list (not filter-only) — matches ROADMAP's Phase 3 success criterion 1 wording ("titles, posters, genres, and streaming providers"). `MovieCatalogService` batch-fetches `/movie/{id}/watch/providers` for all movies in a deck (~20) on cache-miss/refresh only, never per individual deck-fetch request, and stores the resolved per-movie provider data inside the same deck-cache row. This raises TMDB call count per cache refresh from 1 to up to 21, but the ~6h TTL (D-08) means this cost is paid at most once per filter-combo per 6h window.

### Deck ranking (no personalization)
- **D-03:** When no streaming-provider filter is applied, region is irrelevant to deck sourcing and the deck is TMDB's `/discover/movie` results filtered only by genre (if set), ordered by TMDB's own popularity/rating signal. No custom "what will this group like" logic is built — this explicitly avoids re-opening PROJECT.md's out-of-scope call on ML-based taste modeling/personalization. Streaming-provider filtering is opt-in, not a default always-on scope.

### TMDB outage / degradation behavior
- **D-04:** On a TMDB call failure (slow, rate-limited, down) for a deck request: retry a few times with exponential backoff + jitter (per STACK.md's `WebClient` + `retryWhen` recommendation) first. If retries are exhausted, serve the existing cached deck for that filter combo even if it's past its freshness TTL. Only return an explicit error to the caller if there is no cached deck at all for that filter combo yet.

### Deck size & sparse filters
- **D-05:** A deck is one fixed page of TMDB `/discover/movie` results (~20 movies) per filter combination — no multi-page pre-fetching.
- **D-06:** If a filter combination (genre + provider + region) matches fewer than 5 movies, the deck endpoint returns an explicit "not enough movies, try broader filters" response instead of a thin deck. 5+ matches are returned as-is, uncapped beyond the single TMDB page.

### Cache scope & freshness
- **D-07:** The deck cache is keyed by filter combination (genre, provider, region) and **shared across sessions** — two sessions requesting the same filters get the same cached deck rather than each maintaining its own copy. Matches ARCHITECTURE.md's recommendation to cache per filter combo, not per session. — **Reversibility:** costly — switching to per-session cache scoping later means re-keying the cache table/lookup and re-deriving whatever cache-hit assertions Phase 3's own tests rely on (success criterion 4 is "second request for same filters within TTL doesn't re-hit TMDB").
- **D-08:** A cached deck is considered fresh for a few hours (~6h) before a refetch from TMDB is triggered on next request. Genre and watch-provider *reference* lists (not the movie deck itself) are separately cached long-term per ARCHITECTURE.md/PITFALLS.md guidance, since those catalogs are effectively static for weeks — that reference-data TTL is Claude's discretion, not discussed as a separate decision here.

### Claude's Discretion
- Exact deck cache TTL number as a tunable config value beyond "~6h" — treat as adjustable, not hard-locked.
- Genre/watch-provider reference-data cache TTL and refresh mechanism (scheduled job vs lazy-on-miss).
- Exact Flyway migration shape for the new `Session.region` column (nullable with app-level default vs `NOT NULL DEFAULT 'DE'` at the DB level).
- Exact storage shape for `Session`'s multi-select provider field (D-09) — e.g. a comma-separated `VARCHAR` column vs a Postgres array/`TEXT[]` column vs a join table — as long as it holds zero-to-many TMDB provider IDs and survives restart like every other Session field.
- HTTP client choice mechanics (`WebClient` vs Boot 3.2+ `RestClient`) and exact retry/backoff parameters (attempt count, base delay) — STACK.md already recommends `WebClient` + `retryWhen`, exact tuning is implementation detail.
- Cache table/entity shape (`CachedMovie`, genre, provider tables) — already sketched in ARCHITECTURE.md's package structure, not re-litigated here.
- Response shape for the "not enough movies" case (HTTP status code, JSON envelope) — implementation detail, not a product decision.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements & roadmap
- `.planning/REQUIREMENTS.md` — CTLG-01 through CTLG-05 (this phase's requirement set)
- `.planning/ROADMAP.md` §"Phase 3: TMDB Integration & Catalog Caching" — goal, 5 success criteria, dependency on Phase 1 only
- `.planning/PROJECT.md` — Constraints (TMDB movie-data source), Out of Scope (no ML-based personalization — directly shapes D-03), Key Decisions table

### Architecture & stack research (Phase 3-specific)
- `.planning/research/ARCHITECTURE.md` §"Package Structure" — `catalog/` package: `MovieCatalogClient.kt`, `MovieCatalogService.kt`, `CachedMovie.kt`, `tmdb/` DTOs
- `.planning/research/ARCHITECTURE.md` §"Anti-Pattern 3" — TMDB DTOs must not leak past `MovieCatalogService`; cache reference data instead of re-fetching per request
- `.planning/research/ARCHITECTURE.md` — TMDB endpoints: `/discover/movie` (deck), `/movie/{id}/watch/providers` (per-title availability), `/genre/movie/list` (cached long-term); CDN-level cap ~50 req/s, cache aggressively
- `.planning/research/STACK.md` — `WebClient` (not deprecated `RestTemplate`) for all TMDB calls, paired with `kotlinx-coroutines-reactor`; `retryWhen` with exponential backoff + jitter for 429s/transient failures; `Authorization: Bearer <TMDB_READ_ACCESS_TOKEN>` header (not query-param API key)
- `.planning/research/PITFALLS.md` §"Pitfall 6" — TMDB API key exposed in frontend; all calls must be backend-proxied
- `.planning/research/PITFALLS.md` — region-awareness gotcha (`watch_region` param required, availability is region-specific per JustWatch sourcing), image CDN connection-limit note (~20 simultaneous connections per IP — avoid firing dozens of poster requests in parallel), graceful-degradation-on-TMDB-failure gotcha (directly informs D-04)
- `.planning/research/PITFALLS.md` — TMDB rate-limit/CDN numbers are LOW confidence (community forum sourced) — re-verify against current TMDB docs during planning/implementation
- `.planning/research/SUMMARY.md` — Phase 3 delivers/avoids summary, confirms Phase 3 has no dependency on Phase 2 (can be built independently)

### Prior phase context (for consistency)
- `.planning/phases/02-session-lobby-flow/02-CONTEXT.md` D-03 — no host/creator role or privileges exists; directly informs D-02 above (region-edit permission)
- `src/main/kotlin/org/example/muvimatchr/session/Session.kt` — existing `Session` entity (`id`, `joinCode`, `createdAt`) that D-01's region field extends

No external specs beyond the above — requirements fully captured in decisions above.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- None to build on for the catalog itself — no `catalog/` package exists yet; this phase creates it fresh per ARCHITECTURE.md's package structure.
- `Session` entity (Phase 1/2, `src/main/kotlin/org/example/muvimatchr/session/Session.kt`) — D-01's region field is a new column added here via a new Flyway migration (V5+), following the established per-table-migration pattern from Phases 1-2.
- `PostgresTestSupport.kt` (Phase 1, `src/test/kotlin/org/example/muvimatchr/support/`) — reusable Testcontainers Postgres fixture for any new cache-table repository tests this phase adds.

### Established Patterns
- Flyway owns all DDL; `spring.jpa.hibernate.ddl-auto=validate` — the new `Session.region` column and any new cache tables (`cached_movie`, `genre`, `watch_provider`) must ship as new Flyway migrations, never Hibernate auto-DDL.
- JPA entities are plain Kotlin classes (not `data class`), `@Id @GeneratedValue(strategy = GenerationType.UUID)`, relying on `kotlin("plugin.jpa")` — follow this shape for `CachedMovie` and any other new entities.
- Package-by-feature layout (`session/`, `voting/`, now `catalog/`) — established in Phase 1, do not introduce `controller/`/`service/`/`model/`-style layer packages.

### Integration Points
- **Legacy code to replace, not extend:** `src/main/kotlin/org/example/muvimatchr/service/MovieService.kt` is the prior prototype's placeholder catalog — it calls OMDb (not TMDB), hardcodes a mock title list, and uses the deprecated blocking `RestTemplate`. Fully superseded by this phase's `catalog/` package; not reused. `MovieVoteController.kt` similarly holds legacy in-memory voting logic out of this phase's scope (Phase 4 concern).
- `src/main/resources/application.properties` currently has only datasource/JPA/Flyway config — this phase adds the TMDB base URL, bearer token (via env var, never committed), and any cache-TTL config here.
- `build.gradle.kts` does not yet declare `spring-webflux` (needed for `WebClient`) or `kotlinx-coroutines-reactor` — this phase's first plan likely adds these per STACK.md.

</code_context>

<specifics>
## Specific Ideas

No specific UI/UX references — this is a backend/data phase with no frontend surface (Phase 6 consumes this endpoint later). The one substantive product question that came up — "how do we decide which movies are interesting for the group" — was resolved as: use TMDB's own popularity/rating ordering, no custom logic (see D-03); explicitly not re-opening the ML-personalization out-of-scope call from PROJECT.md.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope. (The "movie selection beyond streaming" question was explored and resolved directly within this phase — see D-03 — rather than deferred, since it was really asking how the *existing* genre+ranking scope should behave, not proposing new scope.)

</deferred>

---

*Phase: 3-TMDB Integration & Catalog Caching*
*Context gathered: 2026-09-03*
