---
phase: "3"
slug: "tmdb-integration-catalog-caching"
status: verified
threats_open: 0
asvs_level: 1
created: "2026-09-04"
verified: "2026-09-04"
---

# Phase 3 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Client -> `GET /api/sessions/{sessionId}/deck` | Untrusted caller supplies a session id, an optional genre value, and a bearer token; the only access control on the deck is the Phase 2 participant token | Genre filter, session id, participant token |
| Client -> `GET/PUT /api/sessions/{sessionId}/filters` | Any participant may replace a session's region/provider selection | Region code, provider ids |
| Client -> `GET /api/catalog/watch-providers` | Reference-data endpoint, requires a participant token | Region code |
| Application -> `api.themoviedb.org` | Outbound boundary where the TMDB read-access token is presented; everything on the far side is a third party | TMDB read-access token, genre/provider/region query params |
| Application -> `deck_cache_entry` / genre / watch_provider tables (Postgres) | Upstream JSON and reference data persisted and re-served to callers | Cached movie/genre/provider data |
| Application -> application logs | Any header/request logging on the outbound client would cross the credential into an unprotected medium | (none — deliberately absent) |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-03-01 | Information Disclosure | TMDB credential reaching a caller or bundle | high | mitigate | Credential exists only as a `defaultHeader` on `tmdbWebClient`; `DeckControllerTest` asserts absence from body and every response header; no source file carries an API key in a URL | closed |
| T-03-02 | Information Disclosure | Credential leaked into application logs | high | mitigate | No request-logging filter or wiretap attached to the WebClient builder | closed |
| T-03-03 | Denial of Service | Unauthenticated relay burning the TMDB rate budget | medium | mitigate | `@CurrentParticipant` required; 6h shared-cache TTL bounds outbound volume regardless of caller volume | closed |
| T-03-04 | Tampering | `genre` query parameter forwarded into outbound TMDB URL | medium | mitigate | Typed `Int?`, rejected by Spring before any outbound call; attached via `queryParam`, never string-concatenated | closed |
| T-03-05 | Tampering | Native upsert in `DeckCacheRepository` | low | mitigate | All values bound as named `@Param`, no interpolation | closed |
| T-03-06 | Information Disclosure | Shared-across-sessions cache leaking session-scoped data | medium | mitigate | `deck_cache_entry` has no session/participant column; cache key derives only from filter combination | closed |
| T-03-07 | Spoofing | Cross-session deck access using another session's token | medium | mitigate | Handler compares `participant.session.id` against path `sessionId`, 404 on mismatch | closed |
| T-03-08 | Tampering | Supply chain — three new Maven Central artifacts | high | mitigate | Package Legitimacy Audit (RESEARCH.md) verified all three first-party (Spring, JetBrains, Square), no SUS verdicts | closed |
| T-03-09 | Communications (V9) | TLS to the TMDB endpoint | low | mitigate | Base URL is `https://`; WebClient installs no custom trust manager, disables no verification | closed |
| T-03-10 | Tampering | Malformed region/provider values on filter write | medium | mitigate | `@Pattern("^[A-Z]{2}$")` on region; `validateProviderIds` bounds count; `VARCHAR(2)`/`VARCHAR(255)` schema backstop | closed |
| T-03-11 | Spoofing | Cross-session filter write | medium | mitigate | Same session-id comparison pattern as T-03-07 | closed |
| T-03-12 | Elevation of Privilege | Unauthenticated filter read/write | high | mitigate | `@CurrentParticipant` on both filter endpoints | closed |
| T-03-13 | Repudiation | Any participant can change filters with no attribution record | low | accept | Deliberate per D-02 — no participant-attributed audit trail exists anywhere in the app; adding one only for this field would be inconsistent | closed (accepted) |
| T-03-14 | Denial of Service | Participant flipping filters to force cache misses | low | accept | Bounded by the shared filter-combo cache — a flip to an already-cached combo costs nothing; TTL bounds the worst case. No rate limiting exists anywhere yet (already an open Phase 2 item for the join endpoint); revisit both together before public deployment | closed (accepted) |
| T-03-15 | Tampering | Session region/provider columns | low | mitigate | Both columns added `NOT NULL DEFAULT` at the schema level | closed |
| T-03-16 | Tampering | Unknown genre/provider id accepted before validation | high | mitigate | `CatalogReferenceService` validation call ordering verified to precede every outbound call and every write | closed |
| T-03-17 | Denial of Service | Unauthenticated reference-data relay | medium | mitigate | `@CurrentParticipant` on both reference handlers; 168h reference TTL | closed |
| T-03-18 | Information Disclosure | Internal row identifiers leaking via reference endpoints | low | mitigate | Responses carry `tmdbId` only, never the internal UUID | closed |
| T-03-19 | Tampering | Concurrent reference-table refresh | medium | mitigate | `ON CONFLICT` upsert on both genre and watch-provider tables | closed |
| T-03-20 | Denial of Service | Reference refresh failing while a deck request is in flight | medium | accept | Plan-time rationale: an exhausted-retry reference fetch propagates as an error for that request. **Narrower in shipped code than at plan time** — WR-01 (`831ba1e`) added stale-serve for reference data; only a first-ever request against an empty table still rethrows. Recorded as accepted with this reduced residual scope | closed (accepted) |
| T-03-21 | Spoofing | Reference endpoints accepting a token from any session | low | accept | Deliberate — reference data is not session-scoped, so binding it to one session adds a check with no protective value | closed (accepted) |
| T-03-22 | Denial of Service | Unbounded per-movie availability fan-out | high | mitigate | Bounded semaphore (`providerLookupConcurrency`, clamped ≥1 by WR-03); refresh-path only, never cache-hit path | closed |
| T-03-23 | Information Disclosure | Wrong-region availability badge | high | mitigate | Exact-key lookup on requested region only, no fallback to another region | closed |
| T-03-24 | Tampering | Provider/region filter forwarded into outbound TMDB URL | medium | mitigate | Sourced from `session.providerIds`/`session.region` (write-time validated), never a client-supplied deck query parameter | closed |
| T-03-25 | Spoofing | Client-supplied region/provider override on the deck endpoint | medium | mitigate | Deck handler accepts only `genre` as a request parameter; no region/provider query param exists | closed |
| T-03-26 | Denial of Service | One movie's failed availability lookup aborting the whole deck | medium | mitigate | Per-lookup try/catch yields an empty result for that movie only; `CancellationException` rethrown, never swallowed | closed |
| T-03-27 | Information Disclosure | Availability merge leaking another region's data | medium | mitigate | Exact-key lookup, no cross-region merge — verified no `.first()`/`firstOrNull()` fallback pattern | closed |
| T-03-28 | Tampering | Failed deck refresh writing a partial/wrong cache row | high | mitigate | Failure path returns or throws with no write; upsert is single-call-site on the success path only | closed |
| T-03-29 | Information Disclosure | Stale deck served without signaling degradation | medium | mitigate | `stale: true`/`false` explicitly propagated to the caller through `DeckResult` and the response body | closed |
| T-03-30 | Denial of Service | TMDB outage bypassing the retry/degradation ladder | medium | mitigate | Live UAT found and fixed a real gap: retry filter now walks the (bounded) wrapped-exception cause chain, not just the outermost throwable — commit `831ba1e`. Both ladder halves (stale-serve, 503-no-cache) re-verified live against a genuine connection-refused failure | closed |
| T-03-31 | Denial of Service | Sparse-result handling adding an extra round trip | low | mitigate | Decided at response-shaping time, after the normal cache read/write path — no extra request | closed |
| T-03-32 | Information Disclosure | Upstream error detail leaking into the 503 response | medium | mitigate | Fixed literal message, no interpolation of upstream body/headers/URL; Spring Boot's default `server.error.include-message: never` in effect | closed |
| T-03-33 | Spoofing | Stale fallback observing a different cache row than the freshness check | medium | mitigate | Fallback reuses the same `existing` read taken before the refresh attempt; `findByCacheKey` has exactly one call site on this path | closed |

