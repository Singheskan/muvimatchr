---
phase: 06-frontend-spa
plan: 04
subsystem: frontend
tags: [react, typescript, stompjs, tanstack-query, vitest, react-testing-library, tdd]

# Dependency graph
requires:
  - phase: 06-frontend-spa
    provides: "06-01: single-JAR SPA scaffold, apiFetch/ApiError, useBootstrap, useSessionToken; 06-02: GET .../votes/roster; 06-03: SwipeScreen, D-05 token-in-URL lesson"
  - phase: 05-realtime
    provides: "STOMP broker at /ws, SessionEventPublisher broadcasting to /topic/session/{sessionId} on every vote/status change"
provides:
  - "frontend/src/routing/resolveScreen.ts, useRouteGuard.ts -- the single server-authoritative screen resolver every session route now redirects through"
  - "frontend/src/realtime/useSessionSocket.ts -- the STOMP client half of Phase 5's reconnect-reconcile contract"
  - "frontend/src/routes/WaitScreen.tsx -- the real waiting screen, replacing the JoinScreen placeholder"
affects: [06-05]

# Actuals
actuals:
  tokens: 189263
  tasks: 3
  commits: 4

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "resolveScreen(hasToken, status, myVotedCount) -> Screen is a single pure function every guarded route (SwipeScreen, WaitScreen) feeds into useRouteGuard -- one redirect table instead of each screen's own bespoke conditionals, and completion is read only from status.isComplete (P-02), never derived client-side."
    - "useRouteGuard's navigate() always forwards location.search (D-05) -- verified in this plan's own tests, and confirmed live: no bare-pathname navigate() was introduced anywhere in this plan's new code."
    - "onFrame (socket message handler) never deserializes the frame body -- it only invalidates the status/roster/bootstrap query keys and lets a REST refetch supply the actual truth (P-04, PITFALLS.md Pitfall 5). Exactly one client.subscribe() call site, inside onConnect, so every reconnect gets a fresh subscription rather than a stacked duplicate."
    - "The D-06 inactivity rule (findActiveParticipantIds, COALESCE(last_voted_at, created_at) > now() - timeout) falls back to a participant's created_at when they have never voted -- a real trap for live manual testing with a slow, conversational pace: a seeded participant who hasn't voted yet can silently drop out of the active/completion count well before a human tester gets around to voting for them, at the default 60s timeout. Worth knowing before seeding future live-checkpoint fixtures for multi-participant scenarios."

key-files:
  created:
    - frontend/src/routing/resolveScreen.ts
    - frontend/src/routing/resolveScreen.test.ts
    - frontend/src/routing/useRouteGuard.ts
    - frontend/src/realtime/useSessionSocket.ts
    - frontend/src/realtime/useSessionSocket.test.ts
    - frontend/src/routes/WaitScreen.tsx
    - frontend/src/routes/WaitScreen.test.tsx
    - frontend/src/routes/wait.css
    - frontend/src/session/useSessionStatus.ts
    - frontend/src/session/useRoster.ts
  modified:
    - frontend/src/App.tsx
    - frontend/src/api/client.ts
    - frontend/src/api/types.ts
    - frontend/src/routes/SwipeScreen.tsx
    - frontend/src/routes/SwipeScreen.test.tsx

