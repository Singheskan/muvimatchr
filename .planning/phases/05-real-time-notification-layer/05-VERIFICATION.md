---
phase: 05-real-time-notification-layer
verified: 2026-09-06T08:30:51Z
status: human_needed
score: 15/15 must-haves verified
behavior_unverified: 0
overrides_applied: 0
deferred:
  - truth: "A participant who finishes early visually sees a live 'waiting on N of M' screen update in a browser (SC1's UI half)"
    addressed_in: "Phase 6"
    evidence: "Phase 6 success criteria: 'create/join a session, enter a display name, swipe through the deck, watch a live waiting screen, and land on results' — through the React SPA that does not exist yet"
  - truth: "When the last participant finishes, connected participants automatically transition to the results *view* (SC2's UI half)"
    addressed_in: "Phase 6"
    evidence: "Phase 6 success criteria: 'The results view displays the single best mutual match's poster, title, and where to watch it' and 'no dead end, blank screen, or manual refresh needed' — the results view itself is a Phase 6 artifact"
  - truth: "A reconnecting participant's browser client actually performs the REST reconciliation fetch on every reconnect rather than trusting a cached WS frame (SC3's client-behavior half)"
    addressed_in: "Phase 6"
    evidence: "05-CONTEXT.md and both plans' own 'Flagged assumptions' sections explicitly name this as a frontend obligation this phase cannot enforce, to be built in Phase 6's SPA"
human_verification:
  - test: "Start the application against the local compose Postgres and inspect the startup log for a STOMP endpoint registered at /ws with no fallback transport handler (no SockJS), and open templates/lobby.html in a browser to confirm its SockJS client no longer establishes a socket."
    expected: "Startup log shows a plain WebSocket STOMP endpoint at /ws; lobby.html's browser console shows a failed/absent SockJS connection rather than a live socket."
    why_human: "Requires reading real application startup log output and observing real browser network/console behavior — not visible via static source or unit/integration test inspection. This is 05-01-PLAN.md Task 2's own designated <human-check>, explicitly deferred to end-of-phase by the plan itself; this verification pass is that end-of-phase point."
  - test: "Walk the RTIME-03 story once by hand against a real running instance: open a STOMP connection (e.g. via a WS test client or browser devtools), kill it, record a vote from a different client/session, reconnect the first client, and confirm the reconnected socket stays silent while a REST GET /api/sessions/{id}/votes/status call returns the finished state."
    expected: "The reconnected STOMP connection receives nothing; the REST call returns the correct, current, completed status."
    why_human: "Requires driving a real client against a real running server interactively and observing absence-of-message plus REST response by hand. This is 05-02-PLAN.md Task 2's own designated <human-check>, explicitly deferred to end-of-phase by the plan itself; this verification pass is that end-of-phase point. The automated ReconnectReconciliationTest already proves the same property end-to-end via a real embedded server and real STOMP client (see truths #12–14 below); this item is the plan's own requested sanity confirmation on top of that automated proof, not yet performed as of this verification."
---

# Phase 5: Real-Time Notification Layer Verification Report

**Phase Goal:** Participants who are online see live status without refreshing, and reconnecting participants always land on the server's true current state rather than something stale.
**Verified:** 2026-09-06T08:30:51Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

This phase is scoped, by its own CONTEXT.md and both PLANs' explicit "Flagged assumptions" sections, to the **backend notification layer only** — no frontend exists yet (Phase 6, not yet executed, owns the SPA that will actually render a waiting screen or a results view). The three ROADMAP success criteria are phrased in end-user-observable language ("sees", "transition to the results view"), but the phase's own planning artifacts interpret them as: prove the server pushes the right signal, at the right time, to the right (and only the right) audience, and prove the server offers nothing stale to reconcile against. The UI-rendering halves of SC1–SC3 are deferred to Phase 6 with clear roadmap evidence (see `deferred` in frontmatter). This split is not a gap discovered during verification — it is documented, load-bearing project design, present in `05-CONTEXT.md`, both `PLAN.md`s, and both `SUMMARY.md`s before this phase was ever submitted for verification.

