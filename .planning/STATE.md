---
gsd_state_version: 1.0
current_phase: 06
current_phase_name: Frontend SPA
status: executing
stopped_at: Completed 06-02-PLAN.md
last_updated: "2026-09-06T18:52:42.386Z"
last_activity: 2026-09-06
last_activity_desc: Phase 06 execution started
state_head: 134d6d6c62fc70384ea867bf74e6be0688401bca
progress:
  total_phases: 6
  completed_phases: 5
  total_plans: 21
  completed_plans: 18
  percent: 86
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-09-06)

**Core value:** Two (or more) people with different tastes can independently pick movies they'd watch and get a fast, confident answer to "what do we actually both want to watch tonight" — without the back-and-forth debate.
**Current focus:** Phase 06 — Frontend SPA

## Current Position

Phase: 06 (Frontend SPA) — EXECUTING
Plan: 3 of 5
Status: Ready to execute
Last activity: 2026-09-06 — Phase 06 Plan 2 (session roster read model & endpoint) complete

Progress: [████████░░] 86% (Phase 05 of 6 complete, Plan 2/5 of Phase 06 complete)

## Phase 1 Verification Summary

All four ROADMAP Phase 1 success criteria confirmed via passing automated tests,
independently re-run on `main` post-merge (`./gradlew test`, 6/6 green, 0 failures):

1. Session, Participant and Vote rows written before a restart are readable by a
   fresh, independently-constructed application context — `RestartSurvivalTest`
   (1 test), against real PostgreSQL 18 via Testcontainers, including lazy
   association navigation (Participant → Session FK survives).
2. Vote table enforces `UNIQUE (session_id, participant_id, movie_id)`; native
   upsert (`VoteRepository.upsertVote`, `ON CONFLICT ... DO UPDATE`) updates in
   place — `VoteRepositoryTest` (3 tests). All bindings are named `@Param`s, no
   string interpolation into the query.
3. Session join codes are unique at the database level — `SessionRepositoryTest`
   (2 tests) — a duplicate `join_code` raises `DataIntegrityViolationException`
   from the DB, not an app-level pre-check.
4. Flyway (V1/V2/V3 migrations) applies cleanly to a fresh database and does not
   reapply on a second startup — asserted via unchanged `flyway_schema_history`
   row count across the `RestartSurvivalTest` restart. `ddl-auto=validate`
   throughout; Hibernate never writes DDL.

## Performance Metrics

**Velocity:**

- Total plans completed: 13
- Average duration: - min
- Total execution time: 0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 02 | 2 | - | - |
| 03 | 5 | - | - |
| 04 | 4 | - | - |
| 05 | 2 | - | - |

**Recent Trend:**

- Last 5 plans: -
- Trend: -

