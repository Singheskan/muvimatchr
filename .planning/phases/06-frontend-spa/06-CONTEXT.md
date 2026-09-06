# Phase 6: Frontend SPA - Context

**Gathered:** 2026-09-06
**Status:** Ready for planning

<domain>
## Phase Boundary

Users experience the full MuviMatchr flow — create/join, swipe, wait, results — through a real React SPA, replacing the legacy Thymeleaf prototype entirely. Every backend behavior this phase displays (session/lobby, TMDB deck, vote/match aggregation, real-time push) has already been proven correct in Phases 2-5; this phase is pure frontend build-out against an already-correct API surface, not new backend logic. Scope is bounded to RSLT-01 and RSLT-02 plus the SPA shell needed to reach them (join, swipe, waiting screen wiring the Phase 5 WebSocket layer, results).

</domain>

<decisions>
## Implementation Decisions

### Build & deploy integration
- **D-01:** Single deployable JAR. A `frontend/` directory at the repo root holds the Vite-scaffolded React+TS app; a Gradle task runs `npm run build` and copies the Vite `dist/` output into Spring Boot's static resources so the same JAR serves both the REST/WebSocket API and the SPA from one origin. No CORS config, no second hosting target — matches PROJECT.md's single small-instance deployment constraint. — **Reversibility:** costly — splitting into two deployed services later means introducing CORS/WS-origin config and a second deploy pipeline, not just a file move.
- **D-02:** Delete the 7 legacy Thymeleaf templates (`vote.html`, `results.html`, `lobby.html`, `wait.html`, `next.html`, `totalResults.html`, `error.html`) and the `spring-boot-starter-thymeleaf` dependency from `build.gradle.kts` now, at the start of this phase — not deferred to the end. Matches the project's established "rebuild, don't patch" pattern (PROJECT.md Context; Phase 5 D-03 deleted the legacy `WebSocketConfig`/`WebSocketController` the same way) and directly satisfies Success Criterion 1 ("no Thymeleaf server-rendered page anywhere in the flow").

