---
phase: 05-real-time-notification-layer
plan: 01
subsystem: realtime
tags: [spring-websocket, stomp, simplebroker, kotlin, transaction-synchronization]

# Dependency graph
requires:
  - phase: 04-vote-match-engine
    provides: "SessionVoteStatus / MatchAggregationService.computeStatus, the race-free read model this phase pushes verbatim"
provides:
  - "Session-scoped STOMP notification layer at /ws, broker prefix /topic, replacing the prototype /lobby socket"
  - "SessionEventPublisher.broadcastStatus -- the single after-commit broadcast trigger from VoteService.recordVote"
  - "A real-embedded-server STOMP test harness (StompTestSupport) and its first end-to-end proof (SessionStatusBroadcastTest)"
affects: [06-frontend-spa]

# Actuals (#2632)
actuals:
  tokens: 8700
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "After-commit broadcast via TransactionSynchronizationManager.registerSynchronization, guarded by isSynchronizationActive() -- never an inline push inside the open transaction"
    - "WS and REST payloads share one mapping function (SessionVoteStatus.toResponse(), promoted to a top-level extension) so they cannot drift by construction"
    - "STOMP test harness targets ByteArray payload type against the client's default SimpleMessageConverter -- avoids StringMessageConverter's strict text/plain mime-type filtering, which silently drops application/json-tagged broadcast frames"
    - "Marker-frame subscribeAndAwaitReady handshake closes the client-subscribe-vs-server-push race deterministically, without a fixed-duration sleep"

key-files:
  created:
    - src/main/kotlin/org/example/muvimatchr/realtime/WebSocketConfig.kt
    - src/main/kotlin/org/example/muvimatchr/realtime/SessionEventPublisher.kt
    - src/test/kotlin/org/example/muvimatchr/support/StompTestSupport.kt
    - src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt
  modified:
    - src/main/kotlin/org/example/muvimatchr/voting/VoteService.kt
    - src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt
    - src/main/kotlin/org/example/muvimatchr/controller/LobbyController.kt

key-decisions:
  - "Client-side STOMP test harness uses the WebSocketStompClient default SimpleMessageConverter with a ByteArray payload type request, not StringMessageConverter -- StringMessageConverter's mime-type matching rejects any frame whose content-type isn't text/plain, and the broker's Jackson-based converter tags an object broadcast's frame application/json, so it would silently drop every real status push while a same-type raw-string test frame kept passing (discovered live during this plan, not anticipated in the plan text's simpler fallback description)."
  - "Subscription readiness is proven via a bounded marker-frame round trip (publish a uniquely-tagged string to the same topic, poll for it, retry until seen) rather than a fixed sleep -- STOMP gives no client-visible ack that a SUBSCRIBE has been processed server-side, and the client's subscribe() call returns as soon as the frame is queued for send, not once registered."
  - "java.net.http.HttpClient used for the REST-vs-push parity fetch instead of Spring Boot's TestRestTemplate -- TestRestTemplate does not exist anywhere on this project's Spring Boot 4.1.1 classpath (confirmed empty across spring-boot-test, spring-boot-test-autoconfigure, and spring-boot-restclient-test); the JDK HTTP client needed zero new dependencies and kept this phase's 'no new dependency' goal intact."

patterns-established:
  - "Realtime broadcast trigger pattern: compute status inside the transaction, capture to a local val, register the actual publish as an afterCommit transaction synchronization -- the value pushed is a fresh committed read, never a pre-commit snapshot."
  - "STOMP integration test pattern for this codebase: StompTestSupport extends PostgresTestSupport with @SpringBootTest(RANDOM_PORT), reuses the singleton Postgres container, and builds session/participant fixtures through real service beans (SessionService/ParticipantService) rather than direct repository saves, so raw bearer tokens are obtainable."

requirements-completed: [RTIME-01, RTIME-03]  # Both shared with 05-02's frontmatter; actual completion gated by requirements.ready-ids at execution time -- see note below.

