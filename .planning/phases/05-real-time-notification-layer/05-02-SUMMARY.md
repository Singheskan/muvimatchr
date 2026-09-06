---
phase: 05-real-time-notification-layer
plan: 02
subsystem: realtime
tags: [spring-websocket, stomp, simplebroker, kotlin, integration-testing]

# Dependency graph
requires:
  - phase: 05-real-time-notification-layer
    provides: "05-01's session-scoped STOMP layer (SessionEventPublisher, WebSocketConfig, StompTestSupport, SessionStatusBroadcastTest's first four proofs) -- this plan extends both the production-adjacent test class and the shared harness, and adds no new production symbol"
provides:
  - "Proof that a subscribed client can derive 'waiting on N of M' purely from the push sequence as each participant finishes in turn (RTIME-01 server half)"
  - "Proof that the completion push fans out identically to every connected client of a session, and to no other session's clients (RTIME-02 server half, T-05-08)"
  - "Proof that a client disconnected across a session's completion receives no replay on reconnect, and reconciles in one authenticated REST call onto exactly the state the last broadcast carried (RTIME-03, T-05-09, T-05-10)"
  - "Proof that the notification layer is never load-bearing for correctness -- a session completes correctly with zero STOMP clients ever connected (T-05-11)"
  - "A fixed StompTestSupport harness bug: two-concurrent-subscriber tests no longer cross-contaminate each other's queues with readiness-marker frames"
affects: [06-frontend-spa]

# Actuals (#2632)
actuals:
  tokens: 6636
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Shared readiness-marker sink in StompTestSupport, separate from each subscription's real message queue -- required once a test holds two or more concurrently subscribed StompSessions against the same topic, since a broadcast marker is topic-wide and would otherwise land in every other subscriber's queue too"
    - "Sequential-only vote driving for progression assertions: SimpleBroker dispatch ordering across genuinely simultaneous commits is explicitly not asserted anywhere, only ordering across strictly sequential finishes"

key-files:
  created:
    - src/test/kotlin/org/example/muvimatchr/realtime/ReconnectReconciliationTest.kt
  modified:
    - src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt
    - src/test/kotlin/org/example/muvimatchr/support/StompTestSupport.kt

key-decisions:
  - "Readiness-marker frames are routed to a dedicated shared LinkedBlockingQueue (readyMarkerFrames) inside StompTestSupport rather than the per-subscription queue returned to callers -- discovered live in Task 1's fan-out test: a second client's subscribeAndAwaitReady handshake broadcasts to the whole topic, and without this split it silently corrupted an already-subscribed first client's queue with a non-JSON marker string, causing a JSON parse exception rather than a clean assertion failure."
  - "Task 2 reuses 05-01's java.net.http.HttpClient approach for REST calls (TestRestTemplate confirmed absent from this project's Spring Boot 4.1.1 classpath) rather than re-investigating the dependency gap."

patterns-established:
  - "Multi-subscriber STOMP test pattern: when a test needs 2+ concurrently subscribed StompSessions on the same topic, use subscribeAndAwaitReady for each (now cross-contamination-safe) rather than a single shared queue."

requirements-completed: [RTIME-01, RTIME-02, RTIME-03]

