# Phase 5: Real-Time Notification Layer - Pattern Map

**Mapped:** 2026-09-06
**Files analyzed:** 5 (2 new, 1 modified, 2 deleted)
**Analogs found:** 4 / 5 (2 files are pure deletions with no analog needed)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|-----------------|---------------|
| `src/main/kotlin/org/example/muvimatchr/realtime/WebSocketConfig.kt` (new) | config | event-driven | `src/main/kotlin/org/example/muvimatchr/config/WebSocketConfig.kt` (to be deleted) | exact (structural rewrite, not extension) |
| `src/main/kotlin/org/example/muvimatchr/realtime/SessionEventPublisher.kt` (new) | service (thin publisher) | event-driven / pub-sub | `src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt` (role: injected read-only service called by `VoteService`) | role-match |
| `src/main/kotlin/org/example/muvimatchr/voting/VoteService.kt` (modified — add broadcast call) | service | CRUD + event-driven trigger | itself (existing file, in-place edit) | exact |
| `src/main/kotlin/org/example/muvimatchr/config/WebSocketConfig.kt` (delete) | config | event-driven | n/a — deletion only | n/a |
| `src/main/kotlin/org/example/muvimatchr/controller/WebSocketController.kt` (delete) | controller | event-driven | n/a — deletion only | n/a |

## Pattern Assignments

### `src/main/kotlin/org/example/muvimatchr/realtime/WebSocketConfig.kt` (new)

**Analog:** `src/main/kotlin/org/example/muvimatchr/config/WebSocketConfig.kt` (full file, 21 lines) — the legacy prototype config being deleted. Reuse its Spring wiring shape but change the destination scheme and drop SockJS per CONTEXT.md D-01/D-02 and STACK.md's "no SockJS" guidance.

**Full existing pattern (all 21 lines — this file is being deleted, but its Spring config shape is exactly what the new file should structurally mirror):**
```kotlin
package org.example.muvimatchr.config

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig : WebSocketMessageBrokerConfigurer {

    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic")
        config.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws").withSockJS()
    }
}
```