coverage:
  - id: D1
    description: "Session-scoped STOMP layer (/ws handshake, /topic broker, client-SEND rejection) replaces the prototype /lobby socket; the prototype config/controller are deleted and LobbyController's one dependent is repaired"
    requirement: "RTIME-01"
    verification:
      - kind: integration
        ref: "./gradlew build (full pre-existing suite green against the new broker config)"
        status: pass
      - kind: other
        ref: "source assertions: exactly one @EnableWebSocketMessageBroker, no withSockJS, no setAllowedOrigin, exactly one broadcastStatus call site, afterCommit present in VoteService.kt"
        status: pass
    human_judgment: false
  - id: D2
    description: "Recording a vote pushes exactly one after-commit VoteStatusResponse frame to /topic/session/{sessionId}, field-for-field identical (including isComplete) to the REST status endpoint's JSON, never precedes the commit it describes, and cannot be forged by a connected client"
    requirement: "RTIME-03"
    verification:
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt#recording a vote pushes a status frame to a subscribed client"
        status: pass
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt#the pushed frame JSON is identical to the REST status JSON, including the isComplete field name"
        status: pass
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt#a REST status fetch issued the instant a push arrives never reports an older state than the push"
        status: pass
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt#a forged client SEND to a session topic reaches no subscriber"
        status: pass
    human_judgment: false
  - id: D3
    description: "Running application, started against the local compose Postgres, logs a STOMP endpoint at /ws with no fallback transport handler, and the prototype lobby.html page no longer opens a socket"
    verification: []
    human_judgment: true
    rationale: "Plan's own <verify> designates this a <human-check> deferred to end-of-phase (05-CONTEXT.md/PLAN.md explicitly scope it there, since Phase 6's SPA is what will actually exercise the endpoint from a browser) -- not yet performed as of this plan's completion; tracked as a pending todo for the end of Phase 5."

duration: 30min
completed: 2026-09-06
status: complete
---

# Phase 5 Plan 1: Real-Time STOMP Notification Layer Summary

**Session-scoped STOMP broadcast layer (Spring `SimpleBroker` on `/topic`, endpoint `/ws`) that pushes the exact REST `VoteStatusResponse` JSON to subscribers after every committed vote, proven end-to-end with a real embedded server and a real `WebSocketStompClient`.**

## Performance

- **Duration:** ~30 min
- **Tasks:** 2
- **Files modified:** 7 (3 created production, 2 deleted, 2 modified production; 2 created test)

## Accomplishments
- Replaced the prototype `/lobby` SockJS-fallback socket with a real session-scoped STOMP layer: `/ws` handshake, `enableSimpleBroker("/topic")`, no application destination prefix, no origin widening, and a `ChannelInterceptor` that drops every client `SEND` frame (closing T-05-03).
- `SessionEventPublisher` is a dumb pipe (`@Service`, one constructor param, one public method) that maps `SessionVoteStatus` through the same `toResponse()` extension the REST endpoint uses and publishes to `/topic/session/{sessionId}`, swallowing and logging any exception so a broadcast failure can never surface as a failed vote.
- `VoteService.recordVote()` registers the broadcast as a `TransactionSynchronization.afterCommit()` callback, guarded by `isSynchronizationActive()` — the pushed status is always a committed read, never a pre-commit snapshot a concurrent REST fetch could contradict.
- Deleted `config/WebSocketConfig.kt` and `controller/WebSocketController.kt`; repaired `LobbyController.kt`'s one compile dependency by relocating `LobbyEvent` into that file (unrelated Thymeleaf prototype code otherwise untouched, per 05-CONTEXT.md D-03's scope).
- Built `StompTestSupport`, a reusable real-embedded-server STOMP harness (`@SpringBootTest(RANDOM_PORT)` over the existing singleton Postgres container), and `SessionStatusBroadcastTest`'s four end-to-end tests: a vote produces a push; the pushed JSON is byte-for-byte identical to the REST status JSON including the `isComplete` wire name; a REST fetch on push receipt is never older than the push; and a forged client `SEND` reaches no subscriber.

## Task Commits

