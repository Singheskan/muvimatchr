---
gsd_state_version: 1.0
current_phase: 3
current_phase_name: TMDB Integration & Catalog Caching
status: planning
stopped_at: Phase 3 context gathered
last_updated: "2026-09-03T19:21:50.444Z"
last_activity: 2026-09-03
last_activity_desc: Phase 02 complete, transitioned to Phase 3
state_head: 052cbeb3186ad16345b535631578a4c3f7c7c53f
progress:
  total_phases: 6
  completed_phases: 1
  total_plans: 5
  completed_plans: 5
  percent: 17
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-09-03)

**Core value:** Two (or more) people with different tastes can independently pick movies they'd watch and get a fast, confident answer to "what do we actually both want to watch tonight" — without the back-and-forth debate.
**Current focus:** Phase 3 — TMDB Integration & Catalog Caching

## Current Position

Phase: 3 — TMDB Integration & Catalog Caching
Plan: Not started
Status: Ready to plan
Last activity: 2026-09-03 — Phase 02 complete, transitioned to Phase 3

Progress: [███░░░░░░░] 33% (Phase 02 of 6 complete)

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

- Total plans completed: 2
- Average duration: - min
- Total execution time: 0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 02 | 2 | - | - |

**Recent Trend:**

- Last 5 plans: -
- Trend: -

*Updated after each plan completion*
**Per-Plan Metrics:**

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 02 P01 | 35min | 2 tasks | 15 files |
| Phase 02 P02 | 15min | 2 tasks | 4 files |

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

### Pending Todos

None yet.

### Blockers/Concerns

- Environment quirk (this dev machine): `./gradlew` needs `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` exported (Gradle 8.14.3's daemon can't launch on the machine's default JDK 25). Any test touching Testcontainers additionally needs `DOCKER_HOST=unix:///Users/psrg/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` exported, or the Ryuk reaper container fails to start under Colima. Not fixed in committed config (machine-specific paths); export before every `./gradlew` invocation.
- Phase 4 planning needs a concrete concurrency mechanism decision (optimistic locking vs. transactional SQL count) for the "everyone finished" race — flagged by research as the highest-risk logic in the app.
- Phase 5 planning should review current `@stomp/stompjs` v7 reconnect/resubscribe semantics before implementation (avoid duplicate-message-on-reconnect).
- Phase 3 planning should re-verify current TMDB rate-limit and image-CDN connection-limit numbers against official docs (research flagged these as low-confidence, forum-sourced).
- Late-joiner handling: RESOLVED for Phase 2 (see Recent Decisions below — join anytime, no lock). Abandoned-participant handling (someone who joins but never finishes voting) is still undecided and will need a decision during Phase 4 planning (match/aggregation logic must define what "everyone finished" means when a participant never returns).
- [Phase 02 code review, advisory/non-blocking — see 02-REVIEW.md]: `V4__add_participant_token.sql` adds `token_hash NOT NULL` with no `DEFAULT` (fine now, fragile if any environment ever seeds participant rows before this migration runs); join-code lookup is case-sensitive with no normalization (a lowercased valid code 404s); no rate limiting on the join endpoint (the 6-char join code, ~1.07B combinations, is the sole access control for a session); the raw bearer token is embedded in the `resumeUrl` query string (already an accepted risk in the phase's threat model, re-flagged since URL-embedded secrets leak via history/referrer/logs). None block Phase 2; worth revisiting before a public deploy (Phase 6+ hosting).
- [Phase 02 verification]: ROADMAP Phase 2 success criterion 4's vote-attribution clause ("votes they already cast are still attributed to them") is an intentional deferral to Phase 4 — no voting exists yet (out of scope per 02-CONTEXT.md). Phase 2 proves the stable participant identity Phase 4's participant-keyed votes will depend on; this is not a gap.

## Deferred Items

Items acknowledged and deferred at milestone close, most recent first:

| Category | Item | Status | Deferred At | Milestone |
|----------|------|--------|-------------|-----------|
| *(none)* | | | | |

## Session Continuity

Last session: 2026-09-03T19:21:50.344Z
Stopped at: Phase 3 context gathered
Resume file: .planning/phases/03-tmdb-integration-catalog-caching/03-CONTEXT.md