*Updated after each plan completion*
**Per-Plan Metrics:**

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 02 P01 | 35min | 2 tasks | 15 files |
| Phase 02 P02 | 15min | 2 tasks | 4 files |
| Phase 04 P01 | 25min | 2 tasks | 12 files |
| Phase 04 P02 | 42min | 2 tasks | 5 files |
| Phase 04 P03 | 30min | 2 tasks | 4 files |
| Phase 04 P04 | 20min | 2 tasks | 2 files |
| Phase 05 P01 | 30min | 2 tasks | 9 files |
| Phase 05 P02 | 25min | 2 tasks | 3 files |
| Phase 06 P01 | 20min | 4 tasks | 43 files |
| Phase 06 P02 | 20min | 2 tasks | 5 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- Roadmap: Horizontal-layer build order chosen (Persistence -> Session/Lobby -> TMDB Catalog -> Vote/Match -> Real-Time -> Frontend SPA) specifically to prove backend correctness before UI, since that's where the prior prototype failed.
- Roadmap: Real-time layer deliberately sequenced last so REST/DB correctness can be verified without WebSocket "magic" masking bugs.
- Roadmap: Phase 2 (Session/Lobby) and Phase 3 (TMDB Catalog) have no dependency on each other and may be built in either order.
- Phase 2 context (02-CONTEXT.md): short typable join code (not a full URL); resume via a personal link carrying the participant's token (not browser-storage-only); no host special role/flag; late joiners allowed at any time, no session lock; duplicate display names allowed within a session.
- Phase 2 planning (02-01-PLAN.md/02-02-PLAN.md): `spring-boot-starter-validation` is NOT transitively available (confirmed absent from `runtimeClasspath`) — Plan 02-02 adds it explicitly. `SessionService.createSession()`'s join-code retry loop deliberately has no `@Transactional` of its own (uses plain `save()` per attempt, not `saveAndFlush()` inside a shared transaction) — PostgreSQL aborts the whole transaction after any failed statement, so a shared-transaction retry loop would break on the second attempt.
- [Phase 02]: Spring Boot 4.1.1 modularized @AutoConfigureMockMvc into spring-boot-webmvc-test and autoconfigures a Jackson 3 (tools.jackson) ObjectMapper bean, not the classic com.fasterxml.jackson type
- [Phase 02]: Phase 2 (02-02): spring-boot-starter-validation added explicitly as its own dependency line — Spring Boot 2.3+ no longer transitively pulls Bean Validation in from spring-boot-starter-web
- [Phase 02]: Phase 2 (02-02): SESH-01/SESH-02/SESH-04 all closed — no custom exception handler needed, Spring Boot's default MethodArgumentNotValidException handling already returns 400
- [Phase 03]: Deck cache keyed by (genre, providerIds, region) only — never session/participant — so identical filter combos across different sessions share one cache row and one TTL window; CTLG-04's "not re-fetched per deck load" reading confirmed at the filter-combo level, not per-session.
- [Phase 03]: `with_watch_monetization_types` deliberately never sent to TMDB (COVERAGE.md OPT-OUT) — live-verified the omitted-parameter default is the broadest match (flatrate+rent+buy+ads), not the narrowest. App has no UI concept of subscription-vs-rental, so one merged "where to watch" list is correct.
- [Phase 03]: Live TMDB UAT (with a real `TMDB_API_TOKEN`) found and fixed two defects the MockWebServer-only test suite could not catch: (1) `TmdbRegionalAvailability`'s Kotlin/Jackson defaulting broke whenever TMDB omitted a monetization-category JSON key entirely (the common real-world shape — nearly all of a movie's ~126 regions omit `ads`, ~40% omit `rent`/`buy`); (2) CR-04's retry predicate never matched Spring WebClient's actual wrapped-exception shape (`WebClientRequestException` wrapping the real `IOException`). Both fixed in `831ba1e`, both live-verified post-fix. Lesson: fixture-based tests that always include every optional JSON key, or that only simulate HTTP-status failures, can hide entire classes of real-world defects — worth a standing reminder for future phases with external-API integration.
- [Phase 04]: Phase 4 Plan 1: Pinned-branch totalResults reports pinnedMovies.size, not the raw upstream TMDB total captured at first fetch -- the pinned deck's true candidate count.
- [Phase 04]: Phase 4 Plan 1: Deck caching is keyed by filter combination, not session -- test helper must clear deckCacheRepository before every pin to avoid cross-call/cross-test fixture leakage.
- [Phase 04]: Phase 4 Plan 2: CreateSessionRequest/SessionFiltersRequest.providerIds changed from List<Int> = emptyList() to List<Int>? = null -- Jackson-Kotlin's synthetic defaults-constructor path unreliably handled a sparse body supplying a later param (genre) while omitting providerIds, breaking the plan's own required {"genre": 28} request shape.
- [Phase 04]: Phase 4 Plan 2: the D-03 genre-inertness invariant test uses a single session/single deck GET, not a two-session comparison -- two sessions sharing an identical filter combination would hit the same deck-cache row and leave enqueued MockWebServer fixtures unconsumed, corrupting later tests' response ordering.
- [Phase 04]: [Phase 04]: Phase 4 Plan 3: unanimousMovieIds short-circuits on empty active-participant collection before querying (empty IN () is rejected by Postgres, and is also the correct empty-roster answer); perMovieLikeCounts is deliberately unfiltered by roster/unanimity so RSLT-03's future ranked list needs no schema change.
- [Phase 04]: [Phase 04]: Phase 4 Plan 3: idle-participant tests backdate vote.voted_at/participant.created_at via jdbcTemplate rather than Thread.sleep or a shrunken test-only timeout, exercising the real 60s production inactivity window deterministically.
- [Phase 04]: [Phase 04]: Phase 4 Plan 4: VoteServiceConcurrencyTest asserts Hikari maximumPoolSize exceeds the racing thread count once, inside the race test itself, ruling out the connection pool as an accidental serialiser before trusting the ten-iteration race's result.
- [Phase 04]: [Phase 04]: Phase 4 Plan 4: RestartSurvivalTest's new service-path durability method reads the post-restart vote row via a direct jdbcTemplate query, not VoteRepository, matching the file's existing flyway_schema_history precedent and keeping the assertion independent of the repository layer under test elsewhere.
- [Phase 05]: Phase 5 Plan 1: TestRestTemplate does not exist on this project's Spring Boot 4.1.1 classpath (confirmed absent even from spring-boot-restclient-test) -- used java.net.http.HttpClient instead for the REST-vs-push parity fetch, zero new dependency.
- [Phase 05]: Phase 5 Plan 1: STOMP test client must use the default SimpleMessageConverter with a ByteArray payload type, not StringMessageConverter -- StringMessageConverter's text/plain mime-type matching silently drops the broker's application/json-tagged object broadcasts while still passing same-type string test frames.
- [Phase 05]: [Phase 05]: Phase 5 Plan 2: StompTestSupport's readiness-marker handshake is topic-wide and was corrupting a co-subscribed client's queue with non-JSON marker frames -- fixed by routing markers to a dedicated shared sink separate from each subscription's real message queue, required once any test holds 2+ concurrently subscribed StompSessions on the same topic.
- [Phase 05]: Both plan-designated end-of-phase human-check items (startup log /ws confirmation + dead lobby.html socket; live RTIME-03 reconnect walkthrough) were performed during /gsd-verify-work against a real running instance rather than deferred to Phase 6. Since TMDB_API_TOKEN is unavailable on this dev machine (same gap as Phase 3), the deck was seeded directly via SQL insert into deck_cache_entry (bypassing the real TMDB call) rather than through a live fetch -- the notification/reconnect behavior under test doesn't depend on where the deck data came from. Both checks passed: 0 frames on a reconnected STOMP socket, REST reconciliation returned the correct completed state.
- [Phase 06]: [Phase 06] Phase 6 Plan 1: Developer approved D-01 (single deployable JAR) and D-02 (Thymeleaf prototype teardown) as written (approve-both).
- [Phase 06]: [Phase 06] Phase 6 Plan 1: Developer approved all four npm packages (motion, @stomp/stompjs, @tanstack/react-query, react-router) plus the Vite scaffold set on npmjs.com.
- [Phase 06]: [Phase 06] Phase 6 Plan 1: Developer verified the browser join flow end-to-end (localhost:8080/s/MH1ZWF -> join -> authenticated read-back) and approved the tracer feedback gate.
- [Phase 06]: Phase 6 Plan 2: MatchAggregationService.computeRoster reuses findActiveParticipantIds (called exactly twice, no restated inactivity SQL) and the same pinned-snapshot deckSize guard computeStatus uses -- enforced by grep gates, not just convention. — The roster's active/finished markers must never be able to disagree with computeStatus's completion arithmetic (04-CONTEXT.md D-05/D-06/D-07).
- [Phase 06]: Phase 6 Plan 2: GET /api/sessions/{sessionId}/votes/roster is a new additive endpoint, not a widened VoteStatusResponse -- keeps the Phase 4/5 REST/WebSocket parity contract untouched (P-02: no session-level completion flag on the roster shape). — Widening VoteStatusResponse would have broken the byte-identical REST/WebSocket parity two existing Phase 5 tests assert.