key-decisions:
  - "Executed via an isolated worktree agent for Tasks 1-2 (both auto/TDD); the agent's own worktree branch had forked from main before 06-03 existed, so it fast-forward-merged main into itself first (verified as a strict ancestor, non-destructive) before starting work. Confirmed no history was lost; the orchestrator then fast-forward-merged the completed branch back onto main and removed the worktree."
  - "Task 3's blocking human-verify checkpoint (live two-client progression + kill-the-network reconnect walkthrough) could only be run partially. Items 1-3 (live progression, named roster, auto-transition on completion) were confirmed pass across three separate live two-participant sessions seeded directly via SQL (same TMDB-unavailable workaround as Phases 3, 5, and 06-03). Items 4-6 (reconnect indicator, reconnect-reconcile, no duplicate frames) were not verified this session: Chrome DevTools' Offline throttle turned out to both fail to reliably sever an already-open WebSocket AND block the STOMP client's own reconnect attempts while checked, making it unsuitable for testing reconnect behavior either way, and the developer chose to skip a retry with a cleaner method (real WiFi toggle or closing the WS connection directly) rather than continue troubleshooting this session. This is a genuine gap, not a pass -- see Deviations below."
  - "Discovered mid-checkpoint that a participant who has never cast a vote is only counted 'active' within voting.inactivity-timeout-seconds (default 60s) of their own created_at (VoteRepository.findActiveParticipantIds' COALESCE fallback) -- a real hazard for conversational-pace live testing that silently marked a fixture session complete with the wrong participant excluded, twice, before being diagnosed. Restarted the dev server once with --voting.inactivity-timeout-seconds=3600 (a runtime arg only, not a code or config file change) to remove time pressure for the remaining live checks."

patterns-established:
  - "Live-checkpoint fixture sessions for any multi-participant scenario should either seed all participants' created_at at the moment testing actually begins (not when the SQL is first run) or the dev server should run with a generously widened voting.inactivity-timeout-seconds -- the default 60s window does not survive a normal conversational testing pace."

requirements-completed: []

coverage:
  - id: D1
    description: "Every session route (SwipeScreen, WaitScreen) resolves its screen from server-computed status alone (resolveScreen) and redirects there without dropping the token, gated on a ready flag so no route flashes through 'join' while its own data is still loading"
    requirement: RSLT-01
    verification:
      - kind: unit
        ref: "frontend/src/routing/resolveScreen.test.ts (9 cases covering every branch: no token, null status, isComplete, deckSize/myVotedCount wait branch, deckSize=0 guard, swipe fallback)"
        status: pass
      - kind: unit
        ref: "useRouteGuard tests asserting navigate preserves location.search (D-05) and no-ops when resolved === currentScreen"
        status: pass
      - kind: human
        ref: "Live two-client walkthrough: Alice's finish correctly routed her to /wait; her own roster named Bob and reflected his later completion"
        status: pass
    human_judgment: true
  - id: D2
    description: "The STOMP client connects once per sessionId, exposes connecting/connected/reconnecting state, and treats every inbound frame purely as a signal to invalidate status/roster/bootstrap query keys and refetch via REST -- never as a payload to render directly (P-04)"
    requirement: RSLT-01
    verification:
      - kind: unit
        ref: "frontend/src/realtime/useSessionSocket.test.ts (4 cases: connect/subscribe lifecycle, onFrame invoked on connect and on message, reconnecting state on close/error, single subscribe call site)"
        status: pass
      - kind: unit
        ref: "grep gate: exactly one client.subscribe( call site in useSessionSocket.ts"
        status: pass
    human_judgment: false
  - id: D3
    description: "WaitScreen renders the named roster (D-10) with per-participant Done/Waiting/Away states, and RTIME-02's auto-transition off the waiting screen fires when the session completes"
    requirement: RSLT-01
    verification:
      - kind: unit
        ref: "frontend/src/routes/WaitScreen.test.tsx (6 cases)"
        status: pass
      - kind: human
        ref: "Live: Bob's tab auto-navigated to /results (still 06-05's placeholder) the instant his final vote completed the session, with no manual action"
        status: pass
    human_judgment: true
  - id: D4
    description: "A client whose WebSocket drops shows a reconnecting indicator, and reconnecting reconciles the session's true state via a REST refetch with no duplicate roster rows and no manual page reload required"
    requirement: RSLT-01
    verification:
      - kind: human
        ref: "Attempted live via Chrome DevTools Offline throttle; inconclusive (throttle also blocks the client's own reconnect attempts) and not retried with a cleaner method this session"
        status: not_verified
    human_judgment: true
---

# Phase 6 Plan 4: Server-Authoritative Routing & Waiting Screen Summary

**Replaced every session route's ad hoc redirect logic with one server-authoritative screen resolver, and built the real STOMP-backed waiting screen -- but the checkpoint's network-drop verification (items 4-6) could not be completed live this session and remains an open gap.**