### Session URL scheme & routing
- **D-03:** Shareable/bookmarkable URLs use the existing human join code, not the internal session UUID: `/s/{joinCode}`. Matches what Phase 2 already trained participants to share/type.
- **D-04:** Real client-side routing via React Router, one route per screen: `/s/:code` (join/lobby), `/s/:code/swipe`, `/s/:code/wait`, `/s/:code/results`. Every route fetches live status from the server on mount and redirects if the user is "ahead" of their actual state (e.g. hitting `/results` before voting is complete bounces to `/wait`) — the backend remains the sole authority on what a user can actually see; routing is presentation only, not an access boundary. This directly satisfies Success Criterion 2 (opening a session link cold lands on the correct screen, including straight to results if everyone's already finished).

### Resume-token handling
- **D-05:** The participant's bearer token (issued at join, per Phase 2) stays in the URL query string on the resume/share link permanently — it is read on every load rather than being moved to localStorage and scrubbed from the address bar. This is a deliberate simplicity choice, consistent with the already-accepted Phase 2 risk logged in `02-REVIEW.md` (raw token embedded in `resumeUrl`); this phase does not add new mitigation for that exposure surface, it just keeps reading the token the same way the link already carries it. — **Reversibility:** reversible — moving to localStorage-and-scrub later is additive and doesn't change the token issuance/validation contract.

### Swipe deck interaction & visual feedback
- **D-06:** Card stack shows 2-3 cards with depth (top card fully interactive/draggable, 1-2 cards behind it slightly scaled/offset) — not a single-card-at-a-time render.
- **D-07:** During a drag, the card tilts in the drag direction and a green (like) / red (pass) color tint grows in intensity as the drag crosses the commit threshold, satisfying Success Criterion 4's "visually reflects each pass/like before advancing."
- **D-08:** Finishing a participant's entire deck (swiping every card) auto-transitions straight to the waiting/results flow — treated identically to any other "finished voting" completion, per the pitfalls research warning against a dead-end here.
- **D-09:** Like/pass buttons are shown alongside the draggable card, but only on non-mobile (desktop/tablet-breakpoint-and-up) viewports — hidden on mobile, where drag is the sole interaction. Rationale (from discussion): drag is the natural mobile gesture and buttons would clutter that view; on desktop/PC, mouse-drag is less natural so buttons are the primary interaction there.

### Waiting & results screen content
- **D-10:** The waiting screen shows a named list of participants with per-person done/waiting status (e.g. "Alex ✓, Jordan ✓, Sam …waiting"), using display names already collected at join — not a bare progress count. Addresses the pitfalls research's "no sense of progress" warning more directly than a spinner.
- **D-11:** If the results computation yields zero mutual matches, the results screen shows a simple "no match" message in place of the match card — no runner-up ranking, retry flow, or vote-count fallback (that richer fallback UX is RSLT-05, explicitly v2 in REQUIREMENTS.md). This decision only ensures the v1 screen never crashes or dead-ends on the zero-match case.

### Claude's Discretion
- Exact Gradle task wiring for the frontend build step (task name, whether it hooks into `build`/`bootJar` automatically or is a separate manual step during this phase).
- Component/state-management architecture within the SPA (e.g. React Context vs. prop drilling for participant/session identity; TanStack Query for all server-state per STACK.md).
- Exact desktop breakpoint at which like/pass buttons appear (D-09).
- Results screen layout/visual design beyond the required content (poster, title, where-to-watch) — this phase's UI hint is "yes," so deeper visual design may be handled via a UI design contract pass.
- Copy/wording for the "no match" message (D-11).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements & roadmap
- `.planning/REQUIREMENTS.md` — RSLT-01, RSLT-02 (this phase's requirement set); RSLT-05 explicitly deferred to v2, informs D-11's scope limit
- `.planning/ROADMAP.md` §"Phase 6: Frontend SPA" — goal, 4 success criteria, dependency on Phases 2-5, `UI hint: yes`
- `.planning/PROJECT.md` — Constraints ("Tech stack: ...paired with a proper SPA frontend", "Hosting: small public deployment... single service"), Key Decisions table (Kotlin/Spring Boot + SPA row, currently "Pending — frontend is Phase 6")

### Stack & architecture research (already answers most of the "how")
- `.planning/research/STACK.md` §"Frontend Framework Decision" (React 19 + Vite + TypeScript, locked), §"Frontend Frameworks/Libraries" table (`motion` for swipe/drag, `@stomp/stompjs` v7.x, `@tanstack/react-query` v5.x, React Router v7.x), §"Anti-Patterns to Avoid" (`react-tinder-card` explicitly rejected — build swipe deck on `motion`'s drag/gesture API instead; Create React App rejected in favor of Vite)
- `.planning/research/ARCHITECTURE.md` §"Recommended Project Structure" and the build-order note ("Frontend SPA, consuming the REST + WebSocket API surface established above")
- `.planning/research/PITFALLS.md` Pitfall 5 (missed WebSocket messages on reconnect — REST-reconcile-on-connect pattern the waiting screen must implement, already built server-side in Phase 5), Pitfall 6 (TMDB key must never reach the frontend — all catalog calls go through the existing backend-proxied endpoints), UX Pitfalls table rows on waiting-screen reconnect indicators and "deck ran out" handling (directly informs D-08 and D-10), "Looks Done But Isn't" checklist row on real-time waiting screen verification (kill WiFi mid-wait, reconnect, confirm correct state)

### Prior phase context (for consistency)
- `.planning/phases/05-real-time-notification-layer/05-CONTEXT.md` — the WebSocket topic is `/topic/session/{sessionId}` (UUID-keyed, not join-code-keyed); client must always REST-fetch status first (on load and on every reconnect) before/alongside subscribing — WS is a "go re-check" signal, never the sole carrier of truth; `@stomp/stompjs` v7 reconnect/resubscribe semantics must avoid duplicate-subscription (STATE.md-flagged review item, now this phase's concern)
- `.planning/phases/02-session-lobby-flow/02-CONTEXT.md` and `02-REVIEW.md` — join code is the shareable identifier (informs D-03); resume link carries the raw bearer token as a query param, already an accepted risk (informs D-05); no rate limiting on the join endpoint (pre-existing accepted risk, unrelated to this phase's scope but worth knowing before public deploy)
- `.planning/phases/04-vote-recording-match-aggregation/04-CONTEXT.md` — `SessionVoteStatus`/`MatchAggregationService.computeStatus()` is the live-computed shape the results/waiting screens consume (`deckSize`, `activeCount`, `finishedCount`, `isComplete`, `matchedMovieIds`, `likeCounts`); "match" requires unanimity among currently-active participants, so a genuine zero-match outcome is possible and must be handled (D-11)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `VoteController.getStatus()` / `GET /api/sessions/{sessionId}/votes/status` — the REST reconciliation endpoint the waiting/results screens poll on load and reconnect (per Phase 5 Pattern 4).
- `/topic/session/{sessionId}` STOMP topic (Phase 5) — live push of the same status shape; `@stomp/stompjs` client subscribes here after the initial REST fetch.
- `TokenService` / `CurrentParticipantArgumentResolver` (Phase 2) — existing bearer-token auth for all participant-scoped REST calls; the SPA's API client attaches this token (read from the URL per D-05) on every request.
- Existing session/join/vote/status REST endpoints from Phases 2-4 — this phase wires a frontend to them, does not add new backend endpoints (aside from anything unforeseen surfaced during planning).

### Established Patterns
- Package-by-feature on the backend (`session/`, `voting/`, `catalog/`, `realtime/`) — unaffected by this phase; frontend code lives entirely under the new `frontend/` directory (D-01), no backend package changes expected beyond the static-resource build wiring and Thymeleaf removal (D-02).
- "REST is the sole source of truth on reconnect, WS is a convenience signal" (Phase 5 D-04/Pattern 4) — the frontend must implement the client half of this pattern: always fetch on mount/reconnect, treat WS messages as a trigger to re-fetch or apply directly, never trust WS alone as the initial state source.

### Integration Points
- **Legacy code to delete, not extend (D-02):** `src/main/resources/templates/*.html` (7 files) and the `spring-boot-starter-thymeleaf` line in `build.gradle.kts` — fully superseded by the new SPA.
- **New integration surface:** Gradle build step wiring `frontend/` (Vite build output) into Spring Boot's static resource serving (D-01) — this is new build infrastructure this phase introduces, not present in any prior phase.

</code_context>

<specifics>
## Specific Ideas

- Card stack should look and feel like a classic Tinder-style swipe deck: 2-3 cards deep, top card draggable with rotation + color-tint feedback (D-06/D-07).
- Waiting screen should read like a named roster ("Alex ✓, Jordan ✓, Sam …waiting"), not an anonymous counter (D-10).
- Desktop and mobile should feel native to their input method: drag-only on mobile, drag-or-click-buttons on desktop (D-09) — explicitly raised by the user as "we need two options for phones and for PCs."

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope. (Richer "no mutual match" UX — ranked runner-up list, retry/re-vote flow — was raised implicitly via D-11's scope discussion and confirmed as the already-documented v2 item RSLT-05 in REQUIREMENTS.md, not a new deferred idea.)

</deferred>

---

*Phase: 6-Frontend SPA*
*Context gathered: 2026-09-06*