### Pending Todos

None yet.

### Blockers/Concerns

- Environment quirk (this dev machine): `./gradlew` needs `JAVA_HOME=/Users/psrg/Library/Java/JavaVirtualMachines/corretto-21.0.4/Contents/Home` exported (Gradle 8.14.3's daemon can't launch on the machine's default JDK 25). Any test touching Testcontainers additionally needs `DOCKER_HOST=unix:///Users/psrg/.colima/default/docker.sock` (Colima's non-standard socket path — the non-interactive shell used for orchestration doesn't inherit Colima's shell hook) and `TESTCONTAINERS_RYUK_DISABLED=true` (Ryuk's reaper container fails to start under Colima on this machine). Not fixed in committed config (machine-specific paths); export before every `./gradlew` invocation. Note: spawned executor/reviewer subagents' own shell context has not needed these exports — only the orchestrator's direct `./gradlew` calls between waves have.
- Multi-wave phase execution: `git push origin main` after **every** wave's merge, not just once before dispatch. Claude Code's `isolation="worktree"` forks each new worktree from `origin/HEAD`, not live local HEAD — if origin falls behind (even by one wave's merge commits), the next wave's worktree is created from a stale base and the executor's own branch-check guard halts it (safe, but costs a redispatch). Confirmed twice now (Phase 2 2026-09-03, Phase 3 Wave 2 2026-09-04).
- Phase 4 planning needed a concrete concurrency mechanism decision (optimistic locking vs. transactional SQL count) for the "everyone finished" race — RESOLVED: session-scoped native `SELECT ... FOR UPDATE` row lock, proven via 10-iteration concurrency tests for both vote recording and deck pinning.
- Phase 5 planning should review current `@stomp/stompjs` v7 reconnect/resubscribe semantics before implementation (avoid duplicate-message-on-reconnect). CLARIFIED in 05-CONTEXT.md: this is a Phase 6 (frontend) concern, not Phase 5 — Phase 5 only builds the backend broadcaster (`SessionEventPublisher`/`SimpMessagingTemplate`), no `stompjs` client code.
- Late-joiner handling: RESOLVED for Phase 2 (see Recent Decisions below — join anytime, no lock). Abandoned-participant handling: RESOLVED in Phase 4 — a computed inactivity timeout (`voting.inactivity-timeout-seconds`, default 60s) excludes an idle participant from both the completion count and the unanimity requirement, non-sticky and non-destructive (one new vote re-includes them immediately, and no vote is ever deleted or discounted).
- [Phase 04 code review, closed — see 04-REVIEW.md/04-REVIEW-FIX.md]: code review found a Critical race in `SessionService.pinDeck` (no row lock, unlike `VoteService.recordVote`) plus 3 related warnings; all fixed and re-verified. During human verification of those fixes, the orchestrator itself found and fixed 2 further regressions the fixer's static-only pass couldn't catch by executing the suite: the CR-01 fix had hardcoded the deck response's `stale` flag to `false` (broke the outage-fallback test — restored `stale = result.stale`), and the new `DeckPinConcurrencyTest` compared raw JSON strings across a Postgres `jsonb` round-trip (which does not preserve object-key order/whitespace) — fixed to compare parsed movie lists. Lesson for future phases: a code-fixer's Tier-1-only (static re-read) verification is not a substitute for actually running the test suite — schedule that as a required step whenever a fix pass runs in an environment that can't execute Gradle/Testcontainers itself.
- [Phase 02 code review, advisory/non-blocking — see 02-REVIEW.md]: `V4__add_participant_token.sql` adds `token_hash NOT NULL` with no `DEFAULT` (fine now, fragile if any environment ever seeds participant rows before this migration runs); join-code lookup is case-sensitive with no normalization (a lowercased valid code 404s); no rate limiting on the join endpoint (the 6-char join code, ~1.07B combinations, is the sole access control for a session); the raw bearer token is embedded in the `resumeUrl` query string (already an accepted risk in the phase's threat model, re-flagged since URL-embedded secrets leak via history/referrer/logs). block Phase 2; worth revisiting before a public deploy (Phase 6+ hosting).
- [Phase 02 verification]: ROADMAP Phase 2 success criterion 4's vote-attribution clause ("votes they already cast are still attributed to them") is an intentional deferral to Phase 4 — no voting exists yet (out of scope per 02-CONTEXT.md). Phase 2 proves the stable participant identity Phase 4's participant-keyed votes will depend on; this is not a gap.
- [Phase 03 security, accepted risks — see 03-SECURITY.md]: no rate limiting anywhere in the app yet (same open item as Phase 2's join endpoint — revisit both together before a public deploy); no participant-attributed audit trail for filter changes (deliberate, D-02); TMDB API token has no startup-time presence check (app boots fine with it unset, only fails on first real deck request). block Phase 3; all accepted with rationale in the security log.
- STATE.md's `progress.completed_phases`/`percent` frontmatter drifted stale a THIRD time after this phase's transition (same recurring bug logged 2026-09-03 and again after Phase 3 — `state.record-session`/`phase.complete` is not writing this frontmatter block correctly). Manually corrected 3→4 completed_phases, 50%→67%. `state.json` (the newer state artifact) has been correct all three times; only STATE.md's frontmatter drifts. This is now a confirmed pattern, not a fluke — worth filing as a real defect in the `phase.complete` CLI path rather than continuing to hand-patch it every phase.
- STATE.md's `progress.completed_phases`/`percent` frontmatter drifted stale a FOURTH time, this time triggered by `state.record-session` during Phase 5's `/gsd-discuss-phase` run (not a `phase.complete` call) — reset 4→3 completed_phases, 67%→50%, even though no phase completion occurred, just a context-gathering session. Manually corrected back to 4/67%. Confirms the defect is broader than `phase.complete` — `state.record-session` itself recomputes/overwrites this block incorrectly on any session-recording call.
- [RESOLVED] Phase 5 Plan 1's Task 2 human-check (confirm /ws STOMP endpoint in startup log and that the prototype lobby.html no longer opens a socket) — performed live during /gsd-verify-work 05, passed.
- STATE.md's `progress.completed_phases`/`percent` frontmatter drifted stale a FIFTH time, this time via `state.update-progress`/`state.record-session` during 05-01's own execution close-out (no phase completion occurred, just this plan finishing) — reset 4→3 completed_phases, 67%→50%. Manually corrected back to 4/67%. Now confirmed across `phase.complete`, `state.record-session` (twice), and `state.update-progress` — the defect is in shared progress-recompute logic invoked by multiple state-mutation verbs, not any single command.
- STATE.md's progress.completed_phases/percent frontmatter drifted stale a SIXTH time, this time via state.advance-plan/state.update-progress during 05-02's own execution close-out (05-02 is the last plan of Phase 05, not yet transitioned) -- reset 4->3 completed_phases, 67%->50%. Manually corrected back to 4/67% (Phase 05 itself is not yet marked complete, only its plans). Now confirmed across phase.complete, state.record-session (x2), state.update-progress, and state.advance-plan -- five distinct state-mutation verbs share the same broken progress-recompute path.
- STATE.md's progress.completed_phases/percent frontmatter drifted stale a SEVENTH time, this time via phase.complete itself when actually transitioning Phase 05 -> Phase 06 (the one call that's supposed to get this right) -- left completed_phases at 4/67% after Phase 05 genuinely completed (state.json correctly showed phase 5 status: complete throughout). Manually corrected to 5/83%. This is now confirmed on phase.complete's own completion path, not just the incidental session-recording verbs -- the shared progress-recompute logic is broken everywhere it's called from, including the one call site whose entire job is to get this number right.
- STATE.md's progress.completed_phases/percent frontmatter drifted stale an EIGHTH time, this time via state.record-session during Phase 6's /gsd-discuss-phase run (same trigger class as the FOURTH occurrence) -- reset 5->4 completed_phases, 83%->67%, even though no phase completion occurred (Phase 05 was already complete; this was just a context-gathering session). Manually corrected back to 5/83%. Now confirmed on six distinct call sites (phase.complete, state.record-session x3, state.update-progress, state.advance-plan) -- still unfixed, still purely cosmetic (state.json remains correct throughout), but worth actually filing as a defect rather than continuing to hand-patch every phase.
- STATE.md's progress.completed_phases/percent frontmatter drifted stale a NINTH time, this time via state.advance-plan/state.update-progress during 06-01's own execution close-out (Phase 05 was already complete; only Phase 06 Plan 1 finished) -- reset 5->4 completed_phases, 83%->67% (and update-progress additionally recomputed percent as plans-only 67% rather than phases-plus-plans 81%). Manually corrected to 5 completed_phases / 81%. Same shared progress-recompute defect, seventh and eighth distinct trigger observations on top of the six call sites already logged; still purely cosmetic (state.json remains correct throughout).
- STATE.md's progress.completed_phases/percent frontmatter drifted stale a TENTH time, this time via state.advance-plan/state.add-decision (x2)/state.record-session during 06-02's own execution close-out (Phase 05 was already complete; only Phase 06 Plan 2 finished) -- reset 5->4 completed_phases, 81%->67% each time. Manually corrected back to 5 completed_phases / 86% (18/21 completed_plans, matching the completed_plans/total_plans formula the prior 81% value itself was computed from: 17/21). Ninth and tenth distinct trigger observations on top of the same shared progress-recompute path; still purely cosmetic (state.json remains correct throughout). This defect has now reproduced on every single state-mutating plan-close-out across three consecutive phases (05-01, 05-02, 06-01, 06-02) -- worth escalating from a per-phase hand-patch note to an actual filed defect against gsd-tools' shared progress-recompute logic.

## Deferred Items

Items acknowledged and deferred at milestone close, most recent first:

| Category | Item | Status | Deferred At | Milestone |
|----------|------|--------|-------------|-----------|
| *(none)* | | | | |

## Session Continuity

Last session: 2026-09-06T18:52:42.053Z
Stopped at: Completed 06-02-PLAN.md
Resume file: None
