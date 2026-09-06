# Phase 5: Real-Time Notification Layer - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-06
**Phase:** 5-Real-Time Notification Layer
**Areas discussed:** WS subscription security, Legacy WebSocket code, Manual verification approach, Broadcast trigger scope (all resolved by Claude's first-pass recommendation, per explicit user direction — see note below)

---

**User's direction:** When presented with the four gray areas below for turn-by-turn discussion, the user responded "you can always take your first assumption" — instructing Claude to proceed with its own default recommendation on each, rather than debate options interactively. The options below are what was presented; the "Selected" column reflects Claude's chosen default, not a user pick among alternatives.

## WS subscription security

| Option | Description | Selected |
|--------|-------------|----------|
| Defer token validation | Subscribing requires knowing the session UUID only, no STOMP-level token check — accepted risk, consistent with Phase 2/3's existing "no rate limiting" stance | ✓ |
| Validate token on CONNECT/SUBSCRIBE | New `ChannelInterceptor` checks a bearer token before allowing subscription — closes the gap but is new auth machinery for a single-instance friend-group deployment | |

**Claude's choice:** Defer (D-01, D-02 in CONTEXT.md) — topic keyed by session UUID (higher entropy than the join code), not by the participant token. Documented as an accepted risk to revisit before any public deploy, matching the existing STATE.md accepted-risk log pattern from Phases 2/3.

---

## Legacy WebSocket code

| Option | Description | Selected |
|--------|-------------|----------|
| Delete now | Remove the prototype `WebSocketConfig.kt`/`WebSocketController.kt` (`/lobby` toy topic) since this phase fully supersedes them | ✓ |
| Leave in place | Keep the old files untouched, add the new `realtime/` package alongside | |

**Claude's choice:** Delete (D-03) — matches PROJECT.md's established "rebuild, don't patch" stance for this codebase; the old files are unrelated to sessions and would only cause confusion once real session-scoped WS wiring exists.

---

## Manual verification approach

| Option | Description | Selected |
|--------|-------------|----------|
| Automated STOMP-client tests only | A Java/Kotlin `WebSocketStompClient` integration test proves the behavior, no manual UI step — matches Phases 1-4's backend-first pattern | ✓ |
| Also build a throwaway manual test page | A minimal HTML/JS page to open in two tabs and eyeball the live push behavior | |

**Claude's choice:** Automated only (D-04) — Phase 6 is where a human first interacts with this over a real browser; building even a throwaway UI here would be new scope this phase doesn't need to prove correctness.

---

## Broadcast trigger scope

| Option | Description | Selected |
|--------|-------------|----------|
| Broadcast only after a vote is recorded | `VoteService.recordVote()` is the sole trigger; idle-participant timeouts self-correct on next read (REST or vote), no separate re-broadcast | ✓ |
| Also broadcast on idle-timeout transitions | Would need a scheduled job to detect "someone just crossed the inactivity threshold" and push proactively | |

**Claude's choice:** Vote-triggered only (D-05) — the app has no scheduled-job infrastructure today, and per `04-CONTEXT.md` D-07, idle status is always recomputed live on read, so introducing a timer-driven broadcast would be new infrastructure for a case that already self-heals on the next check.

---

## Claude's Discretion

- Exact `realtime/` package shape (`SessionEventPublisher.kt` wrapping `SimpMessagingTemplate`).
- WS payload shape — full `SessionVoteStatus`/`VoteStatusResponse` reuse vs. a dedicated DTO.
- No SockJS registration (plain WebSocket only), per STACK.md's explicit recommendation against it for this project's modern-browser target.

## Deferred Ideas

None — discussion stayed within phase scope. WS-level token authentication was raised and explicitly accepted as a deferred *risk*, not a new deferred feature (see D-01).