*Status: open · closed · open — below `high` threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above `workflow.security_block_on` (`high`) count toward `threats_open`*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Unregistered Surface Found During Audit

| ID | Finding | Status |
|----|---------|--------|
| UNREGISTERED-01 | `GET /api/catalog/watch-providers` accepted an unconstrained `region: String` (flows into an outbound TMDB param and a `VARCHAR(2)` column). Plan 03-03's register covered unvalidated genre/provider *ids* (T-03-16) but never mapped this endpoint's `region` request parameter. Found by code review, not threat modeling. | closed — fixed in `5e31b91` (`@Validated` + `@Pattern("^[A-Z]{2}$")`) |

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-03-01 | T-03-13 | No participant-attributed audit trail exists anywhere in the app (D-02); adding one only for filter changes would be inconsistent. Revisit if abuse reporting is ever needed. | gsd-security-auditor (opus) | 2026-09-04 |
| AR-03-02 | T-03-14 | Bounded by the shared filter-combo cache TTL; no rate limiting exists anywhere in the app yet — already an open Phase 2 item for the join endpoint. Revisit both together before public deployment. | gsd-security-auditor (opus) | 2026-09-04 |
| AR-03-03 | T-03-20 | Residual scope is narrower than at plan time: WR-01 added stale-serve for reference data, so only a first-ever request against an empty reference table still propagates an error. | gsd-security-auditor (opus) | 2026-09-04 |
| AR-03-04 | T-03-21 | Deliberate — reference data (genre/provider lists) is not session-scoped; binding it to one session would add a check with no protective value. | gsd-security-auditor (opus) | 2026-09-04 |

*Accepted risks do not resurface in future audit runs.*

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-09-04 | 33 | 33 (29 mitigated + 4 accepted) | 0 | gsd-security-auditor (opus) |

Audit ran against `main` at `9f2bf40` (post code-review-fix `831ba1e`, post live-UAT-fix — `git diff --stat 831ba1e..HEAD -- src/` empty at audit time). Every `mitigate`-disposition threat verified with a specific file/line citation, not by trusting the plan's stated intent.

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-09-04
