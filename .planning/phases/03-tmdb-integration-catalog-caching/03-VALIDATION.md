---
phase: "3"
slug: "tmdb-integration-catalog-caching"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: validated
nyquist_compliant: true
wave_0_complete: true
created: "2026-09-04"
validated: "2026-09-04"
---

# Phase 3 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (`kotlin-test-junit5`) + Spring Boot Test |
| **Config file** | none — `tasks.withType<Test> { useJUnitPlatform() }` in `build.gradle.kts` |
| **Quick run command** | `./gradlew test --tests "org.example.muvimatchr.catalog.*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~60 seconds (matches Phase 1/2's Testcontainers-backed suite) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "org.example.muvimatchr.catalog.*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** ~60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| T1 | 03-01 | 1 | CTLG-01, CTLG-02 | T-03-01, T-03-03, T-03-07 | Deck endpoint returns real-shaped TMDB movie data (title/poster/genres) sourced from the upstream payload, behind a participant token | integration (fake TMDB via `mockwebserver3` + Testcontainers Postgres) | `./gradlew test --tests "*.catalog.DeckControllerTest"` | ✅ exists | ✅ green |
| T2 | 03-01 | 1 | CTLG-04 | T-03-05, T-03-06 | Second request within TTL makes zero upstream calls; concurrent refreshes converge on one cache row via native upsert | integration (fake TMDB, request-count delta + row-count assertions) | `./gradlew test --tests "*.catalog.MovieCatalogServiceTest"` | ✅ exists | ✅ green |
| T2 | 03-01 | 1 | CTLG-04 | T-03-05 | Repeated writes to one cache key leave exactly one row with the latest payload, no integrity violation | integration (repository-level, real Postgres) | `./gradlew test --tests "*.catalog.DeckCacheRepositoryTest"` | ✅ exists | ✅ green |
| T3 | 03-01 | 1 | CTLG-02, CTLG-05 | T-03-01, T-03-02 | Credential travels outbound only as an `Authorization` header, never in a URL; `with_genres`/`sort_by` sent correctly | unit (assert outgoing request line and headers) | `./gradlew test --tests "*.catalog.MovieCatalogClientTest"` | ✅ exists | ✅ green |
| T3 | 03-01 | 1 | CTLG-05 | T-03-01 | No TMDB credential value in any response body or response header reaching a caller | integration (`MockMvc`, raw body + header scan) | `./gradlew test --tests "*.catalog.DeckControllerTest"` | ✅ exists | ✅ green |
| T1 | 03-02 | 2 | CTLG-03 | T-03-15 | Region and provider selection persist and survive a real database round trip; defaults applied at schema level | integration (repository-level, real Postgres) | `./gradlew test --tests "*.SessionRepositoryTest"` | ✅ exists (extended) | ✅ green |
| T2 | 03-02 | 2 | CTLG-03 | T-03-10, T-03-11, T-03-12 | Any participant may replace filters; cross-session token rejected 404; unauthenticated rejected 401; malformed values rejected 400 with no write | integration (`MockMvc`, real Postgres) | `./gradlew test --tests "*.SessionFiltersTest"` | ✅ exists | ✅ green |
| T1, T2 | 03-03 | 3 | CTLG-04 | T-03-17, T-03-19 | Reference catalogues fetched at most once per reference TTL per scope; concurrent refresh converges via per-row upsert | integration (fake TMDB, request-count delta) | `./gradlew test --tests "*.catalog.CatalogReferenceServiceTest"` | ✅ exists | ✅ green |
| T2 | 03-03 | 3 | CTLG-02, CTLG-03 | T-03-17, T-03-18 | Reference endpoints require a participant token and expose TMDB ids only, never internal row identifiers | integration (`MockMvc`) | `./gradlew test --tests "*.catalog.CatalogReferenceControllerTest"` | ✅ exists | ✅ green |
| T3 | 03-03 | 3 | CTLG-02, CTLG-03 | T-03-16 | Unknown genre id rejected 400 with no outbound call; unknown provider id rejected 400 with stored state unchanged | integration (`MockMvc`, request-count delta + state re-read) | `./gradlew test --tests "*.catalog.DeckControllerTest"` | ✅ exists | ✅ green |
| T1 | 03-04 | 4 | CTLG-01 | T-03-23, T-03-27 | Per-title availability resolved for exactly the requested region; absent region yields empty list and null link, never another region's data | unit (multi-region fixture) | `./gradlew test --tests "*.catalog.MovieCatalogClientTest"` | ✅ exists (extended) | ✅ green |
| T2 | 03-04 | 4 | CTLG-01, CTLG-04 | T-03-22, T-03-26 | Refresh issues exactly 1 discover + N availability calls under a bounded semaphore; cache hit issues exactly 0 of any kind; one failing lookup costs only that movie | integration (fake TMDB, exact request-count deltas) | `./gradlew test --tests "*.catalog.MovieCatalogServiceTest"` | ✅ exists (extended) | ✅ green |
| T3 | 03-04 | 4 | CTLG-03 | T-03-24, T-03-25 | Provider + region filter sent correctly from session state (`with_watch_providers` comma-joined, `watch_region`); two regions get two cache rows; no client-supplied filter parameter | integration (`MockMvc`, outbound query assertions) | `./gradlew test --tests "*.catalog.DeckControllerTest"` | ✅ exists (extended) | ✅ green |
| T1 | 03-05 | 5 | CTLG-01, CTLG-04 | T-03-28, T-03-29, T-03-30 | Retry, then serve past-TTL cache labelled `stale: true`, then 503 when nothing cached; failure path writes no row | integration (fake TMDB error responses) | `./gradlew test --tests "*.catalog.MovieCatalogServiceTest"` | ✅ exists (extended) | ✅ green |
| T2 | 03-05 | 5 | CTLG-01 | T-03-31, T-03-33 | Fewer than 5 movies returns the discriminated insufficient-results envelope with a truthful count and no partial deck; 5+ returned uncapped; boundary checked at exactly 4 and 5 | integration (`MockMvc`, fixtures of sizes 0/4/5/20) | `./gradlew test --tests "*.catalog.DeckControllerTest"` | ✅ exists (extended) | ✅ green |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

Task IDs map to the `Task N` headings inside each plan. "File exists" reflects the state at the start of that
plan's wave: `❌ Wave 0` means the plan's own task creates the test file, `✅ exists (extended)` means an
earlier plan in this phase created it and this task adds cases to it. `/gsd-validate-phase` finalizes this
table if gaps remain after execution.

---

## Wave 0 Requirements

- [x] `mockwebserver3:5.5.0` test dependency — added by Plan 03-01 Task 1 (with `spring-boot-starter-webclient` and `kotlinx-coroutines-reactor:1.11.0`)
- [x] `src/test/kotlin/org/example/muvimatchr/support/TmdbMockServerSupport.kt` — created by Plan 03-01 Task 1; singleton fake TMDB server bound into the Spring context via `@DynamicPropertySource`, extending the existing `PostgresTestSupport`. Every other file below depends on it
- [x] `src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt` — created by Plan 03-01 Task 1 (extended by 03-01 T3, 03-03 T3, 03-04 T3, 03-05 T1/T2); `@AutoConfigureMockMvc` via `spring-boot-webmvc-test` (Boot 4 modularized location, same working pattern as `ParticipantControllerTest.kt`)
- [x] `src/test/kotlin/org/example/muvimatchr/catalog/MovieCatalogServiceTest.kt` — created by Plan 03-01 Task 2 (extended by 03-04 T2, 03-05 T1); covers CTLG-01 and CTLG-04
- [x] `src/test/kotlin/org/example/muvimatchr/catalog/DeckCacheRepositoryTest.kt` — created by Plan 03-01 Task 2; upsert idempotency against real Postgres
- [x] `src/test/kotlin/org/example/muvimatchr/catalog/MovieCatalogClientTest.kt` — created by Plan 03-01 Task 3 (extended by 03-04 T1); covers CTLG-02, CTLG-03 and the credential-placement assertions
- [x] `src/test/kotlin/org/example/muvimatchr/session/SessionFiltersTest.kt` — created by Plan 03-02 Task 2 (extended by 03-03 T3)
- [x] `src/test/kotlin/org/example/muvimatchr/catalog/CatalogReferenceServiceTest.kt` and `CatalogReferenceControllerTest.kt` — created by Plan 03-03 Task 2

---

## Manual-Only Verifications

Both items below were resolved via live TMDB UAT on 2026-09-04 (see `03-UAT.md`) once a real
`TMDB_API_TOKEN` became available. Kept here for record — no longer open.

| Behavior | Requirement | Resolution |
|----------|-------------|------------|
| Deck endpoint returns genuinely real (non-mocked) TMDB titles/posters/genres/providers | CTLG-01 | ✅ Confirmed live (03-UAT.md Test 1). Also surfaced and fixed a real bug found only against live data: `TmdbRegionalAvailability`'s Jackson/Kotlin defaulting broke per-movie `providers`/`watchLink` whenever TMDB omitted a monetization-category key entirely (the common real-world shape, never exercised by the fixture-based suite). Fixed in commit `831ba1e`. |
| `with_watch_providers` default monetization-type scope (Assumption A1) | CTLG-03 | ✅ Confirmed live (03-UAT.md Test 3): omitted-parameter result count exactly matches the broadest explicit combination (`buy`/`rent\|buy`), clearly exceeding the narrowest (`flatrate`) — TMDB's default is the broadest match, validating COVERAGE.md's OPT-OUT assumption. |

A third defect was found during the same live pass, outside this table's original two items: CR-04's connectivity-failure retry predicate never matched Spring WebClient's actual wrapped-exception shape (`WebClientRequestException` wrapping the real `IOException`), so a genuine connection failure bypassed the degradation ladder entirely. Fixed in the same commit `831ba1e`; both halves (stale-fallback and 503-no-cache) re-verified live.

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies — all 13 tasks across the five plans carry at least one `<automated>` command; 62 commands total, each with a stated `<fails_when>`
- [x] Sampling continuity: no 3 consecutive tasks without automated verify — every task runs at least one `./gradlew` command
- [x] Wave 0 covers all MISSING references — the four Wave 0 gaps below are each created by a named task (see the Per-Task Verification Map)
- [x] No watch-mode flags — every command is a single-shot Gradle invocation or a grep gate
- [x] Feedback latency < 60s — per-task runs are scoped with `--tests` to the catalog or session package
- [x] `nyquist_compliant: true` set in frontmatter — all 14 per-task rows verified green (full suite: 98/98 tests pass), both Manual-Only items resolved live

## Validation Audit 2026-09-04
| Metric | Count |
|--------|-------|
| Gaps found | 0 |
| Resolved | 14/14 automated rows + 2/2 manual-only items |
| Escalated | 0 |

**Approval:** executed and validated 2026-09-04