Within that backend scope, every observable truth was independently verified against the actual codebase — not taken from SUMMARY.md's claims.

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A STOMP client subscribed to `/topic/session/{id}` receives a push within seconds of another participant's vote, having issued no request of its own | ✓ VERIFIED | `SessionStatusBroadcastTest#recording a vote pushes a status frame to a subscribed client` — independently re-run, passed (0.231s) |
| 2 | Pushed JSON is field-for-field identical to `GET /api/sessions/{id}/votes/status`, including the `isComplete` field name | ✓ VERIFIED | `SessionStatusBroadcastTest#the pushed frame JSON is identical to the REST status JSON...` — asserts equal key sets/values plus explicit `isComplete`/not-`complete` string checks; `VoteController.kt` line 74–83 confirms `toResponse()` is shared by both paths |
| 3 | A broadcast is only observable after the vote's transaction commits — a status fetch the instant a push arrives can never see an older state | ✓ VERIFIED | `VoteService.kt:46-51` registers `broadcastStatus` inside `TransactionSynchronization.afterCommit()`, guarded by `isSynchronizationActive()`; `SessionStatusBroadcastTest#a REST status fetch issued the instant a push arrives...` passed |
| 4 | A recorded vote is never rolled back or blocked because a broadcast could not be delivered | ✓ VERIFIED | `SessionEventPublisher.kt:31-37` wraps `convertAndSend` in `try/catch(Exception)`, logs, returns normally; combined with the after-commit registration (item 3), the DB write has already committed before this code path runs, so an exception here is structurally incapable of touching the vote transaction |
| 5 | Exactly one STOMP handshake endpoint (`/ws`) exists, with no SockJS fallback and no widened origin policy | ✓ VERIFIED | `grep -rl 'EnableWebSocketMessageBroker' src/main/kotlin/` → 1 file; `grep -rn 'withSockJS'` → no match; `grep -rnE 'setAllowedOrigin'` → no match; `WebSocketConfig.kt:33-35` registers only `/ws` |
| 6 | A frame sent by a connected client toward a session topic never reaches that topic's subscribers | ✓ VERIFIED | `WebSocketConfig.kt:37-47` `ChannelInterceptor.preSend` returns `null` for `StompCommand.SEND`; `SessionStatusBroadcastTest#a forged client SEND to a session topic reaches no subscriber` passed (1.228s) |
| 7 | The prototype `/lobby` WebSocket layer no longer exists, and the app still compiles, starts, and passes the pre-existing suite | ✓ VERIFIED | `config/WebSocketConfig.kt` and `controller/WebSocketController.kt` confirmed absent from the filesystem; both deletion commits (`c548226`) present in `git log`; full `./gradlew test` reported green by the orchestrator immediately before this verification, and this verifier independently re-ran the 10 phase-5-specific tests (`SessionStatusBroadcastTest` + `ReconnectReconciliationTest`) fresh — 10/10 passed, 0 failures, 0 errors |
| 8 | As three participants finish in turn, a subscribed client's frames show `finishedCount` advancing 1→2→3 toward a stable `activeCount`, i.e. "waiting on N of M" is derivable from pushes alone | ✓ VERIFIED | `SessionStatusBroadcastTest#waiting count advances by exactly one with each sequential finish` — asserts equality (not bound) 1, then 2, then 3; passed (0.278s) |
| 9 | The push following the last participant's final vote reports `isComplete: true` and carries the unanimous `matchedMovieIds` | ✓ VERIFIED | `SessionStatusBroadcastTest#the completion push reaches every connected client with an identical payload` asserts `isComplete` true and `matchedMovieIds` contains the pinned movie id |
| 10 | Every client currently subscribed to a session's topic receives the same completion push — fan-out, not single-connection delivery | ✓ VERIFIED | Same test: two independent `StompSession`s both receive the completion frame; asserts the two parsed payloads are `equal()` (no per-connection personalization) |
| 11 | A client subscribed to one session's topic never receives another session's status | ✓ VERIFIED | `SessionStatusBroadcastTest#a subscriber to one session receives nothing from another session and does receive its own` — negative wait then positive receipt, ruling out a dead subscription |
| 12 | A client disconnected while votes were recorded receives no replay when it resubscribes — no per-client delivery state, no last-known-status buffer | ✓ VERIFIED | `ReconnectReconciliationTest#a client disconnected across completion receives no replay on reconnect` — fresh session's queue yields nothing within bounded wait after a full completion happened while disconnected; passed (1.784s) |
| 13 | A reconnecting client's REST status fetch returns exactly the state the last push carried; REST reconciliation requires the participant's token even though the topic itself does not | ✓ VERIFIED | `ReconnectReconciliationTest#REST reconciliation lands exactly on the last broadcast state and requires authentication` — asserts equal key sets/values between last witnessed push and REST body, asserts unauthenticated fetch ≠ 200 |
| 14 | The notification layer is never load-bearing for correctness — a session reaches the correct completed state even with zero STOMP clients ever connected | ✓ VERIFIED | `ReconnectReconciliationTest#a session completes correctly even with no client ever subscribed` — asserts both `computeStatus()` and an authenticated REST fetch report completion with no listener ever present |
| 15 | The broadcast payload carries no per-participant identity or per-participant vote choices — only session-level aggregate status | ✓ VERIFIED | `VoteStatusResponse` (`VoteController.kt:87-100`) fields are `sessionId, deckSize, activeCount, finishedCount, isComplete, matchedMovieIds, likeCounts[movieId, likeCount]` — no participant id, name, token, or per-participant vote anywhere in the shape actually published by `SessionEventPublisher` |

