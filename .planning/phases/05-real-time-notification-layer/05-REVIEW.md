---
phase: 05-real-time-notification-layer
reviewed: 2026-09-06T08:23:28Z
depth: standard
files_reviewed: 8
files_reviewed_list:
  - src/main/kotlin/org/example/muvimatchr/controller/LobbyController.kt
  - src/main/kotlin/org/example/muvimatchr/realtime/SessionEventPublisher.kt
  - src/main/kotlin/org/example/muvimatchr/realtime/WebSocketConfig.kt
  - src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt
  - src/main/kotlin/org/example/muvimatchr/voting/VoteService.kt
  - src/test/kotlin/org/example/muvimatchr/realtime/ReconnectReconciliationTest.kt
  - src/test/kotlin/org/example/muvimatchr/realtime/SessionStatusBroadcastTest.kt
  - src/test/kotlin/org/example/muvimatchr/support/StompTestSupport.kt
findings:
  critical: 0
  warning: 5
  info: 2
  total: 7
status: issues_found
---

# Phase 5: Code Review Report

**Reviewed:** 2026-09-06T08:23:28Z
**Depth:** standard
**Files Reviewed:** 8
**Status:** issues_found

## Summary

Reviewed the new `realtime/` package (`SessionEventPublisher`, `WebSocketConfig`), the `VoteService`/`VoteController` changes that wire the after-commit broadcast into the vote path, the STOMP integration tests, and the legacy `LobbyController` that was minimally touched (a nested class relocation) as part of this phase's diff.

The core new logic — `WebSocketConfig`'s inbound SEND-drop interceptor, `SessionEventPublisher`'s catch-all best-effort broadcast, and `VoteService`'s after-commit `TransactionSynchronization` registration — is sound and matches the documented design intent (fresh-read-then-broadcast, commit-gated push, no exception ever escaping the publisher). No correctness or security defects were found in that new code itself. The test suite (`ReconnectReconciliationTest`, `SessionStatusBroadcastTest`, `StompTestSupport`) is thorough and exercises real behavior end-to-end rather than mocking the broker.

Findings below are quality/maintainability issues in the new code (a package coupling smell between `voting` and `realtime`), one latent test-fixture footgun, and pre-existing issues in the legacy `LobbyController` file that remains reachable in production despite being flagged for removal in Phase 6. None rise to Critical: nothing here is a newly-introduced security hole, data-loss risk, or crash in the phase's actual delivered feature.

## Warnings

### WR-01: `voting` and `realtime` packages now depend on each other (package-level cycle)

**File:** `src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt:74-83`, `src/main/kotlin/org/example/muvimatchr/realtime/SessionEventPublisher.kt:1-8`

**Issue:** `VoteService` (package `voting`) now depends on `SessionEventPublisher` (package `realtime`), and `SessionEventPublisher` in turn imports `SessionVoteStatus` and the `toResponse()` mapping extension from `voting/VoteController.kt`. That makes `voting -> realtime -> voting` a package-level cycle. The shared mapper also lives inside a `@RestController` file, so a REST-layer file is now a load-bearing dependency of the WebSocket layer — the comment in `VoteController.kt` acknowledges this ("Top-level ... so SessionEventPublisher can map the same ... shape") but the placement still mixes concerns: a change to `VoteController.kt` for HTTP-only reasons can now affect what compiles in `realtime/`.

**Fix:** Extract `toResponse()` and the two response DTOs (`VoteStatusResponse`, `MovieLikeCountResponse`) into their own file (e.g., `voting/VoteStatusResponse.kt`) that both `VoteController` and `SessionEventPublisher` import. This keeps the shared shape in a neutral location and removes the controller file as an implicit dependency of the realtime package.

### WR-02: Unsynchronized shared mutable map in `LobbyController` — concurrent HTTP requests can corrupt it

**File:** `src/main/kotlin/org/example/muvimatchr/controller/LobbyController.kt:20`

