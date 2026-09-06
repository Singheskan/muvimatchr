---
phase: "05"
slug: "real-time-notification-layer"
status: verified
threats_open: 0
asvs_level: 1
created: "2026-09-06"
---

# Phase 05 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Client ↔ STOMP broker (`/ws`, `/topic/session/{id}`) | Unauthenticated WebSocket handshake; any holder of a session UUID can subscribe | Session-level aggregate vote status only (no participant identity, no per-participant choice) |
| Client ↔ REST vote/status endpoints | Bearer-token authenticated (`@CurrentParticipant`) | Same aggregate status payload, but gated by participant token |
| VoteService ↔ SessionEventPublisher | In-process, after-commit | `SessionVoteStatus` mapped through `toResponse()` |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-05-01 | Information Disclosure | `/topic/session/{sessionId}` | medium | accept | Session UUID (122 bits entropy) is the control; payload is aggregate-only. Documented in 05-CONTEXT.md D-01/D-02, carried forward from 05-01 into 05-02. Revisit pre-public-deploy with other logged items in STATE.md. | closed |
| T-05-02 | Information Disclosure | `SessionEventPublisher.broadcastStatus` | high | mitigate | Verified: `messagingTemplate.convertAndSend("/topic/session/$sessionId", status.toResponse())` — only the mapped `VoteStatusResponse`, no participant id/name/token/choice. | closed |
| T-05-03 | Spoofing | `WebSocketConfig` inbound channel interceptor | high | mitigate | Verified: `ChannelInterceptor.preSend` returns `null` for `StompCommand.SEND`; no `@MessageMapping` handler exists. Asserted by `a forged client SEND to a session topic reaches no subscriber`. | closed |
| T-05-04 | Tampering | `WebSocketConfig` handshake registration | medium | mitigate | Verified: no `setAllowedOrigin`/origin-widening call anywhere in `src/main/kotlin/`; Spring's same-origin default retained. | closed |
| T-05-05 | Information Disclosure | `VoteService.recordVote` → broadcast ordering | medium | mitigate | Verified: broadcast registered via `TransactionSynchronizationManager.registerSynchronization`, `afterCommit` — a rolled-back vote publishes nothing. | closed |
| T-05-06 | Denial of Service | STOMP endpoint connection/subscription volume | medium | accept | Consistent with the app-wide no-rate-limiting accepted risk already logged in STATE.md. Single-instance hosting target; in-memory `SimpleBroker` holds only subscription registrations. Carried forward from 05-01 into 05-02. | closed |
| T-05-07 | Denial of Service | `SessionEventPublisher.broadcastStatus` | medium | mitigate | Verified: `catch (e: Exception)` swallows and logs broadcast failures; runs after commit, so a broker fault cannot roll back or fail an already-durable vote. | closed |
| T-05-08 | Information Disclosure | Cross-session topic isolation | high | mitigate | Verified: `a subscriber to one session receives nothing from another session and does receive its own` — `assertNull(crossSessionFrame, ...)`. | closed |
| T-05-09 | Elevation of Privilege | REST reconciliation path (`GET /votes/status`) | high | mitigate | Verified: `@CurrentParticipant` on the status endpoint requires a valid bearer token; the STOMP topic remains intentionally open by design — asymmetry confirmed live during `/gsd-verify-work 05`'s manual RTIME-03 walkthrough (REST call succeeded only with Alice's token). | closed |
| T-05-10 | Information Disclosure | `realtime/` package (no cache/replay state) | medium | mitigate | Verified: negative grep for cache/replay/lastKnown/buffer-shaped identifiers in `src/main/kotlin/org/example/muvimatchr/realtime/` — no matches. Confirmed live: reconnected client received 0 frames, no replay. | closed |
| T-05-11 | Tampering | Aggregation logic independence from notification layer | medium | mitigate | Verified: `a session completes correctly even with no client ever subscribed` exists in `ReconnectReconciliationTest.kt` and passes. | closed |

*Status: open · closed · open — below {block_on} threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-05-01 | T-05-01 | Unauthenticated STOMP subscription to a session topic. Session UUID entropy (122 bits) is the sole control; payload carries only aggregate vote status, never participant-identifying data. Consistent with 05-CONTEXT.md D-01/D-02 and the app-wide posture already logged for the join code in `STATE.md`. | Project owner (via 05-CONTEXT.md decision) | 2026-09-06 |
| AR-05-02 | T-05-06 | No rate limiting on WebSocket connection/subscription volume. Consistent with the app-wide no-rate-limiting accepted risk already logged in `STATE.md` for a single-instance hosting target. | Project owner (via prior STATE.md decision, carried forward) | 2026-09-06 |

*Accepted risks do not resurface in future audit runs.*

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-09-06 | 11 (9 mitigate + 2 accept) | 11 | 0 | Orchestrator, L1 grep-depth verification against register authored at plan time (05-01-PLAN.md, 05-02-PLAN.md `<threat_model>` blocks) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-09-06