coverage:
  - id: D1
    description: "A subscribed client can derive 'waiting on N of M' from the push sequence alone as three participants finish in turn -- finishedCount advances 1, 2, 3 against a stable activeCount of 3, with isComplete false until the last finish"
    requirement: "RTIME-01"
    verification:
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt#waiting count advances by exactly one with each sequential finish"
        status: pass
    human_judgment: false
  - id: D2
    description: "The completion push (isComplete true, finishedCount == activeCount, matchedMovieIds populated) reaches every connected client of a session with an identical, non-personalized payload"
    requirement: "RTIME-02"
    verification:
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt#the completion push reaches every connected client with an identical payload"
        status: pass
    human_judgment: false
  - id: D3
    description: "A subscriber to one session's topic receives nothing while a different session completes, and does receive its own session's frame once its own session is voted in -- proves per-session destination isolation, not a dead subscription (T-05-08)"
    verification:
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt#a subscriber to one session receives nothing from another session and does receive its own"
        status: pass
    human_judgment: false
  - id: D4
    description: "A client explicitly disconnected before a session's completing votes are recorded receives no replayed or remembered frame on reconnect/resubscribe -- the server holds no last-known-status buffer and no per-client delivery ledger"
    requirement: "RTIME-03"
    verification:
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/realtime/ReconnectReconciliationTest.kt#a client disconnected across completion receives no replay on reconnect"
        status: pass
    human_judgment: false
  - id: D5
    description: "An authenticated REST /votes/status fetch by the reconnecting participant returns a body field-for-field identical to the last broadcast the witness client saw, including isComplete and a non-empty matchedMovieIds; the same fetch without an Authorization header does not return 200, pinning the deliberate topic-open/REST-authenticated asymmetry (T-05-09)"
    requirement: "RTIME-03"
    verification:
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/realtime/ReconnectReconciliationTest.kt#REST reconciliation lands exactly on the last broadcast state and requires authentication"
        status: pass
    human_judgment: false
  - id: D6
    description: "A session records votes to completion with zero STOMP clients ever connected and still reports correct completion state via both MatchAggregationService.computeStatus() directly and an authenticated REST fetch -- the notification layer is never load-bearing for correctness (T-05-11)"
    verification:
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/realtime/ReconnectReconciliationTest.kt#a session completes correctly even with no client ever subscribed"
        status: pass
    human_judgment: false
  - id: D7
    description: "No last-known-status, cache, or replay-buffer field exists anywhere under src/main/kotlin/org/example/muvimatchr/realtime/ (negative source assertion, T-05-10)"
    verification:
      - kind: other
        ref: "! grep -rnE 'lastStatus|statusCache|lastKnown|replayBuffer' src/main/kotlin/org/example/muvimatchr/realtime/"
        status: pass
    human_judgment: false
  - id: D8
    description: "End-of-phase human walkthrough of the RTIME-03 story against a real running app (open a STOMP connection, kill it, vote from another client, reconnect, confirm silence + correct REST status) -- the plan's own <human-check>, deliberately deferred to when Phase 6's SPA can exercise the endpoint from an actual browser"
    verification: []
    human_judgment: true
    rationale: "Plan's own <verify> designates this a <human-check> explicitly deferred to end-of-phase (05-CONTEXT.md/PLAN.md scope it there since Phase 6's SPA is what will actually drive the endpoint from a browser). D4-D6 already prove the same story end-to-end via a real embedded server and real STOMP client; this item is the human sanity-check on top, not yet performed, and is bundled with 05-01's identically-deferred Task 2 human-check (see 05-01-SUMMARY.md) as a single end-of-phase manual verification item carried into Phase 6."

duration: 25min
completed: 2026-09-06
status: complete
---

# Phase 5 Plan 2: Reconnect Reconciliation and Multi-Client Broadcast Fan-Out Summary

**Three new integration tests proving finishedCount progression, identical multi-client completion fan-out, and session isolation on `SessionStatusBroadcastTest`; a new `ReconnectReconciliationTest` proving no-replay-on-reconnect, REST-reconciliation parity with an auth asymmetry, and notification-independent correctness -- plus a fix to the shared STOMP test harness that a two-concurrent-subscriber test exposed.**

## Performance

- **Duration:** ~25 min
- **Tasks:** 2
- **Files modified:** 3 (1 created test, 2 modified test/harness)

## Accomplishments
- `SessionStatusBroadcastTest` gained three tests: sequential three-participant, two-movie-deck progression asserting `finishedCount` equals exactly 1, then 2, then 3 with `activeCount` fixed at 3 and `isComplete` false until the last finish (RTIME-01); a two-client fan-out test asserting both connections receive an `isComplete: true` frame with matching `matchedMovieIds`, and that the two parsed payloads are `equal()` to each other, closing off any risk of per-connection personalization (RTIME-02, T-05-02); and a cross-session isolation test proving a session-A subscriber hears nothing while session B completes, then does receive session A's own frame once voted (T-05-08).
- New `ReconnectReconciliationTest` (3 tests): a witness/reconnecting-client pattern proves a client disconnected before the completing votes receives **nothing** on reconnect and resubscribe — no last-known-status buffer, no per-client delivery ledger; a REST reconciliation test proves the authenticated `/votes/status` body is field-for-field identical to the witness's last recorded push, and that the same fetch without an `Authorization` header does not return 200 (T-05-09); and a no-subscriber test proves a session completes correctly (via both `computeStatus()` and REST) with zero STOMP clients ever connected, so the notification layer can never become load-bearing for correctness (T-05-11).
- Negative source assertion confirmed: no `lastStatus`/`statusCache`/`lastKnown`/`replayBuffer`-shaped field exists anywhere under `src/main/kotlin/org/example/muvimatchr/realtime/` (T-05-10).
- Fixed a genuine `StompTestSupport` harness bug (Rule 1) discovered by Task 1's fan-out test: the readiness-marker handshake used to prove a subscription is live broadcasts to the whole topic, so a second client's marker was landing in an already-subscribed first client's queue and corrupting it with a non-JSON string. Routed all marker frames to a dedicated shared sink, separate from every subscription's real message queue.
- Full `./gradlew test` and `./gradlew build` both green, including two consecutive `--rerun-tasks` runs of each new test class.

## Task Commits

1. **Task 1: Waiting on N of M advances with every finish, and the completion push reaches every connected client** - `98e70c9` (test)
2. **Task 2: A client that missed messages while disconnected gets no replay and reconciles to the true state over REST** - `e77ae6d` (test)