**Issue:** `lobbies` is a plain `mutableMapOf<String, MutableMap<String, Boolean>>()` held as controller (singleton bean) state, mutated from `enterLobby`, `toggleReady`, and `switchLobby` — all invoked concurrently from different request threads with no synchronization. Concurrent structural mutation of a non-thread-safe `LinkedHashMap` (e.g., simultaneous `putIfAbsent`/`remove`/`[]=` calls, or an iteration via `lobbyUsers.values.all { it }` racing a concurrent `put`) can corrupt internal map state or throw a `ConcurrentModificationException`, producing intermittent 500s under real concurrent traffic. This file is explicitly flagged as scheduled for removal in Phase 6, but it remains live and reachable (`@GetMapping("/")` is the app's root route) for the duration of this phase.

**Fix:** At minimum wrap `lobbies` (and the per-lobby maps) in a `ConcurrentHashMap` / `Collections.synchronizedMap`, or accept and log the known risk explicitly in-line since removal is imminent. Given the file is slated for deletion, a one-line risk-acceptance comment is a reasonable alternative to a full fix.

### WR-03: Unchecked non-null session-attribute casts in `LobbyController` throw NPE on missing session state

**File:** `src/main/kotlin/org/example/muvimatchr/controller/LobbyController.kt:60-61,76-77,100`

**Issue:** `toggleReady`, `switchLobby`, and `continueToMovies` all do `session.getAttribute("lobbyId") as String` / `as String` for `username`. Kotlin's `as` cast on a `null` value against a non-nullable target type throws `NullPointerException` rather than returning `null`. If a client posts to `/toggleReady`, `/switchLobby`, or `/continue` on a session that never visited `/` (or whose session was invalidated via `/reset` but still has a stale form open in another tab), the request fails with an unhandled 500 instead of a graceful redirect to `/`.

**Fix:** Use `as? String` and redirect to `/` (or otherwise recover) when the attribute is absent:
```kotlin
val lobbyId = session.getAttribute("lobbyId") as? String ?: return "redirect:/"
val username = session.getAttribute("username") as? String ?: return "redirect:/"
```

### WR-04: `toResponse()` extension has no explicit visibility, leaking a controller-file symbol as public API

**File:** `src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt:74`

**Issue:** The top-level `fun SessionVoteStatus.toResponse()` has no visibility modifier, defaulting to `public`, so it is now part of the public surface of a file whose declared type (`VoteController`) is a REST controller. It's only actually needed by `VoteController` itself and `SessionEventPublisher`. Related to WR-01: once the mapper is extracted to its own file (or even left in place), scoping it to `internal` would prevent unrelated code elsewhere in the module from depending on a controller-adjacent symbol by accident.

**Fix:** Mark the function `internal fun SessionVoteStatus.toResponse()` (works today since `SessionEventPublisher` is in the same Gradle module), or move it per WR-01 and keep it `internal` there.

### WR-05: Fixed movie-ID range increment in test fixture can silently collide if `deckSize` grows

**File:** `src/test/kotlin/org/example/muvimatchr/support/StompTestSupport.kt:192-193`

**Issue:** `newSessionFixture` always advances the shared `movieIdRangeStart` `AtomicLong` by a fixed `1000` regardless of the requested `deckSize`:
```kotlin
val rangeStart = movieIdRangeStart.getAndAdd(1000)
val movieIds = (0 until deckSize).map { rangeStart + it }
```
Every current call site uses `deckSize` of 1 or 2, so this is safe today, but nothing enforces the invariant. A future test that passes `deckSize > 1000` would silently generate movie IDs that overlap the next fixture's range, causing sporadic cross-fixture pollution (a vote in one test's session appearing to belong to a movie in another test's pinned deck) that would be very difficult to root-cause from the resulting test failure.

**Fix:** Either derive the increment from the actual size (`movieIdRangeStart.getAndAdd(maxOf(deckSize.toLong(), 1000))`) or add a `require(deckSize <= 1000)` guard so a future violation fails fast with a clear message instead of silently colliding.

## Info

### IN-01: WebSocket topic subscription remains unauthenticated (already a documented, accepted risk)

**File:** `src/main/kotlin/org/example/muvimatchr/realtime/WebSocketConfig.kt:15-24`

**Issue:** No `ChannelInterceptor` validates a participant's bearer token on STOMP `CONNECT`/`SUBSCRIBE`, so any client that knows or observes a session's UUID (visible in every REST call's URL) can subscribe to `/topic/session/{sessionId}` and see that session's aggregate vote-progress and match results without proving membership. This is called out and deliberately accepted in `05-CONTEXT.md` D-01, so it is not a new defect introduced by this implementation — noted here only for completeness of the security pass, and to confirm the code matches the documented decision (it does: the inbound interceptor blocks `SEND` only, never `SUBSCRIBE`).

**Fix:** None required for this phase per D-01. Tracked as an accepted risk to revisit before a public deploy.

### IN-02: Duplicate explanation of the Jackson `isComplete`/`is`-prefix quirk in two files

**File:** `src/main/kotlin/org/example/muvimatchr/realtime/SessionEventPublisher.kt:20-26`, `src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt:92-96`

**Issue:** The same explanation of Kotlin's `isComplete()` getter being stripped to `complete` by Jackson bean introspection is documented at length in both files. Not a bug, but the duplication means a future change to one comment (or to the actual behavior, e.g. an ObjectMapper module upgrade) is easy to leave stale in the other location.

**Fix:** Keep one canonical explanation (suggest on the `VoteStatusResponse.isComplete` property itself, since that's where the `@JsonProperty` annotation lives) and have the other site reference it briefly instead of restating it.

---

_Reviewed: 2026-09-06T08:23:28Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