## Performance

- **Duration:** ~11 min agent execution (Tasks 1-2, isolated worktree) + an extended live-checkpoint session (multiple session reseeds, a URL-mixup detour, and an inactivity-timeout diagnosis)
- **Tasks:** 2/3 automated tasks complete; Task 3 (checkpoint) partially verified
- **Commits:** 4 (all from the worktree agent, fast-forward merged)

## Accomplishments

- `resolveScreen({ hasToken, status, myVotedCount })` is the single pure function deciding `'join' | 'swipe' | 'wait' | 'results'` from server-computed `VoteStatusResponse` alone -- completion is read only from `status.isComplete` (P-02), never derived from local vote counts or roster flags.
- `useRouteGuard` wraps `resolveScreen` with the actual redirect: gated on a `ready` flag so no screen flashes through `'join'` while its own bootstrap/status queries are still loading, and every `navigate()` call forwards `location.search` so the D-05 token-in-URL scheme survives every guard-triggered redirect. Verified by a dedicated test and confirmed live: no new bare-pathname `navigate()` was introduced anywhere in this plan.
- `useSessionSocket` connects a STOMP client to `/ws`, exposes `connecting | connected | reconnecting`, and treats every inbound frame as a pure invalidation signal (never deserializes or applies the payload directly) for exactly three query keys (`status`, `roster`, `bootstrap`) -- one `client.subscribe()` call site inside `onConnect` so a reconnect can never stack a duplicate subscription.
- `WaitScreen` renders the named roster from `useRoster` (D-10) with Done/Waiting/Away markers, and both existing tasks' automated tests plus a live two-client walkthrough confirmed RTIME-02's auto-transition: the last participant's completing vote moved every connected client off the waiting screen with no manual action.
- `SwipeScreen` was updated to route through the new shared guard instead of its own bespoke no-token redirect.

## Task Commits

1. **Task 1: Server-authoritative route guard for every session route**
   - `9a85f73` (test, RED) -- failing tests for `resolveScreen`/`useRouteGuard`
   - `63fe16d` (feat, GREEN) -- `resolveScreen.ts`, `useRouteGuard.ts`, `useSessionStatus.ts`, `useRoster.ts`, `SwipeScreen.tsx` updated
2. **Task 2: STOMP client with reconnect-reconcile, and the named waiting roster**
   - `0c9d916` (test, RED) -- failing tests for `useSessionSocket`/`WaitScreen`
   - `b79aa65` (feat, GREEN) -- `useSessionSocket.ts`, `WaitScreen.tsx`, `wait.css`, `App.tsx` route
3. **Task 3: Human check** (checkpoint, partially closed -- see below)

## Files Created/Modified

- `frontend/src/routing/resolveScreen.ts`, `resolveScreen.test.ts` (new)
- `frontend/src/routing/useRouteGuard.ts` (new)
- `frontend/src/realtime/useSessionSocket.ts`, `useSessionSocket.test.ts` (new)
- `frontend/src/routes/WaitScreen.tsx`, `WaitScreen.test.tsx`, `wait.css` (new)
- `frontend/src/session/useSessionStatus.ts`, `useRoster.ts` (new)
- `frontend/src/api/client.ts`, `frontend/src/api/types.ts` -- `fetchStatus`, `fetchRoster`
- `frontend/src/App.tsx` -- `/s/:code/wait` routed to `WaitScreen`
- `frontend/src/routes/SwipeScreen.tsx`, `SwipeScreen.test.tsx` -- migrated to `useRouteGuard`

## Human Verification (Task 3 checkpoint) -- PARTIAL

Run live against `./gradlew bootRun` across three seeded two-participant sessions (deck rows and participants inserted directly via SQL -- TMDB_API_TOKEN still unavailable on this dev machine, same workaround as Phases 3, 5, 06-03):

