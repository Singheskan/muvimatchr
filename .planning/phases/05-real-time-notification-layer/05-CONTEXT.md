# Phase 5: Real-Time Notification Layer - Context

**Gathered:** 2026-09-06
**Status:** Ready for planning

<domain>
## Phase Boundary

Connected participants get live "waiting on N of M" status pushed to them over WebSocket as votes are recorded, and reconnecting participants always reconcile against a REST status fetch (never stale cached WS state). This phase delivers the backend notification layer only: no frontend consumption (Phase 6), no new vote/aggregation logic (Phase 4 already computes `SessionVoteStatus` correctly and race-free — this phase only pushes it). The existing `GET /api/sessions/{sessionId}/votes/status` endpoint (`VoteController.getStatus`) is the reconciliation source of truth this phase's WS layer sits alongside, not replaces.

**Discussion note:** the user directed Claude to proceed on its own first-pass recommendation for each gray area below rather than discuss them turn-by-turn — decisions below are Claude's judgment calls, made consistent with this project's established risk-tolerance and architecture patterns, not user-debated choices.

</domain>

<decisions>
## Implementation Decisions

### WebSocket subscription security
- **D-01:** No STOMP-level participant-token validation on CONNECT/SUBSCRIBE in v1 — subscribing to a session's status topic requires knowing the session's internal UUID (the same identifier already required for every REST call on that session), but not presenting a bearer token. This is a deliberate accepted risk, consistent with the project's existing pattern (Phase 2/3 already ship without join-endpoint rate limiting, logged as "revisit before a public deploy," not blocking). — **Reversibility:** reversible — adding a `ChannelInterceptor` that validates a token on CONNECT/SUBSCRIBE later doesn't change the topic addressing scheme or any persisted data.
- **D-02:** The broadcast topic is keyed by the session's UUID (`/topic/session/{sessionId}`), not the human-typable 6-character join code — matching every existing REST endpoint's path convention (`/api/sessions/{sessionId}/...`) and giving meaningfully more entropy than the join code (already flagged in `02-REVIEW.md` as the weaker of the two identifiers).

### Legacy WebSocket code
- **D-03:** Delete the existing prototype `WebSocketConfig.kt` and `WebSocketController.kt` (`src/main/kotlin/org/example/muvimatchr/controller/`) — a toy `/lobby` topic unrelated to sessions, fully superseded by this phase's real session-scoped `realtime/` package per `ARCHITECTURE.md`. Matches this project's established "rebuild, don't patch" stance (PROJECT.md Context) rather than leaving dead/confusing legacy code alongside the real implementation.

### Manual verification approach
- **D-04:** Verification is automated-only via a Java/Kotlin STOMP test client (`WebSocketStompClient`, already available transitively via `spring-boot-starter-websocket`) exercising a real embedded server — no throwaway manual HTML test page. Matches Phases 1-4's established pattern of proving backend correctness via automated tests before any UI exists; Phase 6 is where a human first interacts with this over a real browser.

### Broadcast trigger scope
- **D-05:** A broadcast fires only as a direct result of `VoteService.recordVote()` — after each vote is persisted, `MatchAggregationService.computeStatus()` is re-read fresh from the DB (per `ARCHITECTURE.md` Pattern 3) and pushed to `/topic/session/{sessionId}`. Idle-participant timeout transitions are not separately/proactively re-broadcast — there is no scheduled job in this app today, and per `04-CONTEXT.md` D-07 idle status is already always recomputed live on every read (REST or next vote), so a participant's screen self-corrects the next time anything triggers a check. Introducing a scheduled re-broadcast job is new infrastructure this app doesn't otherwise need at its stated scale.