1. **Task 1: Replace the prototype lobby socket with a session-scoped STOMP layer** - `c548226` (feat)
2. **Task 2: Prove one real vote pushes one real frame, with JSON identical to the REST status body** - `d85ea4a` (test)

**Plan metadata:** _pending — this commit_

## Files Created/Modified
- `src/main/kotlin/org/example/muvimatchr/realtime/WebSocketConfig.kt` - session-scoped STOMP config, endpoint `/ws`, broker `/topic`, client-SEND rejection
- `src/main/kotlin/org/example/muvimatchr/realtime/SessionEventPublisher.kt` - the single broadcast pipe to `/topic/session/{sessionId}`
- `src/main/kotlin/org/example/muvimatchr/voting/VoteService.kt` - after-commit broadcast registration in `recordVote`
- `src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt` - `SessionVoteStatus.toResponse()` promoted to a top-level extension
- `src/main/kotlin/org/example/muvimatchr/controller/LobbyController.kt` - own `LobbyEvent` declared, repairing the deletion's compile break
- `src/main/kotlin/org/example/muvimatchr/config/WebSocketConfig.kt` - deleted (prototype)
- `src/main/kotlin/org/example/muvimatchr/controller/WebSocketController.kt` - deleted (prototype)
- `src/test/kotlin/org/example/muvimatchr/support/StompTestSupport.kt` - real STOMP client harness + session/participant fixture builder
- `src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt` - the four end-to-end proofs

## Decisions Made
- See `key-decisions` in frontmatter: SimpleMessageConverter + ByteArray payload type over StringMessageConverter (mime-type mismatch discovered live); marker-frame subscription-readiness handshake instead of a sleep; JDK `HttpClient` instead of the unavailable `TestRestTemplate`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] `TestRestTemplate` does not exist on this project's Spring Boot 4.1.1 classpath**
- **Found during:** Task 2 (writing `SessionStatusBroadcastTest`)
- **Issue:** The plan's read_first/action text (and Spring Boot 3-era convention) assumes `org.springframework.boot.test.web.client.TestRestTemplate` is available via `spring-boot-starter-test`. It is not: confirmed absent (searched every class in the local Gradle module cache) from `spring-boot-test`, `spring-boot-test-autoconfigure`, and even the standalone `spring-boot-restclient-test` module (which exists in 4.1.1 but only provides `MockRestServiceServer`/`RestClient` test support, not `TestRestTemplate`). This is a real Spring Boot 4 modularization gap for this project's exact dependency set, not a typo.
- **Fix:** Used `java.net.http.HttpClient` (JDK-built-in, zero new dependency) to issue the authenticated REST GET against the running embedded server for the pushed-vs-REST parity and ordering assertions (Tests B and C).
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt`
- **Verification:** All four tests pass on two consecutive runs (`./gradlew test --tests "*.realtime.SessionStatusBroadcastTest"`, second with `--rerun-tasks`); full `./gradlew test` suite green.
- **Committed in:** `d85ea4a` (Task 2 commit)

**2. [Rule 1 - Bug] StringMessageConverter silently dropped every real broadcast frame in the test harness**
- **Found during:** Task 2 (first test run — all vote-push assertions timed out with zero exceptions logged anywhere)
- **Issue:** The plan's action text specifies setting the client's message converter to `StringMessageConverter`, with a documented fallback to a `ByteArray` payload type "if the frame arrives as null". In practice the frame doesn't arrive as null to inspect — `StringMessageConverter`'s mime-type matching (`text/plain` only) causes Spring's `DefaultStompSession` to skip invoking the frame handler entirely whenever the server tags a frame `application/json` (which the broker's Jackson-based converter does for any non-String broadcast payload, i.e. every real `VoteStatusResponse` push). A same-type raw-string test frame (used for a manual debug probe) passed through fine, which is what made the mismatch non-obvious at first — it looked like a subscription-timing race, not a converter filtering bug. Diagnosed by tracing byte-code for `AbstractMessageConverter.supportsMimeType` and confirming `SimpleMessageConverter` (the client's actual pre-`StringMessageConverter` default) performs zero mime-type filtering and just requires payload/target-class assignability.
- **Fix:** Left the client's default `SimpleMessageConverter` in place (no `setMessageConverter` call) and requested `ByteArray::class.java` as the subscription's payload type — this always returns the raw wire bytes regardless of content-type, decoded to UTF-8 in the frame handler. Also switched the forgery test's client `send()` call to pass raw bytes (`String.toByteArray()`) rather than a bare `String`, since `SimpleMessageConverter.toMessage()` performs no outbound serialization either and a raw String payload reaching the STOMP encoder throws `ClassCastException`.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/support/StompTestSupport.kt`, `src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt`
- **Verification:** All four tests pass; confirmed via a temporary WARN-level debug probe inside `SessionEventPublisher.broadcastStatus` (reverted before commit) that `convertAndSend` was completing normally server-side the whole time — the bug was entirely client-side converter filtering.
- **Committed in:** `d85ea4a` (Task 2 commit)

