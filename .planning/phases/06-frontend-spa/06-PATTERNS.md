# Phase 6: Frontend SPA - Pattern Map

**Mapped:** 2026-09-06
**Files analyzed:** 11 (7 Thymeleaf templates, 1 build.gradle.kts dependency line, 2 legacy prototype controllers, 1 new Gradle static-resource wiring task) — plus the net-new `frontend/` tree, which has no in-repo analog by design.
**Analogs found:** 1 exact precedent (delete-then-rebuild commit pattern) applied across all backend touchpoints; 0 analogs for the net-new frontend tree (expected — Vite scaffold conventions from STACK.md are the source of truth there, not this repo).

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `src/main/resources/templates/vote.html` (delete) | view/template | request-response | N/A — deletion, not build | precedent: commit `c548226` |
| `src/main/resources/templates/results.html` (delete) | view/template | request-response | same | same |
| `src/main/resources/templates/lobby.html` (delete) | view/template | request-response | same | same |
| `src/main/resources/templates/wait.html` (delete) | view/template | request-response | same | same |
| `src/main/resources/templates/next.html` (delete) | view/template | request-response | same | same |
| `src/main/resources/templates/totalResults.html` (delete) | view/template | request-response | same | same |
| `src/main/resources/templates/error.html` (delete) | view/template | request-response | same | same |
| `build.gradle.kts` (remove thymeleaf line) | config | build | same file, prior line `implementation("org.springframework.boot:spring-boot-starter-thymeleaf")` | exact — surgical one-line removal |
| `build.gradle.kts` (add frontend build task) | config | build/file-I/O | no in-repo analog (no existing Gradle task copies external build output into resources) | none — use Gradle `Copy`/`Exec` task idiom from STACK.md/ARCHITECTURE.md |
| `frontend/` (new Vite React+TS SPA tree) | component/service/hook/route (multiple) | request-response, streaming (WS) | no in-repo analog — net new | none — follow STACK.md scaffold conventions |
| `src/main/kotlin/.../controller/LobbyController.kt` (likely also delete) | controller | request-response | flagged as legacy risk, see below | precedent: commit `c548226` deleted `WebSocketController.kt`/`WebSocketConfig.kt` the same way |
| `src/main/kotlin/.../controller/MovieVoteController.kt` (likely also delete) | controller | request-response | same | same |

## Pattern Assignments

### Backend deletion pattern (templates + Thymeleaf dependency): D-02

**Analog / precedent:** git commit `c548226` ("feat(05-01): replace prototype lobby socket with session-scoped STOMP layer"), Phase 5's D-03 execution. That commit is the established in-repo precedent for "delete the prototype wholesale, don't patch it":

```
Deleted the prototype config/WebSocketConfig.kt and controller/WebSocketController.kt;
relocated LobbyEvent into LobbyController.kt to repair the resulting compile break
```

Verified via `git log --diff-filter=D --oneline` — these two files were fully removed in a single commit alongside the new `realtime/WebSocketConfig.kt` and `realtime/SessionEventPublisher.kt` additions, not deprecated-in-place or feature-flagged.

**Apply this same shape to Phase 6's D-02:**
1. Delete all 7 files in one commit/plan step:
   - `src/main/resources/templates/vote.html`
   - `src/main/resources/templates/results.html`
   - `src/main/resources/templates/lobby.html`
   - `src/main/resources/templates/wait.html`
   - `src/main/resources/templates/next.html`
   - `src/main/resources/templates/totalResults.html`
   - `src/main/resources/templates/error.html`
2. Remove the dependency line in `build.gradle.kts` (line 23 as of this mapping):
   ```kotlin
   implementation("org.springframework.boot:spring-boot-starter-thymeleaf")  // Thymeleaf template engine
   ```
