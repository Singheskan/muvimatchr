---
phase: "3"
slug: "tmdb-integration-catalog-caching"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-09-04"
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
| TBD | TBD | TBD | CTLG-01 | V13/V14 | Deck endpoint returns real-shaped TMDB movie data (title/poster/genres/providers), never the TMDB token | integration (mocked TMDB via `mockwebserver3`) | `./gradlew test --tests "*.catalog.MovieCatalogServiceTest"` | ❌ Wave 0 | ⬜ pending |
| TBD | TBD | TBD | CTLG-02 | V5 | Genre filter narrows results (`with_genres` param sent correctly) | unit (assert outgoing request params) | `./gradlew test --tests "*.catalog.MovieCatalogClientTest"` | ❌ Wave 0 | ⬜ pending |
| TBD | TBD | TBD | CTLG-03 | V5 | Provider + region filter narrows results (`with_watch_providers`/`watch_region` sent correctly) | unit (assert outgoing request params) | `./gradlew test --tests "*.catalog.MovieCatalogClientTest"` | ❌ Wave 0 | ⬜ pending |
| TBD | TBD | TBD | CTLG-04 | — | Second request within TTL doesn't re-hit TMDB (call-count assertion against mocked TMDB server) | integration (mocked TMDB server, assert request count) | `./gradlew test --tests "*.catalog.MovieCatalogServiceTest"` | ❌ Wave 0 | ⬜ pending |
| TBD | TBD | TBD | CTLG-05 | V13 | No TMDB key in any response body/header sent to a caller | integration (`@WebMvcTest`/`MockMvc`, assert response body/headers don't contain the token) | `./gradlew test --tests "*.catalog.DeckControllerTest"` | ❌ Wave 0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

Task IDs/Plan/Wave columns are TBD until the planner assigns concrete task numbers — this table will be refined during planning and finalized by `/gsd-validate-phase` if gaps remain after execution.

---

## Wave 0 Requirements

- [ ] `src/test/kotlin/org/example/muvimatchr/catalog/MovieCatalogClientTest.kt` — covers CTLG-02, CTLG-03; needs `mockwebserver3` wired up (new test dependency)
- [ ] `src/test/kotlin/org/example/muvimatchr/catalog/MovieCatalogServiceTest.kt` — covers CTLG-01, CTLG-04; needs a fake/mocked `MovieCatalogClient` or `mockwebserver3` + Testcontainers Postgres (reuse existing `PostgresTestSupport.kt`)
- [ ] `src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt` — covers CTLG-05; `@AutoConfigureMockMvc` via `spring-boot-webmvc-test` (Boot 4 modularized location, same working pattern as `ParticipantControllerTest.kt`)
- [ ] `mockwebserver3:5.5.0` test dependency — not yet in `build.gradle.kts`

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Deck endpoint returns genuinely real (non-mocked) TMDB titles/posters/genres/providers | CTLG-01 | No `TMDB_API_TOKEN` is set in this dev environment (see RESEARCH.md Environment Availability) — automated tests use `mockwebserver3` against a fake server, which proves the integration logic but cannot prove live TMDB connectivity | Obtain a TMDB API Read Access Token, set `TMDB_API_TOKEN` env var, hit the deck endpoint manually (curl/Postman) against real TMDB, and visually confirm returned titles/posters/genres/providers are real current movies, not fixture data |
| `with_watch_providers` default monetization-type scope (Assumption A1) | CTLG-03 | TMDB's official docs are silent on default behavior when `with_watch_monetization_types` is omitted — must be confirmed against a live call, not derivable from mocked tests | With a real `TMDB_API_TOKEN`, call `/discover/movie` with only `with_watch_providers` set and inspect whether results are scoped to `flatrate` (subscription) or include rent/buy/free — confirm it matches the intended "streaming availability" meaning before relying on it |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