**What to change, concretely:**
- New package: `org.example.muvimatchr.realtime` (not `config`) — per ARCHITECTURE.md's `realtime/` package layout.
- `enableSimpleBroker("/topic")` — keep; `/topic/session/{sessionId}` is the broadcast destination this phase uses (D-02). No `/app` prefix is needed unless a client-to-server STOMP message path is added — this phase is server-push only (`SessionEventPublisher` calls `convertAndSend` directly, never receives a `@MessageMapping`), so `setApplicationDestinationPrefixes("/app")` can likely be dropped, and there is no `@MessageMapping`-annotated controller in this phase (unlike the legacy `WebSocketController`).
- `registry.addEndpoint("/ws")` — keep the same `/ws` handshake path (no requirement in CONTEXT.md to change it), but drop `.withSockJS()` per STACK.md's explicit "no SockJS fallback, modern-browser target" recommendation and CONTEXT.md's Claude's Discretion note.
- No auth/handshake interceptor is registered in v1 (D-01 — explicitly deferred, not part of this phase's scope).

### `src/main/kotlin/org/example/muvimatchr/realtime/SessionEventPublisher.kt` (new)

**Analog for injection/call-site shape:** `src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt` — a `@Service`, constructor-injected, called by `VoteService`, holding no mutable state (same shape this new publisher should have: a thin, stateless, injected collaborator).

**Analog excerpt — service class shell + constructor injection** (`MatchAggregationService.kt` lines 15-20):
```kotlin
@Service
class MatchAggregationService(
    private val sessionRepository: SessionRepository,
    private val sessionService: SessionService,
    private val voteRepository: VoteRepository,
) {
```

**Concrete shape for the new file** (per ARCHITECTURE.md's `realtime/` sketch and Pattern 3 "fresh-read-then-broadcast"):
```kotlin
package org.example.muvimatchr.realtime

import org.example.muvimatchr.voting.SessionVoteStatus
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SessionEventPublisher(
    private val messagingTemplate: SimpMessagingTemplate,
) {
    fun broadcastStatus(sessionId: UUID, status: SessionVoteStatus) {
        messagingTemplate.convertAndSend("/topic/session/$sessionId", status)
    }
}
```
- Topic key is the session UUID per D-02, matching the existing REST path convention `/api/sessions/{sessionId}/...` (see `VoteController.kt` line 20/34/58 `@RequestMapping("/api/sessions")` + `@PathVariable sessionId: UUID`).
- Payload shape: CONTEXT.md's "Claude's Discretion" leaves DTO reuse open; reusing `SessionVoteStatus` directly (the same object `VoteService.recordVote()` already returns, see below) is simplest and matches Pattern 3's "send the full fresh read" recommendation — avoids introducing a parallel WS-only DTO. If Jackson serialization of the raw domain object is undesired, mirror `VoteController.toResponse()`'s mapping to `VoteStatusResponse`/`MovieLikeCountResponse` (`VoteController.kt` lines 69-78) instead, for wire-format parity with the REST endpoint clients already reconcile against.
- No business logic, no repository access — this class must stay a "dumb pipe" per ARCHITECTURE.md line 96 ("realtime/ is deliberately thin ... it must never own business state or be queried for vote data").

### `src/main/kotlin/org/example/muvimatchr/voting/VoteService.kt` (modified)

**This is the exact call site to modify.** Full current file (30 lines) already read in full — no re-read needed:

```kotlin
package org.example.muvimatchr.voting

import org.example.muvimatchr.session.SessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class VoteService(
    private val sessionRepository: SessionRepository,
    private val voteRepository: VoteRepository,
    private val matchAggregationService: MatchAggregationService,
) {
    @Transactional
    fun recordVote(sessionId: UUID, participantId: UUID, movieId: Long, choice: VoteChoice): SessionVoteStatus {
        sessionRepository.lockForUpdate(sessionId)
        voteRepository.upsertVote(UUID.randomUUID(), sessionId, participantId, movieId, choice.name)
        return matchAggregationService.computeStatus(sessionId)
    }
}
```

**Required edit, concretely:**
1. Add constructor param `private val sessionEventPublisher: SessionEventPublisher` (import `org.example.muvimatchr.realtime.SessionEventPublisher`).
2. After `val status = matchAggregationService.computeStatus(sessionId)` is captured (rename the inline return to a local val), call `sessionEventPublisher.broadcastStatus(sessionId, status)`, then `return status`.
3. **Placement matters (load-bearing):** per D-05 and Pitfall 2 already-solved-by-locking, the broadcast call should happen *after* the transactional write path completes its read (i.e., still fine to be inside the `@Transactional` method, since Pattern 3 requires a fresh DB read — which `computeStatus` already performs inside the same locked transaction — not a second, uncoordinated broadcast path). Do not add any second broadcast call site elsewhere (e.g. do not also broadcast from `VoteController`) — `VoteService.recordVote()` is the single trigger per D-05.
4. No error-handling changes needed — this method has no try/catch today (errors propagate to `VoteController`'s existing guard/exception structure); a `SimpMessagingTemplate.convertAndSend` failure is not expected to roll back the vote transaction (broadcast is best-effort UX polish, not correctness-load-bearing, per ARCHITECTURE.md Anti-Pattern 2). Do not wrap the broadcast call in a way that could roll back the already-committed vote write.

### `src/main/kotlin/org/example/muvimatchr/config/WebSocketConfig.kt` (delete) and `src/main/kotlin/org/example/muvimatchr/controller/WebSocketController.kt` (delete)

No analog needed — straightforward deletion per D-03. Both files were read in full above; confirm no other file references `LobbyEvent`, `/topic/lobbyUpdates`, or `/app/lobby` before deleting (a quick grep for `lobbyUpdates`/`LobbyEvent`/`WebSocketController` across `src/` is a cheap pre-delete safety check the executor should run).

---

## Shared Patterns

### Constructor injection + `@Service`/`@Configuration` conventions
**Source:** `src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt` lines 15-20, `src/main/kotlin/org/example/muvimatchr/voting/VoteService.kt` lines 8-13
**Apply to:** `SessionEventPublisher.kt`, `realtime/WebSocketConfig.kt`
All services in this codebase use Kotlin primary-constructor injection with `private val` params, no field injection, no `@Autowired` on constructors (single-constructor classes don't need it). Follow this exactly for `SessionEventPublisher`.

### Package-by-feature, not layer-by-type
**Source:** ARCHITECTURE.md line 95, existing `voting/`, `session/`, `catalog/` packages
**Apply to:** both new realtime files — they belong in a new `realtime/` package, not `config/` or `controller/` (which is precisely the legacy layer-based structure D-03 removes).

### Fresh-read-then-broadcast (Pattern 3)
**Source:** ARCHITECTURE.md lines 146-178 ("Pattern 3: Event-driven notification, not state-carrying broadcast")
**Apply to:** `VoteService.recordVote()` edit — the value broadcast must be the same `SessionVoteStatus` object already produced by `matchAggregationService.computeStatus(sessionId)` inside the existing transaction; never a separately re-derived or cached value.

### REST-reconcile-on-connect (Pattern 4) — informs what NOT to build this phase
**Source:** ARCHITECTURE.md lines 161-178, 239; PITFALLS.md Pitfall 5
**Apply to:** scope boundary only — this phase's backend has no responsibility to replay missed messages or track per-client delivery state; `VoteController.getStatus()` (`VoteController.kt` lines 58-67) already exists and is the reconciliation source of truth Phase 6's frontend will call on load/reconnect. Do not add any WS-side "replay" or "last known state" caching in this phase.

### Testing pattern for the new realtime layer
**Source:** `src/test/kotlin/org/example/muvimatchr/voting/VoteServiceConcurrencyTest.kt` (extends `PostgresTestSupport`, `@Autowired` real Spring beans, asserts against a real embedded Postgres — no mocking of the DB layer)
**Apply to:** the STOMP verification test D-04 calls for — follow the same "real embedded infrastructure, not mocks" convention: use a real embedded Spring Boot test context (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) with a real `WebSocketStompClient` connecting over the actual `/ws` endpoint and asserting a real message arrives on `/topic/session/{sessionId}` after calling the real `VoteService.recordVote()` — not a mocked `SimpMessagingTemplate`. No existing WS test file exists yet, so this is a new test file (e.g. `src/test/kotlin/org/example/muvimatchr/realtime/SessionEventPublisherIntegrationTest.kt`) with no direct analog beyond this structural convention.

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| STOMP integration test (new, exact path Claude's discretion) | test | event-driven | No WebSocket/STOMP test exists in the codebase today; follow the "Testing pattern" shared-pattern note above (real embedded server, `PostgresTestSupport`-style real-infra convention) rather than a copied test file. |

## Metadata

**Analog search scope:** `src/main/kotlin/org/example/muvimatchr/` (all packages), `src/test/kotlin/org/example/muvimatchr/voting/`
**Files scanned:** ~40 main source files (full listing enumerated), 3 test files in `voting/`
**Pattern extraction date:** 2026-09-06
**Tracked-source verification:** `git ls-files` confirmed all cited analog paths (`config/WebSocketConfig.kt`, `controller/WebSocketController.kt`, `voting/VoteService.kt`) are git-tracked source, not gitignored mirrors.