### Claude's Discretion
- Exact `realtime/` package shape (`SessionEventPublisher.kt` wrapping `SimpMessagingTemplate` per `ARCHITECTURE.md`'s sketch) — implementation detail.
- Whether the WS payload is the full `SessionVoteStatus`/`VoteStatusResponse` shape or a trimmed subset — `ARCHITECTURE.md` Pattern 3 recommends sending the full fresh read to save a round trip; exact DTO reuse vs. a dedicated WS payload type is Claude's call.
- SockJS fallback: STACK.md explicitly recommends against it (modern-browser target) — plain WebSocket only, no SockJS registration.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements & roadmap
- `.planning/REQUIREMENTS.md` — RTIME-01, RTIME-02, RTIME-03 (this phase's requirement set)
- `.planning/ROADMAP.md` §"Phase 5: Real-Time Notification Layer" — goal, 3 success criteria, dependency on Phase 4 only
- `.planning/PROJECT.md` — Constraints ("Real-time: WebSocket (or equivalent) needed"), Key Decisions table (async voting + live waiting screen row)

### Architecture & research (already answers most of the "how")
- `.planning/research/ARCHITECTURE.md` §"Real-time Notification Layer" component row, §"Recommended Project Structure" (`realtime/` package: `WebSocketConfig.kt`, `SessionEventPublisher.kt`), §"Pattern 3: Fresh-read-then-broadcast" (directly informs D-05), §"Pattern 4: Reconnect-safe client, not reconnect-safe server" (directly informs the REST-reconcile-on-connect requirement), §"Anti-Pattern 2: Treating WebSocket delivery as the record of truth"
- `.planning/research/STACK.md` §"Real-Time Decision" — STOMP over raw WebSocket/SSE, no SockJS (directly informs Claude's Discretion note above); `@stomp/stompjs` v7.x is a **Phase 6 frontend concern**, not this phase's — noted here only because `STATE.md` flagged reviewing its reconnect/resubscribe semantics before implementation, which applies to the frontend client work, not the backend broadcaster this phase builds
- `.planning/research/PITFALLS.md` Pitfall 2 (race → duplicate broadcast; already solved by Phase 4's session-scoped `SELECT ... FOR UPDATE`, this phase just needs to not introduce a second, uncoordinated broadcast path), Pitfall 5 (missed WS messages on reconnect — directly informs the REST-reconcile requirement, RTIME-03), Security Mistakes row ("No origin/handshake validation on the WebSocket endpoint" — directly informs D-01/D-02, accepted as a documented risk not a blocker), Integration Gotchas WS rows (in-memory `SimpleBroker` doesn't scale past one instance — accepted, matches single-instance hosting target; naive stompjs reconnect can duplicate-subscribe — a Phase 6 frontend concern)

### Prior phase context (for consistency)
- `.planning/phases/04-vote-recording-match-aggregation/04-CONTEXT.md` — `MatchAggregationService.computeStatus()` is the exact read this phase broadcasts (D-05); D-07's "always recomputed live, never sticky" rule is why idle timeouts don't need their own broadcast trigger
- `.planning/phases/02-session-lobby-flow/02-REVIEW.md` — join code (~1.07B combinations) already flagged as the weaker identifier; directly informs D-02's choice of session UUID as the topic key

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `VoteController.getStatus()` / `MatchAggregationService.computeStatus()` (`src/main/kotlin/org/example/muvimatchr/voting/`) — already returns the exact `SessionVoteStatus` shape (`deckSize`, `activeCount`, `finishedCount`, `isComplete`, `matchedMovieIds`, `likeCounts`) this phase needs to push over WebSocket; this phase adds a publisher on top, not new aggregation logic.
- `VoteService.recordVote()` (`src/main/kotlin/org/example/muvimatchr/voting/VoteService.kt`) — the single call site where a broadcast must be triggered after the transactional vote write completes (D-05).
- `spring-boot-starter-websocket` — already a `build.gradle.kts` dependency (line 36); no new dependency needed for the backend side of this phase.
- `CurrentParticipantArgumentResolver.kt` / `TokenService.kt` (`src/main/kotlin/org/example/muvimatchr/auth/`) — existing Bearer-token resolution mechanism for REST; **not reused for WS** per D-01 (STOMP CONNECT/SUBSCRIBE isn't an MVC `HandlerMethodArgumentResolver` call site — wiring token validation into the WS handshake would need a distinct `ChannelInterceptor`, deliberately deferred).

### Established Patterns
- Package-by-feature (`session/`, `voting/`, `catalog/`) — this phase adds a new `realtime/` package per `ARCHITECTURE.md`, not a `controller/`/`service/`-style layer package.
- Flyway owns all DDL; this phase needs no schema changes — it is a pure notification layer over already-persisted state (D-05, Anti-Pattern 2).

### Integration Points
- **Legacy code to delete, not extend (D-03):** `src/main/kotlin/org/example/muvimatchr/config/WebSocketConfig.kt` (registers `/ws` SockJS endpoint + `/topic`/`/app` prefixes for the toy lobby demo) and `src/main/kotlin/org/example/muvimatchr/controller/WebSocketController.kt` (`@MessageMapping("/lobby")` → `@SendTo("/topic/lobbyUpdates")`) — both fully superseded by this phase's session-scoped `realtime/` package.

</code_context>

<specifics>
## Specific Ideas

No specific UI/UX references — this is a backend/data phase (the actual waiting-screen UI is Phase 6). The one concrete mechanism already fixed by prior research: the client always does a REST status fetch first (on load and on every reconnect), then subscribes to the topic for live pushes — WS is a "go re-check" convenience, never the sole carrier of truth (`ARCHITECTURE.md` Pattern 4, `PITFALLS.md` Pitfall 5).

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope. (WS-level token authentication was raised and explicitly accepted as a deferred risk rather than a new deferred *feature* — see D-01 — consistent with Phase 2/3's existing accepted-risk log entries in STATE.md, not a new item.)

</deferred>

---

*Phase: 5-Real-Time Notification Layer*
*Context gathered: 2026-09-06*
