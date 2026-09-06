---
phase: "04"
slug: "vote-recording-match-aggregation"
status: verified
threats_open: 0
asvs_level: 1
created: "2026-09-06"
---

# Phase 04 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| client → `POST /api/sessions/{id}/votes` | Untrusted vote submission | `sessionId`, `movieId`, `choice`, bearer token |
| client → `GET /api/sessions/{id}/deck` | Triggers the one-time deck pin | `sessionId`, bearer token — accepts no other request parameters |
| client → `POST /api/sessions` / `PUT /api/sessions/{id}/filters` | Session creation and filter replacement | `region`, `providerIds`, `genre` |
| client → `GET /api/sessions/{id}/votes/status` | Live status read | matched movie ids, per-movie like counts |
| application → PostgreSQL | Native SQL carrying caller-derived session/participant identifiers | session/participant/movie ids, vote choice |
| process lifecycle → PostgreSQL | State written before shutdown, read by an independently-constructed context afterwards | pinned deck snapshot, vote rows |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-04-01 | Spoofing | `VoteController` participant identity | high | mitigate | Identity resolved solely from bearer token via `@CurrentParticipant`; never accepted from body/path/query. Verified: `VoteController.kt` uses `@CurrentParticipant` on both endpoints, no participant-id parameter exists. | closed |
| T-04-02 | Tampering | `VoteController` movieId acceptance | high | mitigate | 400 on any `movieId` absent from `sessionService.pinnedMovieIds(session)` before entering `VoteService.recordVote`. Verified: guard present at `VoteController.kt:51`. | closed |
| T-04-03 | Elevation of Privilege | Cross-session vote injection | high | mitigate | `participant.session.id != sessionId` → 404, applied on both endpoints. Verified: guard present at `VoteController.kt:40,63`. | closed |
| T-04-04 | Tampering | Native queries in `SessionRepository`/`VoteRepository` | high | mitigate | Named `@Param` binding only, no string interpolation. Verified: every native `@Query` in both repositories binds via `@Param`; no `${...}` interpolation found in query text. | closed |
| T-04-05 | Denial of Service | Session row lock held across `recordVote` | medium | mitigate | Row lock (not table lock); no outbound HTTP inside the transaction. Verified: `VoteService.recordVote` runs lock → upsert → aggregate-read only, no HTTP call. | closed |
| T-04-06 | Information Disclosure | `GET .../votes/status` response | low | accept | Aggregate counts visible to any authenticated session member; no per-participant vote attribution exposed. Intended product behavior. | closed |
| T-04-07 | Repudiation | Vote change history | low | accept | Only `voted_at` records the most recent write; no audit trail of superseded choices. Consistent with Phase 3's accepted no-audit-trail decision. | closed |
| T-04-08 | Denial of Service | Unbounded vote submissions | medium | accept | No rate limiting app-wide (standing accepted risk from Phases 2–3). Row growth bounded by T-04-02's deck-membership check plus the unique constraint. | closed |
| T-04-09 | Tampering | Genre reaching an outbound catalog URL | high | mitigate | `requireKnownGenre` runs before persistence. Verified: `SessionController.kt:38,81` calls it in `createSession`/`replaceFilters` before the value is stored. | closed |
| T-04-10 | Tampering | Post-pin filter mutation | high | mitigate | 409 once `deckPinnedAt` is set, stored values unchanged. Verified: `SessionService.kt:72-73` guard precedes all field assignments. | closed |
| T-04-11 | Tampering | Per-client deck divergence via a request parameter | high | mitigate | Deck endpoint binds no request parameters; all filters read from the session row. Verified: `DeckController.getDeck` takes only `@PathVariable sessionId` and `@CurrentParticipant`. | closed |
| T-04-12 | Elevation of Privilege | Filter replacement authorisation | medium | accept | Session membership is the entire authorisation rule (Phase 2's no-host-role decision, D-03). Intended group behaviour. | closed |
| T-04-13 | Tampering | `findUnanimousMovieIds`/`findLikeCountsBySession` | high | mitigate | Every value bound via named `@Param`, including the participant-id collection. Verified: `VoteRepository.kt` — no string-assembled SQL. | closed |
| T-04-14 | Tampering | Roster denominator for unanimity | high | mitigate | Active roster derived server-side from `participant.created_at`/`MAX(vote.voted_at)`; no client input contributes. Verified in `VoteRepository.findActiveParticipantIds`. | closed |
| T-04-15 | Information Disclosure | Like counts exposed to session members | low | accept | Counts are per-movie, never per-participant — cannot attribute a vote to a person. Intended product behaviour (RSLT-03). | closed |
| T-04-16 | Denial of Service | Aggregate queries on every status read | low | accept | Scoped to one session, backed by `idx_vote_session_id` (V3); no app-wide rate limiting (standing accepted risk). | closed |
| T-04-17 | Tampering | Lost update under simultaneous final votes | high | mitigate | Session-scoped row lock serialises writers. Verified: `VoteServiceConcurrencyTest` passes across 10 independent races — every vote persisted, completion reported exactly once. | closed |
| T-04-18 | Denial of Service | Lock contention → deadlock/stall | medium | mitigate | Futures awaited with a bounded 30s timeout; no outbound call holds the lock. Verified: `VoteServiceConcurrencyTest.kt:129`. | closed |
| T-04-19 | Denial of Service | Connection-pool exhaustion masquerading as serialisation | medium | mitigate | Test asserts pool size exceeds racing-thread count. Verified: `VoteServiceConcurrencyTest.kt:92` and the equivalent `DeckPinConcurrencyTest` guard. | closed |
| T-04-20 | Tampering | Schema drift on restart | high | mitigate | Flyway history row count asserted unchanged across restart; `ddl-auto=validate` refuses to start on mismatch. Verified: `RestartSurvivalTest.kt` asserts `flyway_schema_history` count before/after restart. | closed |
| T-04-21 | Tampering | Concurrent first-time deck pin (code-review CR-01) | high | mitigate | `SessionService.pinDeck` takes the same session row lock as `recordVote`; `DeckController.getDeck` builds its response from the persisted snapshot, not the caller's own fetch. Verified: `DeckPinConcurrencyTest` — 10 races each converge on exactly one persisted snapshot. | closed |
| T-04-22 | Tampering | Concurrent filter-replacement/pin race (code-review WR-02) | medium | mitigate | `SessionService.replaceFilters` takes the same row lock before its pin-state check, so the two transactions cannot interleave. Verified: lock call present at `SessionService.kt:66`. | closed |

*Status: open · closed · open — below high threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on (high) count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

T-04-21/T-04-22 are additive: they were found and closed by the phase's code-review pass (`04-REVIEW.md` CR-01/WR-02), not present in the original plan-time STRIDE registers, and are recorded here for continuity.

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-04-01 | T-04-06, T-04-15 | Aggregate vote/like counts visible to any session member is intended product behaviour — no per-participant attribution is exposed. | Planner (04-01/04-03-PLAN.md) | 2026-09-04 |
| AR-04-02 | T-04-07 | No audit trail of superseded vote choices; consistent with Phase 3's accepted no-audit-trail decision. | Planner (04-01-PLAN.md) | 2026-09-04 |
| AR-04-03 | T-04-08, T-04-16 | No app-wide rate limiting exists yet — standing accepted risk carried from Phases 2–3, to revisit before a public deploy. | Planner (04-01/04-03-PLAN.md) | 2026-09-04 |
| AR-04-04 | T-04-12 | Session membership is the entire authorisation rule for filter replacement, per Phase 2's no-host-role decision (D-03). | Planner (04-02-PLAN.md) | 2026-09-04 |

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-09-06 | 22 | 22 | 0 | Orchestrator (grep-level L1 verification against plan-time STRIDE registers; ASVS level 1) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-09-06