**Score:** 15/15 truths verified (0 present-behavior-unverified)

### Deferred Items

Frontend-observable UI halves of the roadmap's SC1–SC3 wording, addressed by the not-yet-executed Phase 6 (see frontmatter `deferred`). This split is documented in the phase's own planning artifacts (05-CONTEXT.md, both PLANs' "Flagged assumptions" sections) prior to execution, not a gap discovered here.

| # | Item | Addressed In | Evidence |
|---|------|-------------|----------|
| 1 | A human visually sees a live waiting-screen update | Phase 6 | ROADMAP.md Phase 6 SC1: "...watch a live waiting screen, and land on results — with no Thymeleaf server-rendered page anywhere in the flow" |
| 2 | Connected participants automatically transition to a *results view* | Phase 6 | ROADMAP.md Phase 6 SC3: "The results view displays the single best mutual match's poster, title, and where to watch it" (the view itself doesn't exist until Phase 6) |
| 3 | The browser client performs a REST reconcile fetch on every reconnect | Phase 6 | 05-CONTEXT.md / both PLANs' "Flagged assumptions": "Whether the Phase 6 client actually performs the REST fetch on every reconnect... is a frontend obligation this phase cannot enforce" |

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/kotlin/org/example/muvimatchr/realtime/WebSocketConfig.kt` | Session-scoped STOMP endpoint, simple broker, client-SEND rejection | ✓ VERIFIED | Exists, `@Configuration` + `@EnableWebSocketMessageBroker`, matches plan exactly |
| `src/main/kotlin/org/example/muvimatchr/realtime/SessionEventPublisher.kt` | Thin broadcast pipe to `/topic/session/{sessionId}` | ✓ VERIFIED | Exists, single `@Service`, one constructor param, one public method, no repository/aggregation dependency |
| `src/test/kotlin/org/example/muvimatchr/support/StompTestSupport.kt` | Real-embedded-server STOMP client harness | ✓ VERIFIED | Exists, `@SpringBootTest(RANDOM_PORT)`, real `WebSocketStompClient`, real fixture builders via `SessionService`/`ParticipantService` |
| `src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt` | End-to-end proof of push/REST parity, progression, fan-out, isolation | ✓ VERIFIED | 7 `@Test` methods, all independently re-run and passed |
| `src/test/kotlin/org/example/muvimatchr/realtime/ReconnectReconciliationTest.kt` | No-replay, REST reconciliation, correctness-without-listeners proof | ✓ VERIFIED | 3 `@Test` methods, all independently re-run and passed |
| `src/main/kotlin/org/example/muvimatchr/config/WebSocketConfig.kt` (prototype) | Deleted | ✓ VERIFIED | Confirmed absent from filesystem |
| `src/main/kotlin/org/example/muvimatchr/controller/WebSocketController.kt` (prototype) | Deleted | ✓ VERIFIED | Confirmed absent from filesystem |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `VoteService.recordVote()` | `SessionEventPublisher.broadcastStatus` | `afterCommit` transaction synchronization | ✓ WIRED | `VoteService.kt:46-51`; count of `broadcastStatus` call sites in `src/main/kotlin/` = 1, inside `VoteService.kt` |
| `SessionEventPublisher` | `SimpMessagingTemplate.convertAndSend` | destination `/topic/session/{sessionId}` | ✓ WIRED | `SessionEventPublisher.kt:33` |
| `SessionEventPublisher` | `VoteController.toResponse()` | shared `SessionVoteStatus.toResponse()` mapping | ✓ WIRED | `SessionEventPublisher.kt:4` imports `toResponse` from `voting`; `VoteController.kt:74-83` declares it top-level/public |
| `SessionStatusBroadcastTest` / `ReconnectReconciliationTest` | `WebSocketConfig` | real `WebSocketStompClient` handshake against `ws://localhost:{port}/ws` | ✓ WIRED | `StompTestSupport.kt:74-77` `connect()` targets `/ws`; all 10 tests independently re-run, passed |
| `ReconnectReconciliationTest` | `VoteController` REST status endpoint | authenticated `GET /votes/status` compared field-for-field against last broadcast | ✓ WIRED | `ReconnectReconciliationTest.kt:79-85, 138-173` |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Phase-5 realtime test suite (10 tests) actually passes when independently re-executed by the verifier, not just per SUMMARY.md's claim | `./gradlew test --tests "*.realtime.ReconnectReconciliationTest" --tests "*.realtime.SessionStatusBroadcastTest"` | Exit 0; JUnit XML: `SessionStatusBroadcastTest` 7/7 passed (0 failures, 0 errors), `ReconnectReconciliationTest` 3/3 passed (0 failures, 0 errors) | ✓ PASS |
| No deleted prototype files remain on disk | `test -f .../config/WebSocketConfig.kt`; `test -f .../controller/WebSocketController.kt` | Both absent | ✓ PASS |
| No SockJS fallback / no origin-widening call anywhere in main sources | `grep -rn 'withSockJS'`; `grep -rnE 'setAllowedOrigin'` | No matches | ✓ PASS |
| No last-known-status/cache/replay-buffer field anywhere in `realtime/` | `grep -rnE 'lastStatus\|statusCache\|lastKnown\|replayBuffer' src/main/kotlin/org/example/muvimatchr/realtime/` | No matches | ✓ PASS |
| No debt markers (`TBD`/`FIXME`/`XXX`/`TODO`/`HACK`/`PLACEHOLDER`) in phase-touched files | `grep -rn` across all files this phase created/modified | No matches | ✓ PASS |
| All 4 phase task commits actually present in git history | `git log --oneline` | `c548226`, `d85ea4a`, `98e70c9`, `e77ae6d` all present with matching messages and diffs | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plan(s) | Description | Status | Evidence |
|-------------|-----------------|-------------|--------|----------|
| RTIME-01 | 05-01, 05-02 | Live "waiting on N of M" over WebSocket | ✓ SATISFIED (backend) | Truths #1, #8; UI-observation half deferred to Phase 6 |
| RTIME-02 | 05-02 | Auto-transition to results when last participant finishes | ✓ SATISFIED (backend) | Truths #9, #10; the results-view transition itself deferred to Phase 6 |
| RTIME-03 | 05-01, 05-02 | Reconnecting participant reconciles via REST, never stale | ✓ SATISFIED (backend) | Truths #2, #3, #12, #13, #14; client's actual reconnect-time fetch behavior deferred to Phase 6 |

No orphaned requirements: REQUIREMENTS.md maps only RTIME-01/02/03 to Phase 5, and all three appear in at least one plan's `requirements` frontmatter field.

### Anti-Patterns Found

None classified as blocking. `05-REVIEW.md` (independently read, not just trusted) recorded 0 Critical / 5 Warning / 2 Info findings against this phase's diff. Verifier's own scan confirms:

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `voting/VoteController.kt` / `realtime/SessionEventPublisher.kt` | — | `voting` ↔ `realtime` package-level import cycle (WR-01 in 05-REVIEW.md) | ⚠️ Warning | Maintainability smell, not a correctness or security defect; independently confirmed by reading both files' imports |
| `controller/LobbyController.kt` | 20, 60-61, 76-77, 100 | Unsynchronized shared mutable map + unchecked session-attribute casts (WR-02, WR-03) | ⚠️ Warning | Pre-existing prototype code this phase only minimally touched (relocating `LobbyEvent`); explicitly scheduled for Phase 6 removal per 05-CONTEXT.md D-03's stated scope, not introduced or worsened by this phase |
| `voting/VoteController.kt:74` | — | `toResponse()` extension has no explicit visibility modifier (WR-04) | ⚠️ Warning | Public-by-default leak of a controller-adjacent symbol; cosmetic, no behavior impact |
| `support/StompTestSupport.kt:192-193` | — | Fixed movie-ID range increment could collide if `deckSize` ever exceeds 1000 (WR-05) | ⚠️ Warning | Test-only, no current call site is near this bound |

No unreferenced `TBD`/`FIXME`/`XXX` debt markers found in any file this phase created or modified — debt-marker gate does not fire.

### Human Verification Required

Two items, both the phase's own plans' explicitly-designated `<human-check>` blocks, deferred by the plans themselves to "end of phase" — this verification is that end-of-phase point, so they are surfaced here rather than silently dropped:

#### 1. Startup log `/ws` endpoint confirmation + dead `lobby.html` socket

**Test:** Start the application against the local compose Postgres and inspect the startup log for a STOMP endpoint registered at `/ws` with no fallback transport; open `templates/lobby.html` in a browser and confirm its SockJS client no longer opens a socket.
**Expected:** Startup log shows a plain WebSocket STOMP endpoint at `/ws`; `lobby.html`'s SockJS connection fails or never establishes (the endpoint no longer accepts SockJS handshakes since Task 1 removed `withSockJS()`).
**Why human:** Requires reading live application log output and observing real browser network behavior — this is 05-01-PLAN.md Task 2's own designated `<human-check>`, not yet performed per 05-01-SUMMARY.md's own "Next Phase Readiness" note.

#### 2. Manual RTIME-03 walkthrough against a running instance

**Test:** Open a STOMP connection, kill it, vote from a different client/session, reconnect the first client, and confirm the reconnected socket stays silent while a REST `GET /api/sessions/{id}/votes/status` call returns the finished state.
**Expected:** No frame arrives on the reconnected socket; REST call returns the correct, current, completed status.
**Why human:** Requires driving a real client interactively against a real running server. This is 05-02-PLAN.md Task 2's own designated `<human-check>`, not yet performed per 05-02-SUMMARY.md's own "Next Phase Readiness" note. Note: `ReconnectReconciliationTest` already automates and passes the identical property end-to-end (truths #12–14) — this item is the plans' own requested human sanity-check layered on top of that automated proof, not a substitute for it.

### Gaps Summary

No gaps. Every must-have truth, artifact, and key link for this phase's backend scope is present, wired, and independently confirmed to pass (10/10 realtime tests re-run fresh by this verifier, 0 failures/errors; all 4 task commits present in git history; both prototype files confirmed deleted; no debt markers; no replay/cache state in `realtime/`).

The phase is held at `human_needed` rather than `passed` solely because of the two plan-designated `<human-check>` items above, which both plans explicitly deferred to "end of phase" and which have not yet been performed against a real running instance (confirmed via both SUMMARY.md's own "Pending todo" / "Next Phase Readiness" sections). These are procedural sanity-checks on top of already-passing automated proofs, not indicators of missing functionality.

---

_Verified: 2026-09-06T08:30:51Z_
_Verifier: Claude (gsd-verifier)_