**Plan metadata:** _pending — this commit_

## Files Created/Modified
- `src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt` - three added tests (progression, fan-out, isolation), four 05-01 tests unchanged
- `src/test/kotlin/org/example/muvimatchr/support/StompTestSupport.kt` - readiness-marker frames now routed to a dedicated shared sink, isolating them from per-subscription "real" queues
- `src/test/kotlin/org/example/muvimatchr/realtime/ReconnectReconciliationTest.kt` - new file, three tests (no-replay, REST reconciliation + auth asymmetry, correctness-without-listeners)

## Decisions Made
- See `key-decisions` in frontmatter: the readiness-marker sink split (found live, not anticipated in the plan text) and reuse of 05-01's `java.net.http.HttpClient` for REST calls.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] StompTestSupport's readiness-marker handshake corrupted a second concurrently-subscribed client's queue**
- **Found during:** Task 1 (writing the fan-out test with two simultaneous `StompSession`s subscribed to the same topic)
- **Issue:** `subscribeAndAwaitReady` proves a subscription is live by repeatedly broadcasting a uniquely-tagged marker string to the session's topic via the server-side `SimpMessagingTemplate` and polling the *same* queue it returns to the caller for that marker. This works fine for a single subscriber, but the marker broadcast is topic-wide: when a second client subscribes to the same topic and runs its own handshake, its marker also lands in the *first* client's already-returned queue. The first test's subsequent `queue.poll()` calls, expecting real `VoteStatusResponse` JSON, instead sometimes received the marker string, which failed to parse as JSON (`StreamReadException`) rather than failing as a clean assertion.
- **Fix:** Added a dedicated `readyMarkerFrames` shared queue inside `StompTestSupport`. The frame handler installed by `subscribeToSessionTopic` now checks each incoming frame's text for the `__stomp_test_ready__` prefix and routes marker frames there instead of the per-subscription queue; `subscribeAndAwaitReady` polls `readyMarkerFrames` for its own marker instead of the returned queue. Every "real" queue handed back to test code is now guaranteed free of handshake noise regardless of how many other clients are concurrently subscribing to the same topic.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/support/StompTestSupport.kt`
- **Verification:** `SessionStatusBroadcastTest`'s fan-out test (and all seven tests in the class) pass on two consecutive runs, the second with `--rerun-tasks`; full `./gradlew test` and `./gradlew build` green.
- **Committed in:** `98e70c9` (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (test-harness bug, discovered and fixed during Task 1's own verification, before any commit was made)
**Impact on plan:** Necessary to make the two-concurrent-subscriber fan-out test pass at all against this project's real STOMP/SimpleBroker stack; no production code (`src/main/kotlin/`) was touched by this fix, and the fix strictly increases test isolation rather than changing behavior under test.

## Issues Encountered
None beyond the harness bug documented above, which was found and fixed within Task 1's own execution before that task's commit.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Phase 5's full server-side real-time contract is now proven: a single push per vote, WS/REST payload parity, forgery rejection, cross-session isolation, N-of-M progression, multi-client fan-out, no-replay-on-reconnect, REST reconciliation with an auth asymmetry, and correctness independent of any listener. Phase 6's SPA has a fully-specified `/topic/session/{sessionId}` contract and `/api/sessions/{sessionId}/votes/status` reconciliation endpoint to build against.
- **Pending todo, carried to Phase 6 (not blocking this plan or this phase's other work):** two `<human-check>` items remain unperformed against a real locally-running app (not just the automated embedded-server tests): (1) 05-01 Task 2's confirmation that the startup log shows a `/ws` STOMP endpoint with no fallback transport and that `templates/lobby.html` no longer opens a socket; (2) this plan's Task 2 confirmation of the reconnect story end-to-end against a real running instance. Both are explicitly deferred by their own plans to whenever Phase 6's SPA first exercises these endpoints from an actual browser — bundling them there is more useful than a standalone manual pass now, since Phase 6 will be driving the same endpoints anyway.
- All three of this phase's requirements (RTIME-01, RTIME-02, RTIME-03) are now fully proven on the server side per the coverage table above. The three flagged assumptions from 05-CONTEXT.md/this plan (a human seeing the waiting screen update, the frontend auto-transitioning to results, the frontend actually performing a reconnect-time REST fetch) remain frontend obligations for Phase 6, not gaps in this phase's backend work.

---
*Phase: 05-real-time-notification-layer*
*Completed: 2026-09-06*

## Self-Check: PASSED

All three files verified present on disk (`ReconnectReconciliationTest.kt`, `SessionStatusBroadcastTest.kt`, `StompTestSupport.kt`); both task commits (`98e70c9`, `e77ae6d`) verified present in `git log`.