1. **Live progression (RTIME-01):** PASS. Alice finishing her deck moved her to `/wait`; her roster correctly reflected Bob's later completion.
2. **Named roster (D-10):** PASS. Participants appear by their typed display name (Alice/Bob), not an anonymous count.
3. **Auto-transition (RTIME-02):** PASS. Bob's final vote completing the session auto-navigated his own tab to `/results` (still 06-05's placeholder) with no manual action.
4. **Reconnect indicator:** NOT VERIFIED. Chrome DevTools' Offline throttle did not produce a visible reconnecting indicator, but this method is unreliable for the test (see below) -- inconclusive, not a confirmed fail.
5. **Reconnect reconcile (RTIME-03):** NOT VERIFIED. After re-enabling network, the tab only reached the correct state after a manual reload, which the item explicitly disallows -- but since the network-kill method itself was compromised, this doesn't confirm a real defect either.
6. **No duplicate frames:** NOT VERIFIED (not reached; blocked on items 4-5).

**Why 4-6 are inconclusive, not failed:** Chrome DevTools' Offline throttle does not reliably close an already-open WebSocket connection, and *also* blocks the STOMP client's own `reconnectDelay`-driven reconnect attempts while it's checked -- so neither "does the app detect a drop" nor "does the app recover" could be genuinely exercised by this method. The developer chose to stop rather than retry with a real WiFi toggle or DevTools' "Close connection" on the `/ws` row.

This session also surfaced (and worked around) an unrelated fixture-timing trap: `VoteRepository.findActiveParticipantIds` counts a never-voted participant as active only within `voting.inactivity-timeout-seconds` (default 60s) of their `created_at`. The normal conversational pace of live manual testing blew past that window twice, silently marking sessions complete with the "wrong" participant excluded before this was diagnosed. Restarted the dev server once with `--voting.inactivity-timeout-seconds=3600` (a runtime argument, not a committed config change) to remove time pressure for the rest of the walkthrough.

## Decisions Made

- Accepted a partial checkpoint close rather than continuing to iterate on reconnect-test methodology this session, per the developer's explicit choice ("skip this, not relevant"). Items 4-6 are logged as an open gap, not silently dropped.
- Chased two false alarms during the checkpoint before finding real signal: (1) briefly suspected a token-propagation race in `SwipeScreen`'s route guard after landing on an unexpected "Welcome back" screen -- reverted immediately once the developer confirmed the visited URL was actually the bare `/s/{code}` join-screen route, not `/swipe`, i.e. no bug; (2) a second "Welcome back" sighting turned out to be a stale browser tab still pointed at an entirely different, already-completed session from earlier in the checkpoint. Neither produced a code change.

## Deviations from Plan

- Task 3's `<resume-signal>` calls for "a pass/fail for each of the six items" before the plan can be considered fully verified. Only 3 of 6 have a definitive pass; 3 are recorded as not verified with the specific reason (compromised test method), per the developer's explicit decision to stop here. This SUMMARY documents the plan as executed with this gap open rather than fabricating a pass for the untested items.

## Issues Encountered

- Chrome DevTools Offline throttling is not a valid tool for testing WebSocket reconnect behavior in this app -- worth remembering for any future live network-drop verification (use a real OS-level network toggle, or DevTools' "Close connection" on the specific WS row, instead).
- `voting.inactivity-timeout-seconds`' `created_at` fallback for never-voted participants (60s default) is incompatible with a slow, conversational live-testing pace for multi-participant scenarios -- future live checkpoints involving 2+ participants should seed with a wide timeout override from the start.

## User Setup Required

None.

## Next Phase Readiness

- Items 4-6 of this plan's checkpoint remain unverified. This does not block 06-05 (results view), which does not depend on reconnect behavior, but should be revisited before considering RTIME-03 genuinely proven -- worth a dedicated retest with a real network toggle before shipping.
- RSLT-01 remains **not yet marked complete** by design -- 06-05 also declares it; the shared-ID gate withholds `Complete` until every declaring plan has a `*-SUMMARY.md`.
- `/s/:code/results` still resolves to the `JoinScreen` placeholder -- 06-05 replaces it with the real results screen.

---
*Phase: 06-frontend-spa*
*Completed: 2026-09-09*