3. **Not explicitly named in CONTEXT.md D-02 but structurally required — flag for the planner:** `LobbyController.kt` (`src/main/kotlin/org/example/muvimatchr/controller/LobbyController.kt`) and `MovieVoteController.kt` (`src/main/kotlin/org/example/muvimatchr/controller/MovieVoteController.kt`) are the *only* remaining callers of the view names `"lobby"`, `"vote"`, `"results"`, `"error"` (confirmed via grep — no other file returns these view-name strings). Both are prototype, layer-based-package controllers explicitly called out as superseded in `ARCHITECTURE.md` line 95 ("the prior prototype used layer-based packages... `MovieVoteController` was doing session, vote, and aggregation logic all in one class"). They hold in-memory session state (`HttpSession` attributes, a `mutableMapOf` lobby registry) that is fully superseded by the real `session/` and `voting/` packages built in Phases 2-4. Leaving them in place after template deletion means dead code returning view names Spring can no longer resolve (a runtime 500 the moment `GET /` or `GET /vote` is hit, not a compile error — Kotlin `Controller` view-name strings aren't checked at compile time). Recommend the planner delete both controllers as part of the same D-02 step, following the exact `c548226` precedent (delete wholesale, same commit as the template removal) — same match quality and same rationale as the WebSocketController/WebSocketConfig precedent.

### Gradle static-resource build wiring (D-01, new)

**No direct in-repo analog** — this is genuinely new build infrastructure per `06-CONTEXT.md`'s "Integration Points" section: "Gradle build step wiring `frontend/` (Vite build output) into Spring Boot's static resource serving." `src/main/resources/static/` is currently empty (confirmed via `find`).

**Structural guidance (from Claude's Discretion in CONTEXT.md + standard Spring Boot/Gradle idiom, since no repo analog exists):**
- Add a Gradle task (e.g. `buildFrontend` of type `Exec`, running `npm ci && npm run build` inside `frontend/`) and a `Copy` task that copies `frontend/dist/**` into `build/resources/main/static/` (or `src/main/resources/static/` if committing build output is preferred — CONTEXT.md leaves task wiring to discretion).
- Wire it via `tasks.named("processResources") { dependsOn(buildFrontend, copyFrontendDist) }` so `bootJar`/`build` picks it up automatically — matches the single-deployable-JAR goal in D-01 ("no CORS config, no second hosting target").
- `build.gradle.kts` currently has no `Exec`/`Copy`/`node`-plugin precedent to copy from (confirmed by reading the full file — plugins block only has Kotlin/Spring/dependency-management plugins, no Gradle Node plugin). Planner should treat this as greenfield Gradle work guided by STACK.md/ARCHITECTURE.md, not by an existing analog.

### `frontend/` SPA tree (net-new, D-01/D-03/D-04/D-06-D-11)

**No in-repo analog exists** — this is intentionally a brand-new directory tree per `06-CONTEXT.md` line 9 ("replacing the legacy Thymeleaf prototype entirely"). Source of truth for scaffolding conventions is `.planning/research/STACK.md` (React 19 + Vite + TS, `motion` for drag/gesture — NOT `react-tinder-card`, `@stomp/stompjs` v7.x, `@tanstack/react-query` v5.x, React Router v7.x) and `.planning/research/ARCHITECTURE.md`. The planner should treat STACK.md's code examples as the pattern source for this tree rather than searching the codebase further — there is nothing else in this repo of the same role/data-flow shape (React components, hooks, client-side routes) to analogize from.

**API surface the SPA must integrate with (read-only reference, not files to copy from, but the contract new frontend code binds to):**
- `VoteController.getStatus()` → `GET /api/sessions/{sessionId}/votes/status` (Phase 4/5) — polled on mount/reconnect per Phase 5 D-04's "REST is sole source of truth" pattern.
- `/topic/session/{sessionId}` STOMP topic (Phase 5, `realtime/SessionEventPublisher.kt`) — WS push of the same status shape (`SessionVoteStatus`/`VoteStatusResponse`).
- `TokenService`/`CurrentParticipantArgumentResolver` (Phase 2, `src/main/kotlin/org/example/muvimatchr/auth/`) — bearer-token auth; SPA reads the token from the URL query string (D-05) and attaches it as an `Authorization` header (or however the existing resolver expects it — planner should read `CurrentParticipantArgumentResolver.kt` directly during planning to confirm the exact header/scheme).

## Shared Patterns

### Delete-then-rebuild (applies to all backend touchpoints in this phase)
**Source:** git commit `c548226` (Phase 5 D-03 execution)
**Apply to:** Thymeleaf template deletion, `build.gradle.kts` dependency removal, and (flagged) `LobbyController`/`MovieVoteController` deletion.
**Pattern:** Delete the legacy artifact wholesale in the same step/commit as introducing its replacement — never leave the old code paths in place "just in case" or behind a flag. This project's established convention (also stated directly in `PROJECT.md` Context per `06-CONTEXT.md` D-02) is "rebuild, don't patch."

### REST-first, WS-as-signal (applies to all new frontend data-fetching code)
**Source:** `.planning/phases/05-real-time-notification-layer/05-CONTEXT.md` D-04/Pattern 4, restated in `06-CONTEXT.md` "Established Patterns"
**Apply to:** every SPA screen/hook that reads session/vote status (join/lobby, swipe, wait, results).
**Pattern:** Always REST-fetch status on mount and on every WS reconnect; treat inbound STOMP messages as either (a) a trigger to re-fetch, or (b) directly applicable state — but never treat the WS connection itself as the initial or sole source of truth.

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `frontend/` entire tree (components, hooks, routes, API client, swipe-deck logic) | component/hook/route/service | request-response, streaming | Net-new SPA, first frontend code in this repo — no prior React/Vite/TS code exists to analogize from. Planner must source patterns from `.planning/research/STACK.md` code examples instead. |
| Gradle frontend-build-and-copy task | config | file-I/O | No existing Gradle task in `build.gradle.kts` performs an `Exec`+`Copy` pipeline; only Kotlin/Spring Boot plugin declarations exist today. |

## Metadata

**Analog search scope:** `src/main/kotlin/org/example/muvimatchr/**` (controller, config, realtime, auth, session, voting, catalog packages), `src/main/resources/**`, `build.gradle.kts`, `settings.gradle.kts`, project git history (`git log --diff-filter=D`, `git show`) for the Phase 5 deletion precedent.
**Files scanned:** ~20 backend source files + git history for commit `c548226`; confirmed `frontend/` does not yet exist and `src/main/resources/static/` is empty.
**Pattern extraction date:** 2026-09-06