**3. [Rule 1 - Bug] Client `subscribe()` can lose the first real broadcast to a subscription-registration race**
- **Found during:** Task 2 (same debugging session as #2, before the converter bug was isolated)
- **Issue:** `StompSession.subscribe()` returns as soon as the SUBSCRIBE frame is queued for send, not once the broker has actually registered the subscription. A test that subscribes and then immediately triggers a vote (a synchronous, very fast DB round trip) can have the vote's broadcast committed and published before the broker finishes processing the SUBSCRIBE, silently losing the frame — with no exception anywhere to signal it.
- **Fix:** Added `subscribeAndAwaitReady` to `StompTestSupport`: subscribes, then repeatedly publishes a uniquely-tagged disposable marker string to the same topic via the server-side `SimpMessagingTemplate` (never a client `SEND`, which the interceptor would drop anyway) and polls for it, bounded at 5 seconds. Once the marker round-trips, the subscription is proven live and the real test logic proceeds. This is a deterministic bounded retry, not a fixed sleep.
- **Files modified:** `src/test/kotlin/org/example/muvimatchr/support/StompTestSupport.kt`, `src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt`
- **Verification:** All four tests pass consistently across repeated runs (`--rerun-tasks`).
- **Committed in:** `d85ea4a` (Task 2 commit)

---

**Total deviations:** 3 auto-fixed (1 blocking-dependency substitution, 2 bugs found and fixed during test-harness development)
**Impact on plan:** All three were necessary to make Task 2's verification actually pass against this project's real Spring Boot 4.1.1 / Kotlin 2.3 dependency versions — none represent scope creep against the plan's stated objective, acceptance criteria, or threat model. No production code in Task 1 was touched by any of these fixes.

## Issues Encountered
- Initial diagnosis of deviations #2 and #3 above required an ad-hoc manual debug test (`DebugStompTest.kt`, not committed) that isolated a raw-string publish/subscribe round trip to confirm basic STOMP plumbing worked before the converter mime-type mismatch was identified as the actual cause. Deleted before the final commit; not part of the shipped test suite.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- `SessionEventPublisher` and the `/topic/session/{sessionId}` contract are ready for Phase 6's SPA to subscribe to.
- **Pending todo (tracked for end of Phase 5, not blocking this plan):** the plan's Task 2 `<human-check>` — starting the app against local compose Postgres and confirming the startup log shows a `/ws` STOMP endpoint with no fallback transport, and that `templates/lobby.html` no longer opens a socket — has not yet been performed. Per 05-CONTEXT.md/the plan itself, this is explicitly deferred to end-of-phase since Phase 6 is what will exercise the endpoint from an actual browser.
- 05-02 (reconnect reconciliation) can proceed; it shares `RTIME-01`/`RTIME-03` with this plan in REQUIREMENTS.md, so those requirement IDs will only flip to fully-closed once 05-02 also completes (handled by the `requirements.ready-ids` gate, not a gap in this plan's work).

---
*Phase: 05-real-time-notification-layer*
*Completed: 2026-09-06*

## Self-Check: PASSED

All created files verified present on disk; both task commits (`c548226`, `d85ea4a`) verified present in `git log`; both deleted prototype files verified absent.
