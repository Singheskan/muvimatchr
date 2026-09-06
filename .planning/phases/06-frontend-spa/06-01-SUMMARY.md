---
phase: 06-frontend-spa
plan: 01
subsystem: frontend
tags: [react, vite, typescript, spring-boot, spa, gradle, tanstack-query, react-router, spa-forwarding]

# Dependency graph
requires:
  - phase: 02-session-lobby-flow
    provides: bearer-token participant identity (join code, token issuance, CurrentParticipantArgumentResolver)
  - phase: 03-tmdb-integration-catalog-caching
    provides: backend-proxied catalog surface (not consumed yet by this plan, but the API contract this SPA will extend)
  - phase: 04-vote-recording-match-aggregation
    provides: live vote-status/match computation shape (SessionVoteStatus) later plans in this phase bind to
  - phase: 05-real-time-notification-layer
    provides: STOMP topic /topic/session/{sessionId} and REST-reconcile pattern later plans in this phase wire the client to
provides:
  - Single deployable Spring Boot JAR containing both the API and a Vite-built React SPA (D-01)
  - Complete removal of the Thymeleaf prototype (7 templates, 5 Kotlin files, thymeleaf starter dependency) (D-02)
  - SpaForwardingConfig serving four literal /s/{code} deep-link routes without shadowing /api/** or /ws
  - SessionBootstrapController (GET /api/sessions/by-code/{joinCode}/me) resolving a token-holder's own session/participant identity, cross-session-safe
  - resumeUrl reshaped to /s/{joinCode}?token={token} (D-03)
  - frontend/ Vite React+TS scaffold with api client, session-token/bootstrap hooks, and a working JoinScreen
affects: [06-02, 06-03, 06-04, 06-05]

# Actuals (#2632)
actuals:
  tokens: 53250
  tasks: 4
  commits: 3

# Tech tracking
tech-stack:
  added: [vite, react 19, typescript, "@tanstack/react-query", react-router, motion, "@stomp/stompjs", vitest, "@testing-library/react", jsdom]
  patterns:
    - "Gradle Exec tasks (npmInstall -> buildFrontend) wired into processResources so bootJar packages the SPA at BOOT-INF/classes/static, gated by -PskipFrontendBuild"
    - "WebMvcConfigurer view-controller forwarding limited to four literal /s/{code}* patterns, never a wildcard, to avoid shadowing /api/** or /ws"
    - "Bearer-token-gated join-code-to-session bootstrap endpoint (no anonymous joinCode->sessionId lookup)"
    - "apiFetch attaches Authorization only for /api/ paths and only when a token is explicitly supplied"

key-files:
  created:
    - src/main/kotlin/org/example/muvimatchr/web/SpaForwardingConfig.kt
    - src/main/kotlin/org/example/muvimatchr/session/SessionBootstrapController.kt
    - src/test/kotlin/org/example/muvimatchr/web/SpaForwardingTest.kt
    - src/test/kotlin/org/example/muvimatchr/session/SessionBootstrapControllerTest.kt
    - frontend/package.json
    - frontend/vite.config.ts
    - frontend/index.html
    - frontend/src/main.tsx
    - frontend/src/App.tsx
    - frontend/src/api/types.ts
    - frontend/src/api/client.ts
    - frontend/src/session/useSessionToken.ts
    - frontend/src/session/useBootstrap.ts
    - frontend/src/routes/JoinScreen.tsx
  modified:
    - build.gradle.kts
    - .gitignore
    - src/main/kotlin/org/example/muvimatchr/session/ParticipantController.kt
    - src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt

key-decisions:
  - "Developer approved D-01 and D-02 as written (approve-both)."
  - "Developer approved all four npm packages (motion, @stomp/stompjs, @tanstack/react-query, react-router) plus the Vite scaffold set on npmjs.com."
  - "Developer verified the browser join flow end-to-end and approved."

patterns-established:
  - "Delete-then-rebuild precedent (commit c548226) applied again: Thymeleaf templates and prototype controllers/services/models deleted wholesale in the same step, not deprecated in place."
  - "SPA deep-link forwarding registers exactly the named route set — no catch-all — so API auth failures stay real 401s, never silently downgraded to HTML 200s."

requirements-completed: [RSLT-01]

coverage:
  - id: D1
    description: "Single deployable JAR serves both the API and the React SPA (frontend/dist packaged into BOOT-INF/classes/static via Gradle npmInstall -> buildFrontend -> processResources wiring)"
    verification:
      - kind: integration
        ref: "unzip -l build/libs/MuviMatchr-0.0.1-SNAPSHOT.jar | grep BOOT-INF/classes/static/index.html"
        status: pass
      - kind: unit
        ref: "./gradlew build (full existing Kotlin suite, no Phase 1-5 regression)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Thymeleaf prototype fully removed: 7 templates, 5 prototype Kotlin files (LobbyController, MovieVoteController, LobbyService, MovieService, model/Lobby.kt), and the thymeleaf starter dependency"
    verification:
      - kind: integration
        ref: "test ! -d src/main/resources/templates; test ! -d controller -a ! -d service -a ! -d model; ! grep spring-boot-starter-thymeleaf build.gradle.kts"
        status: pass
    human_judgment: false
  - id: D3
    description: "SessionBootstrapController resolves a token-holder's own session/participant identity from the human join code, 404s on a token for a different session, 401s with no header, and returns only the caller's own votedMovieIds"
    verification:
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/session/SessionBootstrapControllerTest.kt"
        status: pass
    human_judgment: false
  - id: D4
    description: "SPA forwarding serves the four D-04 routes (/s/{code}, /s/{code}/swipe, /s/{code}/wait, /s/{code}/results) as text/html without shadowing /api/** (unauthenticated /api/sessions/{uuid}/deck still 401s)"
    verification:
      - kind: integration
        ref: "src/test/kotlin/org/example/muvimatchr/web/SpaForwardingTest.kt"
        status: pass
    human_judgment: false
  - id: D5
    description: "End-to-end join flow: opening /s/{joinCode} in a browser serves the SPA (not Thymeleaf), submitting a display name issues a real bearer token, and the resulting authenticated screen reads back the participant's own display name from a real REST call"
    verification:
      - kind: manual_procedural
        ref: "Developer confirmed at http://localhost:8080/s/MH1ZWF: join form served, submission reloaded to /s/MH1ZWF?token=... showing 'Welcome back, {name}', page served from localhost:8080 (not Vite dev server), no Thymeleaf markup"
        status: pass
    human_judgment: true
    rationale: "Cross-layer browser verification (network origin, absence of dev-server artifacts, visual read-back) requires human observation of the running system; this is the plan's tracer feedback gate and was explicitly a checkpoint:human-verify task."
  - id: D6
    description: "Frontend unit tests pass: Authorization header attached only for /api/ paths with an explicit token, ApiError carries status, join code URL-encoded, JoinScreen form/read-back behavior"
    verification:
      - kind: unit
        ref: "frontend/src/api/client.test.ts, frontend/src/routes/JoinScreen.test.tsx (npm --prefix frontend run test)"
        status: pass
    human_judgment: false

# Metrics
duration: ~20min (code tasks; two human-gate tasks awaited developer response across the session)
completed: 2026-09-06
status: complete
---

# Phase 6 Plan 1: Frontend SPA Tracer Summary

**Thymeleaf prototype deleted wholesale and replaced by a Vite React+TS SPA built into the same Spring Boot JAR, with a bearer-token-gated join-code bootstrap endpoint and one join path (browser cold-load -> display name -> authenticated read-back) proven end-to-end.**

## Performance

- **Duration:** ~20 min of code-task execution (Task 3 + Task 4), plus two human checkpoint gates (Task 1 decision gate, Task 2 package-legitimacy gate) and the tracer feedback gate, all resolved across the session
- **Started:** 2026-09-06T18:01:18Z (first task commit)
- **Completed:** 2026-09-06T18:39:24Z (this closeout)
- **Tasks:** 4/4
- **Files modified:** 43 (across the plan's 3 code commits)

## Accomplishments

- Deleted the Thymeleaf prototype in full: 7 templates, `LobbyController`, `MovieVoteController`, `LobbyService`, `MovieService`, `model/Lobby.kt`, and the `spring-boot-starter-thymeleaf` dependency — no deprecation shim, no feature flag.
- Wired a Vite-built React SPA into the Spring Boot build via `npmInstall` -> `buildFrontend` -> `processResources`, producing one deployable JAR containing both the REST/WebSocket API and the SPA (D-01).
- Added `SpaForwardingConfig` registering exactly the four D-04 deep-link routes (`/s/{code}`, `/s/{code}/swipe`, `/s/{code}/wait`, `/s/{code}/results`) as `forward:/index.html`, with a grep gate proving no wildcard or regex pattern was introduced that could shadow `/api/**` or `/ws`.
- Added `SessionBootstrapController` (`GET /api/sessions/by-code/{joinCode}/me`), the token-gated join-code-to-session resolution endpoint that closes the anonymous-lookup gap (T-06-02) and lets a resuming participant reload their session identity from the human join code alone.
- Reshaped `ParticipantController.join()`'s `resumeUrl` to the `/s/{joinCode}?token=` scheme (D-03).
- Built the frontend tracer slice: Vite scaffold, `api/types.ts` (mirroring every backend DTO the phase will need), `api/client.ts` (`apiFetch`/`joinSession`/`fetchBootstrap`, Authorization attached only for `/api/` paths with an explicit token), `useSessionToken`/`useBootstrap` hooks, and `JoinScreen` — proven end-to-end in a real browser against the running JAR.

## Task Commits

Each task was committed atomically:

1. **Task 1: Reversibility gate (D-01/D-02)** - decision-only, approved "approve-both" (no code commit)
2. **Task 2: Package legitimacy gate** - decision-only, approved "approved" (no code commit)
3. **Task 3: Delete the Thymeleaf prototype and stand up the backend half of the SPA contract** - `5c000df` (test, RED), `81665a1` (feat, GREEN)
4. **Task 4: End-to-end tracer — join path through the single JAR** - `95a00cd` (feat)

**Plan metadata:** (this commit) — docs: complete 06-01 plan

_Task 3 was TDD (`tdd="true"`): `5c000df` added failing `SpaForwardingTest`/`SessionBootstrapControllerTest` cases against the pre-teardown tree, `81665a1` performed the teardown and implementation to green._

## Files Created/Modified

- `build.gradle.kts` - Thymeleaf starter removed; `npmInstall`/`buildFrontend` Exec tasks added, wired into `processResources`
- `.gitignore` - `frontend/node_modules/` and `frontend/dist/` added
- `src/main/kotlin/org/example/muvimatchr/web/SpaForwardingConfig.kt` - four literal SPA deep-link forwards
- `src/main/kotlin/org/example/muvimatchr/session/SessionBootstrapController.kt` - token-gated join-code-to-session resolution
- `src/main/kotlin/org/example/muvimatchr/session/ParticipantController.kt` - `resumeUrl` reshaped to `/s/{joinCode}?token=`
- `src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt` - `findMovieIdsVotedBy` native query added
- `src/test/kotlin/org/example/muvimatchr/web/SpaForwardingTest.kt` - four-route 200 + unauthenticated-API 401 proof
- `src/test/kotlin/org/example/muvimatchr/session/SessionBootstrapControllerTest.kt` - happy path, cross-session 404, missing-header 401, own-votes-only
- `frontend/` (new) - Vite React+TS scaffold: `package.json`, `vite.config.ts`, `index.html`, `src/main.tsx`, `src/App.tsx`, `src/api/types.ts`, `src/api/client.ts`, `src/session/useSessionToken.ts`, `src/session/useBootstrap.ts`, `src/routes/JoinScreen.tsx`, plus test files
- Deleted: `src/main/resources/templates/*.html` (7 files), `controller/LobbyController.kt`, `controller/MovieVoteController.kt`, `service/LobbyService.kt`, `service/MovieService.kt`, `model/Lobby.kt`

## Decisions Made

- **Task 1 decision:** Developer approved D-01 and D-02 as written (approve-both) — single deployable JAR, prototype teardown in the same step.
- **Task 2 decision:** Developer approved all four npm packages (`motion`, `@stomp/stompjs`, `@tanstack/react-query`, `react-router`) plus the Vite scaffold set on npmjs.com.
- **Tracer feedback gate:** Developer verified the browser join flow end-to-end and approved — confirmed `http://localhost:8080/s/MH1ZWF` served the join form, submitting a display name reloaded to `/s/MH1ZWF?token=...` showing "Welcome back, {name}" read back from the authenticated API call, page loaded from `localhost:8080` (not a Vite dev server), no Thymeleaf markup.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None during this closeout. `unzip -l build/libs/*.jar | grep ...` initially appeared to fail because the glob matched both the plain and executable jars in one `unzip -l` invocation; re-running against the specific `MuviMatchr-0.0.1-SNAPSHOT.jar` confirmed `BOOT-INF/classes/static/index.html` is present — a verification-script artifact, not a defect in the shipped code.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- The single-JAR build, SPA forwarding, and token-gated bootstrap endpoint are proven end-to-end — plans 06-02 through 06-05 can build directly on this foundation (roster, swipe deck, route guards, results) without revisiting the build integration.
- `frontend/src/api/types.ts` already carries the DTO shapes (`DeckResponse`, `VoteStatusResponse`, etc.) later plans will bind to, so no contract rediscovery is needed.
- RSLT-01 remains marked incomplete in REQUIREMENTS.md: it is declared across all of 06-01 through 06-04's frontmatter and is only satisfied once the full join-swipe-wait-results flow (including the results view, plan 06-05... note: 06-04 is the last plan declaring RSLT-01, 06-05 adds RSLT-02) is complete. `gsd-tools query requirements.ready-ids` confirms RSLT-01 is currently `blocked`, not `ready`.
- No blockers for Wave 2 (06-02, 06-03).

---
*Phase: 06-frontend-spa*
*Completed: 2026-09-06*

## Self-Check: PASSED

- All 16 key-files.created/artifacts entries confirmed present on disk (`FOUND:` for each; see verification transcript).
- All 4 deleted-package directories confirmed absent (`src/main/resources/templates`, `controller/`, `service/`, `model/`).
- `grep -n 'spring-boot-starter-thymeleaf' build.gradle.kts` returns no match.
- `git log --oneline --all --grep="06-01"` returns all 3 task commits: `5c000df`, `81665a1`, `95a00cd`.
- `./gradlew build` — BUILD SUCCESSFUL (full suite green, no Phase 1-5 regression).
- `./gradlew test --tests "*.web.SpaForwardingTest" --tests "*.session.SessionBootstrapControllerTest"` — BUILD SUCCESSFUL.
- `unzip -l build/libs/MuviMatchr-0.0.1-SNAPSHOT.jar` contains `BOOT-INF/classes/static/index.html`.
- `npm --prefix frontend run test` — 2 files, 6 tests, all passed.
- No missing items.
