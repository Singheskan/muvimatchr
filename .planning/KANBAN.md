# MuviMatchr — Kanban / Activity Log

Running log of work done and issues encountered, maintained every session. For a
machine-readable point-in-time resume snapshot of a paused phase, see
`.planning/HANDOFF.json` / `<phase-dir>/.continue-here.md` instead — this file is
the human-readable history across the whole project.

---

## Board

### In Progress
- Phase 6 (Frontend SPA) — executing. 06-01 (SPA scaffold/teardown tracer) and 06-02
  (session roster read model + endpoint) complete; 06-03/06-04/06-05 remain.

### Done
- **Plan 06-02 (session roster read model + membership-gated REST endpoint) — complete
  (2026-09-06).** See dated entry below for full detail. Full plan SUMMARY at
  `.planning/phases/06-frontend-spa/06-02-SUMMARY.md`.
- Phase 5 (Real-Time Notification Layer) — executed, code-reviewed, verified, transitioned/closed (2026-09-06).
- **Plan 05-02 (N-of-M progression, multi-client fan-out, reconnect reconciliation) —
  complete (2026-09-06). Phase 5's plans are now all executed.** See dated entry below
  for full detail. Full plan SUMMARY at
  `.planning/phases/05-real-time-notification-layer/05-02-SUMMARY.md`.
- **Plan 05-01 (session-scoped STOMP layer + first end-to-end broadcast proof) —
  complete (2026-09-06).** See dated entry below for full detail. Full plan SUMMARY at
  `.planning/phases/05-real-time-notification-layer/05-01-SUMMARY.md`.
- Phase 4 (Vote Recording & Match Aggregation) — executed, code-reviewed, verified, closed (2026-09-06; see Issues Log).
- Project setup: PROJECT.md, REQUIREMENTS.md (22 v1 requirements), ROADMAP.md (6 phases).
- Phase 1 research, pattern mapping, validation strategy, planning (3 plans), plan
  verification (0 blockers/warnings), requirements + decision coverage gates.
- Discarded old in-memory prototype's uncommitted edits (superseded by Phase 1).
- Reconciled diverged remote history (see Issues below) and pushed to `origin/main`.
- **Plan 01-01 (Persistence Foundation toolchain + local DB) — complete.** Reconciled
  the leftover interrupted-session worktree (stale base, missing files already on
  main — re-applied the same verified diff directly onto `main` instead of merging;
  see Issues below). Boot 4.1.1 / Kotlin 2.3.20 / Gradle 8.14.3 toolchain live,
  JPA/Flyway/Postgres/Testcontainers dependencies resolved, `./gradlew build` green
  (`a3845f2`). Local PostgreSQL 18 via `docker-compose.yml`, healthy, wired into
  `application.properties` with `ddl-auto=validate` (`de4a3a2`). Placeholder test
  deleted. Full plan SUMMARY at
  `.planning/phases/01-persistence-foundation/01-01-SUMMARY.md`.
- **Plan 01-02 (tracer slice — Session entity + restart-survival proof) — complete.**
  Executed via an isolated `gsd-executor` worktree agent; merged cleanly (fast-forward)
  onto `main`. Built `V1__create_session.sql`, `Session.kt` (JPA entity), and
  `SessionRepository.kt`. `RestartSurvivalTest` proves a `Session` written by one
  application context is readable by a second, independently-started context against
  the same real PostgreSQL 18 (via Testcontainers) — the core D-03 architecture proof
  this whole phase exists to establish — and that Flyway does not reapply migrations on
  the second startup. `SessionRepositoryTest` proves the join-code unique constraint is
  enforced at the database level. Caught and fixed a real bug mid-execution: the test
  was initially, silently hitting the persistent local dev database instead of the
  ephemeral Testcontainers instance (see Issues below). `./gradlew test` green (3/3).
  Full plan SUMMARY at `.planning/phases/01-persistence-foundation/01-02-SUMMARY.md`.
- **Plan 01-03 (Participant + Vote tables, race-safe upsert) — complete. Phase 1 done.**
  Executed via an isolated `gsd-executor` worktree agent; merged cleanly (fast-forward)
  onto `main`. Built `V2__create_participant.sql`/`Participant.kt`/`ParticipantRepository.kt`
  and `V3__create_vote.sql`/`Vote.kt`/`VoteRepository.kt` (native `INSERT ... ON CONFLICT
  DO UPDATE` upsert on `(session_id, participant_id, movie_id)`, all bindings named
  `@Param`s, no string interpolation). Extended `RestartSurvivalTest` to write and read
  back Session + Participant + Vote together across a real context restart, including
  lazy-association navigation (proves the FK survived, not just scalar columns). Caught
  and fixed a second real bug mid-execution: `PostgresTestSupport`'s per-test-class
  container lifecycle raced with Spring's cached DataSource across two test classes (see
  Issues below). `./gradlew test` green (6/6), independently re-verified by the
  orchestrator on `main` post-merge. All four ROADMAP Phase 1 success criteria confirmed;
  ROADMAP.md and STATE.md marked Phase 1 complete. Full plan SUMMARY at
  `.planning/phases/01-persistence-foundation/01-03-SUMMARY.md`.
- **Plan 02-01 (tracer — session create/join/resume, this codebase's first
  auth + REST layer) — complete.** Executed sequentially on `main` (a prior
  worktree dispatch for this plan hit a stale-base mismatch, so this run was
  degraded to sequential per #2649/#683). Built `V4__add_participant_token.sql`,
  `TokenService` (SecureRandom 256-bit token, SHA-256 hash-at-rest),
  `SessionService.createSession()` (DB-level join-code collision retry, no
  shared `@Transactional` — see decision below), `ParticipantService.join()`,
  `SessionController`/`ParticipantController` (`POST /api/sessions`,
  `POST /{joinCode}/participants`, `GET /{sessionId}/participants/me`), and
  `CurrentParticipantArgumentResolver`/`WebMvcConfig` (custom
  `HandlerMethodArgumentResolver` standing in for Spring Security — this
  codebase's first auth mechanism). `ParticipantControllerTest` (8 tests, real
  MockMvc against real Postgres, no mocks) proves the whole path: unique join
  codes, server-issued token distinct from name/id, hash-at-rest (64-char
  SHA-256), 404 on unknown join code, bearer-token resume with no duplicate
  row, 401 on missing/malformed/unrecognized token, 404 on cross-session
  token. Task 2 done via TDD (RED `5a9f93e` → GREEN `edfa75f`). Hit and fixed
  three Spring Boot 4.1.1 framework-version surprises mid-execution (see
  Issues below). `./gradlew test`/`build` green throughout, including the
  pre-existing Phase 1 suite. SESH-01, SESH-02, SESH-03, SESH-05 marked
  complete in REQUIREMENTS.md (SESH-04, 3+ participants, is 02-02's job).
  Full plan SUMMARY at
  `.planning/phases/02-session-lobby-flow/02-01-SUMMARY.md`.
- **Plan 02-02 (Bean Validation + multi-participant/join-code-distinctness
  hardening) — complete. Phase 2 done.** Executed sequentially on `main`
  (same stale-base worktree condition as 02-01, pre-emptively skipped straight
  to sequential dispatch rather than re-attempting a doomed worktree). Added
  `spring-boot-starter-validation` explicitly (confirmed absent transitively
  since Spring Boot 2.3), `@field:NotBlank`/`@field:Size(max=100)` +
  `@Valid` on the join request. Extended `ParticipantControllerTest` to 12
  tests (validation cases + a 3-distinct-participant proof with a
  `JdbcTemplate` row-count assertion) and added `SessionServiceTest` (2
  tests) proving join-code distinctness/shape. `./gradlew test` green 20/20
  across Phase 1 + Phase 2, no regressions. All five SESH-01..05 requirements
  now complete. Full plan SUMMARY at
  `.planning/phases/02-session-lobby-flow/02-02-SUMMARY.md`.
- **Phase 2 code review** (`02-REVIEW.md`, standard depth, focused on the
  codebase's first auth/crypto code) — 0 Critical, 4 Warning, 7 Info. Warnings
  are advisory/non-blocking: `V4` migration's `NOT NULL` column has no
  `DEFAULT`; join-code lookup is case-sensitive (no normalization); no rate
  limiting on the join endpoint; raw bearer token in `resumeUrl` query string
  (already an accepted risk in the phase's threat model). SecureRandom token
  issuance, SHA-256 hash-at-rest, hash-then-compare resolution, and the
  no-`@Transactional` retry pattern were all confirmed correctly implemented.
- **Phase 2 goal verification** (`02-VERIFICATION.md`) — PASSED, 9/9
  must-haves. Independently re-ran the full test suite (not trusted from
  SUMMARY.md) via JUnit XML reports: 20/20 green. All 5 requirements traced to
  passing tests. One documented, intentional deferral (not a gap): the
  "votes still attributed on resume" clause of success criterion 4 can't be
  verified until voting exists (Phase 4) — Phase 2 only had to prove stable
  participant identity, which it does.
- **Phase 2 marked complete** in ROADMAP.md/STATE.md; PROJECT.md evolved
  (3 requirements moved Active → Validated, 2 new implementation decisions
  logged, Key Decisions outcomes filled in for entries this phase confirmed).

- **Phase 3 context gathered** (`03-CONTEXT.md`, `/gsd-discuss-phase 3`). Four areas
  discussed: region source (host sets an optional `Session.region`, default Germany/DE,
  editable later by any participant — no host/creator role introduced, consistent with
  Phase 2 D-03), deck ranking (TMDB popularity/rating sort only, no personalization —
  explicitly re-confirmed PROJECT.md's ML-out-of-scope call rather than reopening it),
  TMDB outage behavior (retry with backoff, then serve stale cache, error only if nothing
  cached), and deck size/cache scope (~20-movie deck per filter combo, "not enough movies"
  response under 5 matches, cache keyed by filter combo shared across sessions, ~6h TTL).

- **Phase 3 (TMDB Integration & Catalog Caching) complete** — executed end-to-end
  (5 waves), code-reviewed (4 Critical + 4 Warning, all fixed and re-verified),
  goal-verified (5/5 automated criteria), and live-TMDB UAT closed out
  (4/4 human-check items passed, 2 manual-only resolved live). See prior Issues
  Log entries (2026-09-04) for the full execution/review/UAT narrative.
- **Phase 4 context gathered** (`04-CONTEXT.md`, `/gsd-discuss-phase 4`). Three areas
  resolved: deck stability (the specific movie list is pinned per session on first
  fetch, with region/provider/genre all locked together at that same moment — genre
  must move from a per-request query param to a session-level field to support this;
  fully resolves Phase 3's deferred "filters change after votes exist" question, since
  there's no window left in which that can happen), abandoned participants (1-minute
  inactivity timeout since last vote/join — short because the actual deck has no
  trailers/synopsis to linger over — drops the idle participant from both the
  "everyone finished" count *and* the unanimity/match requirement while idle, live and
  reversible on their next vote, not a permanent kick), and vote editing (no
  revise/undo in v1 at all, consistent with the existing `VOTE-06` v2 deferral — the
  upsert mechanism stays purely as an idempotency safeguard, not a user-facing edit
  path).

### Next
- Plan Phase 5 (Real-Time Notification Layer) — `/gsd-plan-phase 5`.

---

## Issues Log

### 2026-09-01 — Docker/container runtime absent on dev machine
No Docker-API-compatible daemon was installed (`docker`, `colima`, `podman`,
`orbstack`, `nerdctl` all absent), blocking every Testcontainers-based integration
test this phase needs — including the restart-survival proof that is the phase's
core deliverable. **Resolution:** surfaced as a `checkpoint:decision` in Plan
01-01 Task 1; user chose Colima (free for commercial use, fully CLI-installable
via Homebrew, no GUI/license click-through). Installed and confirmed running.

### 2026-09-01 — Execution interrupted mid-task (budget constraint)
The Plan 01-01 executor was running in an isolated git worktree
(`isolation="worktree"`) and got partway through Task 2 (colima+docker install
done, build-config edits made, placeholder test deleted) before the session had
to pause for a budget constraint. Nothing was committed and `<verify>` was never
confirmed green. **Resolution:** documented precisely (not discarded, not
silently treated as done) in `.planning/HANDOFF.json` and
`.planning/phases/01-persistence-foundation/.continue-here.md` for the next
session to reconcile.

### 2026-09-01 — Local `main` had diverged from `origin/main`
`origin/main` carried a commit (`c395dd6`, "Update README.md" — a dual
MIT/commercial licensing README) that was never in local history; local `main`
had 16 commits origin didn't have (all prior app work + this session's GSD
planning). A plain `git push` would have been rejected as non-fast-forward, and
force-pushing would have silently discarded the licensing README. **Resolution:**
merged `origin/main` into local `main` (clean, no conflicts — local never touched
`README.md`), then pushed normally. Licensing content preserved.

### 2026-09-01 — Leftover agent-worktree scaffolding not gitignored
`.claude/worktrees/` (holds isolated git worktrees created by Agent-tool
dispatches, each with its own nested `.git`) and `.planning/milestone.lock`
(session lock) showed up as untracked and were not covered by `.gitignore`.
**Resolution:** added both to `.gitignore` — they're local session state, not
project content, and the nested `.git` in a worktree should never be committed
into the parent repo.

---

### 2026-09-02 — Resumed session; reconciled stale worktree from interrupted prior session
Prior session paused mid-Task-2 of Plan 01-01 with uncommitted toolchain edits sitting
in an isolated Agent-tool worktree (`isolation="worktree"`) that was never merged back.
On inspection, that worktree's branch had been created from a stale base — origin's
early "Update README.md" commit — which predates several files already on `main`
(`WebSocketConfig.kt`, `WebSocketController.kt`, `error.html`, `totalResults.html`,
`wait.html`). Merging it as-is risked silently dropping those files or producing
spurious conflicts. **Resolution:** verified the worktree's diff (`build.gradle.kts`,
`gradle-wrapper.properties`, placeholder-test deletion) matched Plan 01-01 Task 2's
spec exactly, then re-applied the identical edits directly onto `main`'s actual current
tip (which was still at the plan's expected pre-edit baseline) and verified there
instead of merging. Removed the stale worktree and branch. Also discovered Gradle
8.14.3's daemon cannot launch on this machine's default JDK (Corretto 25, resolved via
`java_home`) — every `./gradlew` call in this phase needs `JAVA_HOME` pinned to the
installed temurin-21 JDK; not fixed via a committed `gradle.properties` since that
would hardcode a machine-specific path outside Task 2's declared file scope. Completed
Task 2 and Task 3, committed both, wrote `01-01-SUMMARY.md`, cleared the stale
`.planning/milestone.lock` (dead PID, confirmed via `ps`) and consumed `HANDOFF.json`.

### 2026-09-02 — Plan 01-02 worktree branched before Plan 01-01's implementation commits landed
The `gsd-executor` agent spawned for Plan 01-02 branched its isolated worktree at the
moment of dispatch, which was *before* this same session's earlier direct-on-main
commits for Plan 01-01 (`a3845f2`, `de4a3a2`, the docs closeout `1c77b80`) existed —
so the worktree only had Plan 01-01's planning docs, not the actual toolchain
upgrade/compose file it depends on. The plan's own `<worktree_branch_check>` guard
fired the expected warning. **Resolution:** the agent confirmed its branch had zero
unique commits of its own at that point (`git log main..HEAD` was empty), so it ran a
risk-free `git merge --ff-only main` inside the worktree before starting any task,
pulling in the required Plan 01-01 work, then proceeded normally. No data loss, no
manual intervention needed — flagged here in case a future multi-plan wave hits the
same staleness with a worktree that already has unique commits (which would NOT be a
safe fast-forward and would need real reconciliation).

### 2026-09-02 — Testcontainers requires an extra Colima socket-path override
`./gradlew test` invocations that use Testcontainers (this project's `RestartSurvivalTest`
and repository tests) failed to start the Ryuk resource-reaper container under Colima
even with `DOCKER_HOST` correctly pointed at Colima's socket. **Resolution:** also export
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` — Testcontainers/Ryuk tries
to bind-mount the host socket path literally by default, which doesn't match where
Colima's VM actually exposes it. Documented in `.planning/STATE.md` Blockers/Concerns
alongside the existing `JAVA_HOME=temurin-21` requirement from Plan 01-01, since every
future `./gradlew test` run in this phase needs both exports.

### 2026-09-02 — RestartSurvivalTest was silently testing against the wrong database
While building Plan 01-02's core restart-survival proof, the test initially passed but
was actually writing to and reading from the persistent local dev PostgreSQL
(`muvimatchr-db-1` from Plan 01-01's `docker-compose.yml`), not the ephemeral
Testcontainers-managed instance the test was supposed to use — which would have made the
"proof" meaningless (a green result regardless of whether restart-survival genuinely
worked). Root cause: `SpringApplicationBuilder.properties(...)` sets *default*
(lowest-precedence) properties, so the Testcontainers JDBC URL was silently overridden by
the higher-precedence classpath `application.properties` datasource config pointing at
the dev database. **Resolution:** switched to passing the same values as
command-line-style `--key=value` args to `.run(...)`, which have Spring Boot's highest
property-source precedence and correctly win. Confirmed the fix via a direct `psql` query
against the dev database showing no further test writes landing there. One stray
`join_code='ABC123'` row from before the fix was found and deleted from the dev database
this session.

### 2026-09-02 — PostgresTestSupport's per-class container lifecycle broke on a second consumer
Plan 01-02's shared `PostgresTestSupport.kt` fixture used JUnit's standard
`@Testcontainers`/`@Container` lifecycle, which stops and restarts the container per
test *class*. This worked fine with only one consumer (`SessionRepositoryTest`), but
Plan 01-03 added a second consumer (`VoteRepositoryTest`): on a full-suite run, the
extension stopped the first class's container and started a fresh one (different port)
for the second class, while Spring's cached `ApplicationContext`/`DataSource` kept
pointing at the now-dead first container — `Connection refused`, but only when running
the whole suite together, never when running either test class in isolation (which made
it easy to miss). **Resolution:** switched `PostgresTestSupport` to Testcontainers'
documented singleton-container pattern — manual `.start()`, no per-class stop — so one
container instance is shared and stays alive across every test class in the run.
`./gradlew test` green afterward (6/6, full suite).

### 2026-09-02 — Phase 1 (Persistence Foundation) complete
All 3 plans executed and merged (`01-01` toolchain/local DB, `01-02` Session tracer
slice, `01-03` Participant/Vote tables). All four ROADMAP.md Phase 1 success criteria
confirmed via automated tests, independently re-verified on `main` after each merge
(not just trusted from the executor's own report): restart survival across all three
entities, vote tuple uniqueness + working upsert, session join-code uniqueness, and
Flyway migrate-once/reapply-safety. `./gradlew test` is green (6/6) on `main`.
`ROADMAP.md` and `.planning/STATE.md` marked Phase 1 complete. Next: discuss/plan Phase
2 (Session & Lobby Flow).

### 2026-09-02 — Phase 2 planned (`/gsd-plan-phase 02`), after 4 prior interrupted attempts
Four earlier attempts at planning this phase were interrupted by connection
errors/stalls before any PLAN.md reached disk. This attempt completed and wrote both
plans early/incrementally per the retry guidance. Two things worth carrying forward:

1. **02-RESEARCH.md is wrong that Jakarta Bean Validation is transitively available**
   — confirmed again this session by reading `build.gradle.kts` directly: no
   `spring-boot-starter-validation` (Spring Boot decoupled it from
   `spring-boot-starter-web` since Boot 2.3, never re-added here). Plan 02-02 Task 1
   adds the dependency explicitly and verifies `hibernate-validator` actually resolves
   on the compile classpath (not just that the Gradle coordinate is declared).
2. **Caught a real correctness bug in 02-RESEARCH.md's own illustrative join-code
   retry code before it could ship**: wrapping the collision-retry loop in one
   `@Transactional` method using `saveAndFlush` per attempt is broken on PostgreSQL —
   once one statement in a transaction fails, Postgres aborts the *entire* transaction
   ("current transaction is aborted"), so a second `saveAndFlush` after the first's
   constraint violation would fail immediately, not retry cleanly. Plan 02-01's
   `SessionService.createSession()` deliberately has no `@Transactional` of its own and
   calls plain `save()` per attempt instead, so each attempt gets its own transaction —
   consistent with how Phase 1's own `SessionRepositoryTest` already behaves (no
   surrounding `@Transactional`, two sequential `saveAndFlush` calls, first commits,
   second fails cleanly).

Also required updating two Phase 1 test files (`VoteRepositoryTest.kt`,
`RestartSurvivalTest.kt`) as part of Plan 02-01 Task 1, since both construct
`Participant(...)` without the new required `tokenHash` constructor argument that this
phase adds — a compile-breaking ripple from making the token column `NOT NULL` with no
default, caught during planning rather than left for the executor to discover.

### 2026-09-03 — Plan 02-01 executed sequentially after a worktree stale-base mismatch; three Spring Boot 4.1.1 test-support surprises hit and fixed
A prior isolated-worktree dispatch for Plan 02-01 hit a stale-base mismatch against
`origin/HEAD`, so per the project's documented #2649/#683 auto-degrade policy this run
executed sequentially on `main` instead. No worktree cleanup was needed (`git worktree
list` showed only `main`).

During Task 1, three framework-version issues surfaced that neither 02-RESEARCH.md nor
02-PATTERNS.md could have anticipated (both predate hands-on compilation against this
exact Boot 4.1.1 dependency graph):

1. **The plan's claim that `RestartSurvivalTest.kt` already imports `java.util.UUID` was
   wrong.** Only `VoteRepositoryTest.kt` did. Adding the required `tokenHash =
   UUID.randomUUID().toString()` constructor arg without the import would not compile —
   added the missing import (Rule 3).
2. **`@AutoConfigureMockMvc` doesn't exist where the standard Spring Boot docs/examples
   put it in this version.** Boot 4.1.1 modularized MockMvc test autoconfiguration out
   of `spring-boot-test-autoconfigure` into a brand new `spring-boot-webmvc-test`
   artifact, relocating the class to
   `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`. Added the
   dependency explicitly to `build.gradle.kts` (version auto-resolved via the existing
   Spring Boot BOM).
3. **Autowiring `com.fasterxml.jackson.databind.ObjectMapper` in a test threw
   `NoSuchBeanDefinitionException`.** Boot 4.1.1's Jackson autoconfiguration registers a
   Jackson 3 (`tools.jackson.databind.ObjectMapper`) bean by default — a different type
   from the classic Jackson 2 `ObjectMapper` the project's pre-existing
   `jackson-module-kotlin` dependency provides on the classpath. Switched the test to
   autowire `tools.jackson.databind.ObjectMapper` instead.

All three fixed inline as Rule 3 blockers (compile/runtime errors preventing task
completion, not architectural changes), verified via `./gradlew test`/`build`, and
documented in `02-01-SUMMARY.md`. Worth flagging for Phase 3+ planning: this project is
on a genuinely bleeding-edge Spring Boot version, so any RESEARCH.md/PATTERNS.md code
example involving Boot's test-support or Jackson auto-configuration should be treated as
a *shape* reference, not copied verbatim — verify actual package/artifact names against
the resolved classpath before trusting an import.

### 2026-09-03 — Phase 2 fully executed, reviewed, verified, and closed out in one session
Ran `/gsd-execute-phase 02` end-to-end: Plan 02-01 (already logged above), then Plan
02-02, then the phase-close gates (code review, goal verification, `phase.complete`,
PROJECT.md/STATE.md evolution).

**Worktree isolation stayed broken for the whole session.** Local `main` was already 8
commits ahead of `origin/main` before this session started (nothing here has been pushed
recently), so Claude Code's `isolation="worktree"` — which forks from `origin/HEAD`, not
live local HEAD — hit the same stale-base mismatch for every plan this session, not just
02-01. Rather than re-attempt a worktree dispatch for 02-02 that was certain to fail the
same way, it was dispatched directly in sequential mode (re-recording the
`dispatch-isolation` sentinel scoped to that plan first, since the phase-level sentinel
still said `harness-worktree` and the isolation guard rejects a non-worktree dispatch
against a stale sentinel). **This will keep happening on every phase until `main` is
pushed to `origin`** — worth doing before the next multi-plan phase if parallel
worktree execution is wanted back.

**Plan 02-02** added `spring-boot-starter-validation` explicitly, Bean Validation on the
join request, and closed the phase's last two proof gaps (3+ participants, join-code
distinctness). No deviations, no checkpoints. `./gradlew test` 20/20 green.

**Code review** (`02-REVIEW.md`, standard depth) found 0 Critical / 4 Warning / 7 Info —
the auth/crypto core (SecureRandom + SHA-256 hash-at-rest + hash-then-compare resolution)
was confirmed sound. Warnings are all forward-looking hardening (case-sensitive join-code
lookup, no rate limiting on join, no DEFAULT on the new NOT NULL migration column, token
in resumeUrl query string) rather than defects in what Phase 2 actually needed to prove —
none blocked completion, all carried to STATE.md Blockers/Concerns for before-public-launch
attention.

**Goal verification** (`02-VERIFICATION.md`) independently re-ran the full test suite
from JUnit XML output rather than trusting SUMMARY.md's claimed pass count, and passed
9/9 must-haves. One clause of ROADMAP success criterion 4 (vote-attribution-on-resume)
is explicitly deferred to Phase 4 since no voting exists yet — documented as intentional,
not a gap.

Phase 2 marked complete (`phase.complete`); PROJECT.md evolved (3 requirements Active →
Validated, Key Decisions outcomes filled in, 2 new implementation decisions logged).
Next: discuss/plan Phase 3 (TMDB Integration & Catalog Caching) — no CONTEXT.md yet.

### 2026-09-03 — `state.record-session` silently corrupted the progress counter
Running `gsd_run query state.record-session --stopped-at "Phase 3 context gathered" ...`
at the end of the Phase 3 discuss session rewrote `.planning/STATE.md`'s
`progress.completed_phases` from 2 to 1 and `progress.percent` from 33 to 17 — even
though `ROADMAP.md` still correctly shows Phase 1 and Phase 2 both complete. Nothing in
the discuss-phase workflow instructs the tool to touch that field for a context-only
session; this looks like a bug in the tool's progress-recomputation logic (possibly
conflating "current phase's own plan-completion count" with "phases complete count").
**Resolution:** manually corrected `completed_phases`/`percent` back to 2/33 and
committed the fix (`12f059a`) immediately after the tool's own commit (`bc73fbb`).
**Not yet fixed upstream** — worth a closer look if `state.record-session` is run again
and the counter drifts a second time; may need to file/check for a gsd-core issue.

### 2026-09-04 — Phase 3 (TMDB Integration & Catalog Caching) executed end-to-end, code-reviewed, fixed, and verified

Ran `/gsd-execute-phase 03` through all 5 waves (tracer-first, strictly sequential —
each wave is a single plan depending on the previous): 03-01 tracer slice (TMDB
WebClient → cache → endpoint), 03-02 session region/provider filter state, 03-03
cached genre/watch-provider reference catalogs, 03-04 provider filtering + per-movie
availability, 03-05 resilience (TMDB-unreachable degradation ladder, sparse-result
handling). Each wave dispatched as a `gsd-executor` subagent in a Claude Code
`isolation="worktree"` worktree, merged via `worktree.cleanup-wave`, then a full
`./gradlew build` gate before advancing.

**Worktree isolation broke again on Wave 2 for exactly the reason logged 2026-09-03:**
local `main` had been pushed once at the start of the session but not after Wave 1's
merge, so Claude Code's `isolation="worktree"` (forks from `origin/HEAD`, not live
local HEAD) created Wave 2's worktree from a stale base — the executor's own
`<worktree_branch_check>` guard correctly caught the mismatch and halted cleanly (exit
42, zero commits, harness auto-removed the empty worktree). **Fix applied this
session:** `git push origin main` after every wave's merge, not just once at the
start. This kept all of Waves 2-5 on the fast worktree-isolation path. Confirms the
2026-09-03 note was right — this needs to become standing practice for any multi-wave
phase, not a one-off before the first dispatch.

**Local dev environment needed three env vars that aren't in any project file:**
`JAVA_HOME` pinned to Corretto 21 (system default is JDK 25; the Gradle daemon itself
fails to start under 25, not just the project's toolchain target), `DOCKER_HOST`
pointed at Colima's non-standard socket path (`~/.colima/default/docker.sock` — the
orchestrator's non-interactive shell doesn't inherit the interactive shell's Colima
env hook), and `TESTCONTAINERS_RYUK_DISABLED=true` (Ryuk's reaper container fails to
start under Colima on this machine). None of this blocked the executor subagents
(their own shell context apparently already had a working setup) — only the
orchestrator's own `./gradlew build` gate calls between waves needed it. Worth adding
a `gradle.properties` / documented dev-setup note if this keeps recurring.

**Code review found 4 Critical + 4 Warning** (`03-REVIEW.md`, standard depth, 37
files) — all real bugs, not stylistic: an unbounded provider-id list could overflow
both the deck cache key's `VARCHAR(128)` column and session's `VARCHAR(255)` column,
the latter silently misdiagnosed as a join-code collision and retried 10 times; the
watch-providers region param had no format validation; and — most notably — Wave 5's
own retry/degradation ladder (the whole point of that wave) only matched
`WebClientResponseException`, so a genuine connection-level failure (refused,
timeout, DNS) bypassed the stale-fallback/503 path entirely, with zero test coverage
of that scenario. Ran `/gsd-code-review 03 --fix` (critical_warning scope) — all 8
fixed and independently re-verified present in the codebase (not just claimed) by the
verifier agent afterward. One retry mid-session: the first `--fix` dispatch hit an
account-wide session usage limit after only creating its worktree (zero edits) —
cleanly recoverable by removing the empty worktree/branch and re-dispatching once the
reset time passed.

**Goal verification: 5/5 automated ROADMAP criteria pass, phase left at `human_needed`**
(not `passed`) — 4 items (live-TMDB "real data" confirmation, live sparse-filter
behavior, TMDB's undocumented omitted-parameter default for monetization types, and
CR-04's connectivity-failure retry path) all require a real `TMDB_API_TOKEN`, absent
on this dev machine. These were already open items in `WINDOWS.md` from earlier
plans' own `<human-check>` blocks, not new gaps. Per protocol, phase was **not**
marked complete — `03-UAT.md` was created from `03-VERIFICATION.md`'s
`human_verification` list and committed; `/gsd-verify-work 3` will walk through the 4
live-API checks and auto-transition the phase to complete once they pass. Also fixed
a small doc-staleness gap the verifier flagged: `REQUIREMENTS.md` had CTLG-05 as
unchecked/"Pending" while CTLG-01–04 showed "Complete" despite equivalent evidence
strength — corrected both the checkbox and the traceability table row.

**Next:** get a `TMDB_API_TOKEN` into this dev environment, then run
`/gsd-verify-work 3` to close out the 4 pending UAT items and complete Phase 3.

### 2026-09-04 — `state.record-session` progress-counter drift recurred a third time
Same bug logged 2026-09-03 (Phase 3 context session) fired again after the Phase 4
context session: `progress.completed_phases` dropped from 3 to 2 (and `percent` from
50 to 33) in `STATE.md`'s frontmatter even though nothing regressed — Phase 3 is still
complete. `state.json` (the newer state artifact) was correct both times; only
`STATE.md`'s YAML frontmatter drifts. **Resolution:** manually corrected back to 3/50
and committed (`dcd9c25`), same as the prior occurrence. **Still not fixed upstream**
— three occurrences now (2026-09-03 Phase 3 context, and twice more implied by
STATE.md's own Blockers note before this). Worth filing/checking a gsd-core issue
rather than continuing to patch it by hand every phase transition.

### 2026-09-06 — Phase 4 executed, code-reviewed, and closed out
Ran all 4 plans of Phase 4 (Vote Recording & Match Aggregation) sequentially — worktree
isolation auto-degraded for the whole run because local `main` was ahead of
`origin/main` (per #683's base-check). Wave 1 (04-01) wired the tracer path end-to-end
(deck pinning, transactional vote recording, live status); Wave 2 (04-02, 04-03) added
genre-as-session-state filter locking and unanimous-match/like-count aggregation; Wave
3 (04-04) proved concurrency and restart durability with 10-iteration races against
real Postgres. Each executor self-fixed a handful of Rule-1 bugs discovered while
running its own plan's verification (a Jackson field-name bug, test-fixture cache
pollution, a pre-existing test broken by pinning) — all documented in their SUMMARYs.

**Code review found a real gap:** CR-01 — `SessionService.pinDeck` had no row lock
(unlike `VoteService.recordVote`), so concurrent first-time deck pins could race, and
the HTTP response was built from the caller's own fetch rather than the persisted
snapshot. Fixed (plus 3 related warnings) via `gsd-code-fixer`. **Human verification
of those fixes then caught two regressions the fixer's static-only pass couldn't
have seen without running the suite:** the CR-01 fix had hardcoded the deck response's
`stale` flag to `false` (broke the outage-fallback test), and the new
`DeckPinConcurrencyTest` compared raw JSON strings across a Postgres `jsonb` round-trip
(which doesn't preserve object-key order/whitespace) — a false-positive test failure,
not a real concurrency bug. Both fixed and verified stable across repeated runs.
**Lesson:** a code-fixer's Tier-1 (re-read, no execution) verification is not a
substitute for actually running the test suite when the environment can support it —
worth building that into the fix-pass protocol rather than relying on ad hoc
follow-up.

Nyquist validation: 8/8 per-task rows had real, named, passing tests — zero gaps,
`nyquist_compliant: true`. Security review: 22 threats across all 4 plans (plus 2 more
closed by the code-review fix pass) — all mitigated/accepted, `threats_open: 0`. UAT:
1 item (concurrency-under-real-load, the plan's own designated human-check) — passed.
Phase marked complete, PROJECT.md evolved (4 requirements moved to Validated, 4 new
architectural decisions logged).

**Drift bug recurred a third time** (see prior 2026-09-03/2026-09-04 entries):
`STATE.md` frontmatter's `completed_phases`/`percent` again lagged `state.json` after
`phase.complete` ran (3→4, 50%→67% needed manual correction). This is now a confirmed
pattern across three separate phase transitions, not a fluke.

**Next:** `/gsd-discuss-phase 5` or `/gsd-plan-phase 5` — Real-Time Notification Layer.

### 2026-09-06 — Phase 5 context gathered; `state.record-session` drift recurred a fourth time (now confirmed broader than `phase.complete`)
Ran `/gsd-discuss-phase 5`. Prior project research (`ARCHITECTURE.md`, `STACK.md`,
`PITFALLS.md` from the original roadmap research) had already answered almost all of
the "how": STOMP over WebSocket, `/topic/session/{id}` broadcast, fresh-DB-read before
every broadcast, and "reconnect-safe client, not reconnect-safe server" (client always
does a REST status fetch on connect/reconnect; WS is a push convenience only, never
the record of truth). The existing `VoteController.getStatus()` REST endpoint already
returns everything needed for that reconciliation — this phase only adds a publisher
on top of `MatchAggregationService`, no new aggregation logic.

Presented 4 genuinely open gray areas (WS subscription security, disposition of the
legacy prototype `WebSocketConfig.kt`/`WebSocketController.kt` `/lobby` toy code,
manual-vs-automated verification given no frontend exists yet, and broadcast trigger
scope). User's answer: "you can always take your first assumption" — proceed with
Claude's own default recommendation on each rather than debate turn-by-turn. Resolved:
(1) defer STOMP-level token validation as an accepted risk, same pattern as Phase 2/3's
already-accepted "no rate limiting" — topic keyed by session UUID, not the weaker
6-char join code; (2) delete the legacy lobby-prototype WS files now, fully superseded;
(3) automated `WebSocketStompClient` integration tests only, no throwaway manual HTML
page — matches Phases 1-4's backend-first pattern; (4) broadcast triggers only from
`VoteService.recordVote()`, no scheduled job for idle-timeout transitions (they
self-correct on next read per Phase 4's D-07 "always live, never sticky" rule). Full
detail in `05-CONTEXT.md`/`05-DISCUSSION-LOG.md`.

**Drift bug recurred a fourth time, and this occurrence narrows the cause:** this was
a plain `/gsd-discuss-phase` context-gathering session — no `phase.complete` call at
all — yet `STATE.md` frontmatter still reset `completed_phases` 4→3 and `percent`
67%→50% via `state.record-session`. The first three occurrences (2026-09-03, twice
2026-09-04/09-06) all happened around `phase.complete`, which pointed suspicion there;
this one proves `state.record-session` itself is the actual culprit, independent of
phase completion. Manually corrected back to 4/67% and committed (`d0a9aa6`). Four
occurrences now — worth filing as a real `gsd-core` defect rather than continuing to
hand-patch every session.

### 2026-09-06 — Phase 5 planned (skipped research; pattern-mapped; plan-checker passed clean)

Ran `/gsd-plan-phase 5`. User chose to skip phase research (CONTEXT.md already embedded
the relevant `ARCHITECTURE.md`/`STACK.md`/`PITFALLS.md` citations directly — nothing left
to investigate for a well-documented Spring STOMP integration over an already-built
`VoteService`). No phase SPEC.md exists, so the deterministic edge-probe fallback ran
against RTIME-01/02/03 directly; all three came back `unclassified/unresolved` (a
genuine non-classification, not zero-edges) and were carried into both plans as explicit
`<assumptions>`, not silently dropped or fabricated as resolved. Pattern-mapper ran
first and produced `05-PATTERNS.md`.

Planner produced 2 plans across 2 waves (05-01 tracer: swap prototype `/lobby` WS for
session-scoped STOMP + prove one broadcast end-to-end; 05-02, blocked on 05-01: N-of-M
progression/fan-out + reconnect reconciliation). Three findings changed the plan
materially: (1) deleting the legacy `WebSocketController.kt` (D-03) would have broken
compilation — `LobbyController.kt` references its `LobbyEvent` and injects the
`SimpMessagingTemplate` bean the old `@EnableWebSocketMessageBroker` provides, so the
delete-and-rebuild had to be one atomic task, not two; (2) broadcasting the raw
`SessionVoteStatus` would have silently violated RTIME-03 — `VoteController` renames the
wire field to `isComplete` via `@JsonProperty`, so `toResponse()` was promoted to a
shared extension so REST and WS serialize identically; (3) the planner registered the
broadcast as an `afterCommit` transaction hook (not inline, which risked publishing
pre-commit state or rolling back a vote on a broker throw) and added a `ChannelInterceptor`
to block forged client `SEND` frames on the topic — a new HIGH-severity threat the
open `SimpleBroker` introduced that CONTEXT.md's D-01 didn't cover (D-01 only defers
*token* validation, not this).

Plan-checker (haiku) passed clean on the first pass — 0 blockers, 0 warnings on both the
verify-command-path and failing-direction deterministic probes; all 5 CONTEXT.md
decisions and all 3 RTIME requirements confirmed visible in plan tasks/must_haves.
Requirements + decision coverage gates and the post-planning gap-analysis gate all
passed 8/8. Committed as `4952d64` (plans) and `14f5baa` (STATE.md/PATTERNS.md).

### 2026-09-06 — Plan 05-01 executed (session-scoped STOMP layer + first end-to-end proof)

Resumed a prior interrupted attempt (uncommitted partial diff from a transient API
error, nothing previously committed). Verified the partial work against the plan and
found it correct and complete for Task 1, so continued from it rather than redoing it.

**Task 1 (`c548226`):** deleted the prototype `/lobby` WebSocket layer
(`config/WebSocketConfig.kt`, `controller/WebSocketController.kt`), replaced it with a
session-scoped STOMP layer in a new `realtime/` package — `/ws` handshake, simple
broker on `/topic`, a `ChannelInterceptor` rejecting every client `SEND` frame
(T-05-03), and `SessionEventPublisher.broadcastStatus` registered from
`VoteService.recordVote()` as an `afterCommit` transaction synchronization so a push
never precedes the write it describes. `LobbyController.kt`'s one compile dependency
on the deleted `WebSocketController.LobbyEvent` was repaired by relocating that data
class into the same file, unrelated Thymeleaf prototype code otherwise untouched.
Full pre-existing suite green against the new broker config.

**Task 2 (`d85ea4a`):** built `StompTestSupport` (real `@SpringBootTest(RANDOM_PORT)`
harness, real `WebSocketStompClient`, real session/participant fixtures) and
`SessionStatusBroadcastTest`'s four end-to-end proofs — a vote produces a push, the
pushed JSON is field-for-field identical to the REST status JSON including the
`isComplete` wire name, a REST fetch on push receipt is never older than the push, and
a forged client `SEND` reaches no subscriber. Two real bugs found and fixed mid-task,
neither anticipated by the plan text: (1) `StringMessageConverter` (which the plan
called for) silently drops every real object-broadcast frame because its `text/plain`
mime-type matching rejects the broker's `application/json`-tagged frames — a same-type
raw-string test frame passed fine, which made this look like a subscription-timing
race at first; fixed by leaving the client's default `SimpleMessageConverter` in place
and requesting a `ByteArray` payload type instead, which performs zero mime-type
filtering. (2) `StompSession.subscribe()` returns as soon as the frame is queued for
send, not once the broker has registered it — a genuine race that can silently lose
the very first broadcast; fixed with a bounded marker-frame round-trip
(`subscribeAndAwaitReady`) instead of a fixed sleep. Also discovered `TestRestTemplate`
does not exist anywhere on this project's Spring Boot 4.1.1 classpath (confirmed
absent even from the dedicated `spring-boot-restclient-test` module) — used
`java.net.http.HttpClient` instead, keeping the "no new dependency" goal intact.

All four tests pass on two consecutive runs (second with `--rerun-tasks`); full
`./gradlew test` suite green. Plan metadata committed (`73daf04`, `b286a37`).
Full plan SUMMARY at `.planning/phases/05-real-time-notification-layer/05-01-SUMMARY.md`.

**Drift bug recurred a FIFTH time**, this time via `state.update-progress`/
`state.record-session` during this plan's own close-out — reset `completed_phases`
4→3 and `percent` 67%→50% again, with no phase completion involved. Manually
corrected back to 4/67%, per the now-established pattern. Five occurrences across
`phase.complete`, `state.record-session` (x2), and `state.update-progress` — clearly
shared recompute logic across state-mutation verbs, not one isolated command.

**Deferred:** Task 2's `<human-check>` (start the app against local compose Postgres,
confirm the startup log shows a `/ws` STOMP endpoint with no fallback transport, and
that `templates/lobby.html` no longer opens a socket) is explicitly scoped by the plan
to end-of-phase, not this plan — not yet performed.

### 2026-09-06 — Plan 05-02 executed (N-of-M progression, multi-client fan-out, reconnect reconciliation); last plan of Phase 5

**Task 1 (`98e70c9`):** added three tests to `SessionStatusBroadcastTest` (four from
05-01 untouched): sequential three-participant/two-movie progression asserting
`finishedCount` climbs exactly 1, 2, 3 against a stable `activeCount` of 3 with
`isComplete` false until the last finish (RTIME-01); a two-client fan-out test
asserting both connections receive an identical `isComplete: true` payload with the
pinned movie in `matchedMovieIds` (RTIME-02, T-05-02); and a cross-session isolation
test proving a session-A subscriber hears nothing while session B completes, then does
receive session A's own frame once voted (T-05-08).

Found and fixed a real `StompTestSupport` harness bug mid-task, not anticipated by the
plan: the fan-out test's second concurrently-subscribed client's readiness-marker
handshake broadcasts to the whole topic, so its marker was landing in the *first*
client's already-returned queue and corrupting it with a non-JSON string (a
`StreamReadException` instead of a clean assertion failure). Fixed by routing all
marker frames to a dedicated shared sink inside `StompTestSupport`, separate from every
subscription's real message queue — any test with 2+ concurrent subscribers to the same
topic was silently exposed to this before.

**Task 2 (`e77ae6d`):** new `ReconnectReconciliationTest` (3 tests) — a witness client
stays connected throughout while a second client is explicitly disconnected before the
completing votes, then reconnects with a fresh session and resubscribes; its queue
yields nothing, proving no last-known-status buffer and no per-client delivery ledger
(RTIME-03, ARCHITECTURE.md Pattern 4). A REST reconciliation test proves the
authenticated `/votes/status` body is field-for-field identical to the witness's last
recorded push, and that the same fetch without an `Authorization` header does not
return 200 — pinning the deliberate topic-open/REST-authenticated asymmetry (T-05-09).
A no-subscriber test proves a session completes correctly via both `computeStatus()`
and REST with zero STOMP clients ever connected — the notification layer can never
become load-bearing for correctness (T-05-11). Negative source assertion confirms no
`lastStatus`/`statusCache`/`lastKnown`/`replayBuffer`-shaped field exists anywhere under
`realtime/` (T-05-10). No new production code — test files only, as scoped.

All tests pass on two consecutive runs per class (second with `--rerun-tasks`); full
`./gradlew test` and `./gradlew build` green. Plan metadata committed (`c8e4369`). Full
plan SUMMARY at `.planning/phases/05-real-time-notification-layer/05-02-SUMMARY.md`.

**Drift bug recurred a SIXTH time**, via `state.advance-plan`/`state.update-progress`/
`state.add-decision`/`state.add-blocker` during this plan's own close-out (05-02 is
Phase 5's last plan, but Phase 5 itself is not yet transitioned/complete) — reset
`completed_phases` 4→3 and `percent` 67%→50% repeatedly across the sequence of calls.
Manually corrected back to 4/67% after the last state-mutating call, per the
now-established pattern. Six occurrences now span `phase.complete`,
`state.record-session` (x2), `state.update-progress` (x2), and `state.advance-plan` —
this is clearly one shared, broken progress-recompute path invoked by essentially every
state-mutation verb, not an isolated command; worth filing as a real defect upstream
rather than continuing to hand-patch every single plan close-out.

**Deferred, carried into Phase 6:** two `<human-check>` items remain unperformed
against a real locally-running app rather than just the automated embedded-server
suite — 05-01 Task 2's STOMP-endpoint-in-startup-log check, and this plan's Task 2
reconnect walkthrough. Both plans explicitly scope these to whenever Phase 6's SPA
first exercises the endpoints from an actual browser, so they're bundled there rather
than done as a standalone manual pass now.

### 2026-09-06 — Phase 5 closed out: worktree degrade, code review, UAT (including a live scripted STOMP walkthrough), Nyquist, security, transition

`/gsd-execute-phase 5` ran both plans. Worktree isolation degraded to sequential for
the whole phase before any dispatch (#683-class: local `main` was ahead of unpushed
`origin/main`, so Claude Code's `isolation="worktree"` would have forked from a stale
`origin/HEAD`). 05-01's first attempt was cut short by a transient API error mid-response
(no commits made, safe to resume); the resumed attempt completed cleanly. 05-02 executed
without incident.

Code review (05-REVIEW.md): 0 Critical, 5 Warning, 2 Info — no blockers. Notable warnings:
a `voting↔realtime` package cycle via `VoteController.toResponse()`; legacy
`LobbyController.kt`'s pre-existing unsynchronized-map/unchecked-cast risk (still live,
slated for Phase 6 removal); `StompTestSupport`'s fixed `+1000` movie-ID range as a
latent test-pollution risk if `deckSize` ever exceeds 1000. Regression gate: full
136-test suite (all phases) re-run fresh, 0 failures — required manually resolving this
machine's `JAVA_HOME`/`DOCKER_HOST`/`TESTCONTAINERS_RYUK_DISABLED` env quirks and
Gradle's up-to-date caching (needed `--rerun-tasks` for a genuine run) since the generic
regression-gate script has no Kotlin/Gradle branch and would otherwise no-op to `true`.

Phase verifier: 15/15 must-haves, `human_needed` (the two plan-designated end-of-phase
`<human-check>` items). Persisted as `05-UAT.md` and ran through `/gsd-verify-work 5`.

Mid-UAT, the user questioned whether Phase 4's concurrency UAT (marked `pass` in a prior
session neither of us could see the transcript of) had actually been run manually or
just rubber-stamped. Re-ran `VoteServiceConcurrencyTest`/`DeckPinConcurrencyTest` live
against a genuine concurrent Gradle compile in the background (an unbounded `yes`+`dd`
load generator was blocked by the auto-mode safety classifier as fork-bomb-shaped; a
real bounded `gradlew compileKotlin` build substituted cleanly) — both passed under real
contention, settling the doubt regardless of what happened before.

For Phase 5's own UAT test 1 (startup log + dead `lobby.html` socket), the user did the
check themselves. For test 2 (live RTIME-03 reconnect walkthrough), the user asked me to
set it up — driven via a small Node script (`fetch` + a hand-rolled STOMP-over-WebSocket
client, no new dependencies) against a real running `bootRun` instance. `TMDB_API_TOKEN`
is still unavailable on this dev machine (same gap as Phase 3), so the deck was pinned by
seeding `deck_cache_entry` directly via SQL (`docker exec ... psql`) instead of a live
TMDB fetch — the notification/reconnect behavior under test doesn't depend on where the
deck data came from. Result: 0 frames on the reconnected STOMP socket after a 3s wait,
REST status returned `isComplete:true` with the correct `matchedMovieIds`. User reviewed
the transcript and confirmed pass. (Side note: cleanup after the walkthrough used a
slightly-too-broad `pkill` that also killed the local Gradle daemon — harmless, it just
respawns on next build, but worth using a narrower kill target next time.)

Nyquist validation (05-VALIDATION.md, State B/reconstructed): all 4 tasks across both
plans have automated `<verify>` commands, zero gaps, `nyquist_compliant: true` — no
auditor spawn needed. Security review (05-SECURITY.md, State B): 11 threats from both
plans' `<threat_model>` blocks (9 mitigate + 2 accept), every mitigation independently
grep/test-verified against the actual codebase rather than trusted from the plan text,
`threats_open: 0`.

Verification canonicalized `human_needed` → `passed` after UAT closed with zero issues.
Transition ran standalone: `phase.complete` (Phase 5 → Phase 6), PROJECT.md evolved (new
Key Decisions rows for the STOMP transport/after-commit-broadcast/no-replay-cache/
accepted-risk decisions; the two frontend-half Active requirements annotated with what
Phase 5's backend delivered, left un-Validated since no UI exists yet).

**Drift bug recurred a SEVENTH time** — this time on `phase.complete` itself, the one
call whose entire job is getting this number right: after genuinely completing Phase 5,
STATE.md's frontmatter was left at `completed_phases: 4`/`67%` (state.json correctly
showed phase 5 `status: complete` throughout — only STATE.md's frontmatter block is
affected, as with all six prior occurrences). Manually corrected to `5`/`83%`. This is no
longer "some state-mutation verbs have a shared bug" — the primary, intended call site
for marking a phase done also hits it. Filing this upstream is overdue.

### 2026-09-06 — Phase 6 context gathered (Frontend SPA)

Ran `/gsd-discuss-phase 6` in default interactive mode. No SPEC.md, no prior
checkpoint, no existing plans — clean start. Loaded PROJECT.md/REQUIREMENTS.md/
STATE.md plus the three most recent prior CONTEXT.md files (05, 04, 02) and the
Phase 6-relevant slices of STACK.md/ARCHITECTURE.md/PITFALLS.md. Confirmed via
`find`/`ls` that no `frontend/` scaffold exists yet and the legacy prototype's
7 Thymeleaf templates + `spring-boot-starter-thymeleaf` are still present and
untouched.

Discussed all 4 presented gray areas (user selected all): build & deploy
integration, session URL scheme & resume-token handling, swipe deck
interaction, waiting/results screen content. Key decisions captured in
`06-CONTEXT.md`:

- Single deployable JAR — `frontend/` (Vite/React/TS) built via a Gradle task
  and copied into Spring Boot static resources, no CORS/two-service split.
- Delete the 7 legacy Thymeleaf templates + thymeleaf dependency now, not
  deferred to end of phase (matches Phase 5's precedent of deleting the legacy
  WebSocketConfig/Controller outright rather than leaving it alongside new code).
- `/s/{joinCode}` shareable URLs, real React Router with one route per screen
  (join/swipe/wait/results) — each route re-fetches live status on mount and
  redirects if the user is "ahead" of their real state, so routing is purely
  presentational and adds no new access-control surface.
- Resume-link bearer token stays in the URL query string permanently (user's
  explicit choice, against the recommended localStorage-and-scrub option) —
  consistent with the already-accepted Phase 2 risk logged in `02-REVIEW.md`.
- Swipe deck: 2-3 card stack depth, color-tint + rotation drag feedback,
  deck-exhaustion auto-transitions to waiting (no dead end), like/pass buttons
  shown on desktop only (hidden on mobile, which stays drag-only).
- Waiting screen shows a named per-participant done/waiting roster, not a bare
  count. Results screen shows a plain "no match" message on a genuine
  zero-mutual-match outcome (full ranked-fallback UX stays deferred to v2 per
  REQUIREMENTS.md RSLT-05 — confirmed as already-scoped-out, not a new
  deferral).

One live back-and-forth worth noting: the user pushed back on the routing
question ("can users mess with the session when its client side?") before
picking React Router. Answered directly rather than re-asking the same
multiple-choice — the backend is the sole authority on all real session state
regardless of URL, so client-side routing changes nothing about what a user
can actually do. Also needed two rounds of clarification on the like/pass
button question ("we need two options for phones and for pcs" → "buttons do
not show up on mobile") before landing on the final responsive-buttons
decision — a case where the initial multiple-choice options didn't cover the
actual answer the user had in mind, resolved via targeted single-select
follow-ups rather than guessing.

Committed `06-CONTEXT.md` + `06-DISCUSSION-LOG.md` (`d138349`).

**Drift bug recurred an EIGHTH time** — `state.record-session` reset
`completed_phases`/`percent` from `5`/`83%` back to `4`/`67%` even though this
was purely a context-gathering session (Phase 5 already complete, no
completion event occurred). Manually corrected back to `5`/`83%` and logged in
STATE.md Blockers/Concerns as the eighth confirmed occurrence, now spanning six
distinct call sites including `phase.complete` itself. Committed separately
(`2c3b553`).

Next: `/gsd-plan-phase 6` to turn this context into an executable plan.

### 2026-09-06 — Phase 6 planned (Frontend SPA)

Ran `/gsd-plan-phase 6`. User opted to **skip phase-level research** (STACK/
ARCHITECTURE/PITFALLS from project-level research plus 06-CONTEXT.md were
judged sufficient) and to **continue without Nyquist's VALIDATION.md** when
prompted (no RESEARCH.md → no Validation Architecture section to derive it
from). Spawned `gsd-pattern-mapper` first (no capability skip since
`workflow.pattern_mapper` is on) — it produced `06-PATTERNS.md`, surfacing one
load-bearing finding: `LobbyController.kt`/`MovieVoteController.kt` are the
only remaining callers of the view-name strings tied to the 7 templates D-02
deletes, and would 500 on `/` and `/vote` if left behind — folded into the
planner's brief so the teardown wouldn't silently miss them.

Since there's no SPEC.md for this phase, ran the spec-less edge-probe fallback
against RSLT-01/RSLT-02 before planning — both came back `unclassified/
unresolved` (the deterministic probe couldn't auto-classify edge cases from
requirement text alone); passed the raw coverage report into the planner with
an explicit instruction to surface both as flagged assumptions rather than
silently drop them, and to run the prohibition-recall protocol itself (no
`## Prohibitions` section existed either).

`gsd-planner` (opus) produced **5 plans across 4 waves**:
- **06-01** (Wave 1, tracer, non-autonomous) — D-01 Gradle/Vite single-JAR
  wiring, D-02 Thymeleaf + prototype teardown (now including the two flagged
  controllers), a new token-gated `GET /api/sessions/by-code/{joinCode}/me`
  bootstrap endpoint, D-03 resume-URL reshape, and the join screen proven
  end-to-end. Two zero-code `checkpoint:decision`/`checkpoint:human-verify`
  gates before D-01/D-02's one-way teardown, per REVERSIBILITY_GATES.
- **06-02** (Wave 2, autonomous) — new named-roster read model +
  `GET /.../votes/roster` for D-10, reusing the existing inactivity query.
- **06-03** (Wave 2, non-autonomous) — swipe deck (D-06/07/08/09): pure
  `resolveSwipe` rule, `motion` drag/tilt/tint, desktop-only buttons at 768px.
- **06-04** (Wave 3, non-autonomous) — `resolveScreen`/`useRouteGuard` (D-04)
  + waiting screen wired to STOMP with reconnect-reconcile.
- **06-05** (Wave 4, non-autonomous) — `pickBestMatch` + results view
  (RSLT-02), D-11 no-match branch, cold-open forwarding (RSLT-01).

Two new backend endpoints beyond CONTEXT.md's explicit list turned out
necessary (join-code→session resolution for D-03; roster data source for
D-10, since `VoteStatusResponse` only carried aggregate counts) — both
additive, both token-gated, explicitly called out by the planner as
CONTEXT.md-permitted ("anything unforeseen surfaced during planning"). No
`## Package Legitimacy Audit` existed (research skipped), so the planner
applied the documented fallback: treated all npm packages as `[ASSUMED]` and
added a blocking-human checkpoint before `npm install` rather than halting
planning outright.

`gsd-plan-checker` (haiku) returned **VERIFICATION PASSED**, zero
blockers/warnings, on the first pass — no revision loop needed. Both
deterministic probes (verify-command-path resolvability, failing-direction
statements) were 87/87 clean before the checker even ran. Requirements
coverage 2/2, decision coverage 11/11, post-planning gap analysis 13/13 (2
requirements + 11 decisions) — all green on first check, no re-plan cycles.

Committed plans (`e120e74`), then a small follow-up commit for
`state.json`/`06-PATTERNS.md` that the first commit's file list missed
(`42d65c7`).

Next: `/gsd-execute-phase 6` to run all 5 plans.

### 2026-09-06 — Plan 06-02 executed (session roster read model + endpoint)

Executed autonomously (both tasks `type="auto" tdd="true"`, no checkpoints) after
confirming the plan's Docker precondition was met (`docker info` reachable via Colima
socket). Each task ran a genuine RED-then-GREEN TDD cycle with its own commit pair:

- **Task 1** — `ParticipantRepository.findBySession_IdOrderByCreatedAtAsc` and
  `VoteRepository.findVoteCountsByParticipant` (unfiltered by `choice` — a PASS is
  progress too) added; `MatchAggregationService.computeRoster(sessionId)` computes a
  join-ordered `SessionRoster` by calling the *same* `findActiveParticipantIds` query
  `computeStatus` already calls (now exactly two call sites total, enforced by a grep
  gate) and the same pinned-snapshot `deckSize` guard — no second, independently-
  drifting inactivity rule. `a8a0159` (test, RED — fails to compile) → `fcf9512`
  (feat, GREEN — all 6 read-model behaviors pass).
- **Task 2** — `GET /api/sessions/{sessionId}/votes/roster` added to `VoteController`,
  inheriting the identical membership guard/401/404 semantics `recordVote`/`getStatus`
  already use. `SessionRosterResponse`/`ParticipantProgressResponse` DTOs with
  `@get:JsonProperty` pins on `isFinished`/`isActive` (same Jackson boolean-getter-
  prefix-stripping fix already applied to `isComplete`). Deliberately no
  session-level completion flag on the roster shape (prohibition P-02) — verified by
  a body-content test. To keep RED honest even though Task 1's controller file
  already existed, `git stash`ed the Task 2 controller diff before running the new
  MockMvc tests (confirmed 4/5 genuinely fail against the unmodified controller),
  then restored it for GREEN. `d97178f` (test, RED) → `134d6d6` (feat, GREEN — all 11
  cases in `SessionRosterTest.kt` pass, full `./gradlew build` green including every
  Phase 1-5 regression test).

Zero deviations from the plan. RSLT-01 stays `blocked` in REQUIREMENTS.md by design
(the shared-ID gate, #2388) — 06-03 and 06-04 also declare it, so it won't flip to
`Complete` until the last of the three plans finishes.

**Recurring bug re-confirmed, 9th/10th occurrences:** `state.advance-plan`,
`state.add-decision` (x2) and `state.record-session` each independently reset
STATE.md's frontmatter `progress.completed_phases`/`percent` back down (5→4,
81%→67%) during this plan's close-out, exactly matching the pattern logged after
every plan close-out since 05-01. Manually corrected one final time, after all
state-mutating calls ran, to `completed_phases: 5` / `percent: 86` (18/21 completed
plans — the same completed_plans/total_plans formula the prior 81% value itself came
from, 17/21). `state.json` remained correct throughout; only the STATE.md frontmatter
drifts. Full detail added to STATE.md's own Blockers/Concerns log — worth actually
filing as a defect against gsd-tools now rather than continuing to hand-patch it.

Full plan SUMMARY: `.planning/phases/06-frontend-spa/06-02-SUMMARY.md`.

Next: 06-03 (swipe deck) and 06-04 (waiting screen), both non-autonomous (contain
checkpoints), then 06-05 (results view).

### 2026-09-09 — Plan 06-03 closed out (swipe deck, checkpoint + fixes)

Picked up mid-session: Tasks 1-2 (swipe decision rule, card stack, screen wiring) had
already been executed and committed in a prior session (`67ad762`, `28a1722`,
`b2a1384`, `6c2bdc6`), but Task 3's blocking human-verify checkpoint had never run —
no `06-03-SUMMARY.md` existed. Also found an uncommitted working-tree fix from that
prior session: the deck-exhaustion navigate to `/wait` only fired on the post-vote
path, so reopening a link whose bootstrap already covered every deck movie fell
through to an empty card stack instead of redirecting. Folded both cases into one
effect keyed on `cursor >= remaining.length`, added a regression test, committed as
`f9a0e58`.

Ran the checkpoint live against `./gradlew bootRun` with two seeded test sessions
(deck rows inserted directly into `session.pinned_deck` via SQL — TMDB_API_TOKEN is
still unavailable on this dev machine, same workaround as Phase 5). Browser
automation wasn't connected this session, so the developer drove the walkthrough
directly rather than via screenshots.

Five of six items passed clean (stack depth, tilt/tint, commit feel, desktop
buttons, resume-coverage-by-test). Item 5 (deck exhaustion) **failed on first
try**: swiping the last card landed on the join-a-session form instead of the
welcome-back screen. Root cause: the exhaustion `navigate()` to `/wait` dropped the
`?token=` query param entirely — since the token lives only in the URL (D-05, no
browser storage), the destination route saw an unauthenticated load. This is a
standing pattern to watch for now: *every* internal `navigate()` to another
token-gated route must forward the token explicitly. Fixed in `a61f820`, and both
exhaustion tests in `SwipeScreen.test.tsx` were strengthened with a probe route
component asserting the token itself round-trips — the original tests only checked
that the destination *text* appeared, which is exactly why they didn't catch this.

Also implemented two feel-adjustments requested during the same checkpoint (button
hover state, a 220ms transition on the card stack's per-slot transform so the next
card promotion animates instead of snapping), then re-verified live on a fresh
seeded session — all six items passed.

Test fixture cleanup: both seeded sessions' participant/vote/session rows were
deleted after verification; nothing left in the dev database.

`06-03-SUMMARY.md` written. STATE.md progress corrected by hand (18→19 completed
plans, 86%→90%) — the same recurring frontmatter-drift defect logged after every
plan close-out since 05-01 did not reproduce loudly this time since no GSD
state-mutation CLI verb ran in this session (STATE.md was edited directly); worth
noting the defect is specifically in `gsd-tools`' state verbs, not in STATE.md
itself.

Full plan SUMMARY: `.planning/phases/06-frontend-spa/06-03-SUMMARY.md`.

Next: 06-04 (waiting screen — replaces the `/wait` placeholder this plan's checkpoint
exposed) and 06-05 (results view), both non-autonomous.

### 2026-09-09 — Plan 06-04 closed (routing guard + waiting screen, partial checkpoint)

Dispatched a gsd-executor agent in an isolated worktree for Tasks 1-2 (server-authoritative
route guard via `resolveScreen`/`useRouteGuard`, and the STOMP-backed `WaitScreen` with the
named roster). The agent's worktree branch had forked from `main` before 06-03 existed; it
correctly detected this, fast-forward-merged current `main` into itself first (verified as a
strict ancestor, non-destructive), then executed both tasks as normal RED/GREEN TDD pairs
(`9a85f73`/`63fe16d`, `0c9d916`/`b79aa65`). 50/50 frontend tests and a full `./gradlew build`
green. The orchestrator fast-forward-merged the branch back onto `main` and removed the
worktree.

The agent explicitly reviewed 06-03's token-propagation lesson before writing any new
`navigate()` calls and confirmed `useRouteGuard` forwards `location.search` on every redirect —
no repeat of that bug class.

Task 3's live checkpoint (two-client progression + kill-the-network reconnect walkthrough) only
closed partially. Items 1-3 (live progression, named roster, RTIME-02 auto-transition) passed
across three separate seeded two-participant sessions. Getting there took three false starts,
all environmental rather than code bugs:

- Twice mistook a correctly-functioning `/results` or bare `/s/{code}` placeholder screen
  ("Welcome back...") for a bug, before confirming the browser was actually on an unintended
  URL or a stale tab from an earlier session in the same checkpoint — not a routing defect.
- Discovered `VoteRepository.findActiveParticipantIds` counts a never-voted participant as
  active only within `voting.inactivity-timeout-seconds` (default 60s) of their own
  `created_at`. The normal pace of relaying instructions and waiting for the developer to act
  blew past that window twice, silently excluding the slower participant and marking sessions
  "complete" mid-test in a confusing way. Restarted the dev server once with
  `--voting.inactivity-timeout-seconds=3600` (runtime arg only, not committed) to remove time
  pressure for the rest of the walkthrough.

Items 4-6 (reconnect indicator, reconnect-reconcile, no duplicate frames) were **not verified**.
Chrome DevTools' Offline throttle turned out to be the wrong tool for this test: it doesn't
reliably close an already-open WebSocket, and it also blocks the STOMP client's own
`reconnectDelay`-driven reconnect attempts while checked — so neither "does the app detect a
drop" nor "does the app recover" could actually be exercised. Recommended a real WiFi toggle or
DevTools' "Close connection" on the `/ws` row instead; the developer chose to stop here rather
than retry ("skip this, not relevant"). Logged as an open, undismissed gap in
`06-04-SUMMARY.md` rather than fabricated as a pass — worth a dedicated retest with proper
tooling before treating RTIME-03 as genuinely proven.

Test fixture cleanup: all four seeded sessions' participant/vote/session rows deleted after
verification; nothing left in the dev database. Dev server stopped.

`06-04-SUMMARY.md` written with the partial checkpoint result. STATE.md progress corrected by
hand (19→20 completed plans, 90%→95%).

Full plan SUMMARY: `.planning/phases/06-frontend-spa/06-04-SUMMARY.md`.

Next: 06-05 (results view — closes RSLT-01/RSLT-02), non-autonomous. Before or alongside it,
consider a clean retest of 06-04's reconnect items 4-6 with a real network toggle.

### 2026-09-09 — Plan 06-05 closed (results view) — Phase 6 and ROADMAP.md execution complete

Dispatched a gsd-executor agent in an isolated worktree for Tasks 1-2 (deterministic
`pickBestMatch` selection + `ResultsScreen`, and cold-opened resume-link forwarding). The
first attempt halted cleanly with **zero changes**: its worktree had forked from `origin/main`
at the Phase 3 milestone commit (91 commits stale — `origin` hadn't been pushed to since),
so `.planning/phases/06-frontend-spa/` and `frontend/` didn't exist in that checkout at all.
The agent correctly diagnosed this via `git merge-base`/`git rev-list`, confirmed its own
branch had zero unique commits (safe to discard), and explicitly deferred the git-topology
fix to the orchestrator rather than improvising — exactly the restraint the role calls for.
Fixed by pushing local `main` to `origin` (91 commits, plain fast-forward, no force) and
re-dispatching; the second attempt forked cleanly and completed both tasks without incident
(`ed240a6`/`2922ef0`, `0b17212`/`9f8889d`). 66/66 frontend tests, full `./gradlew build` green.

Task 3's full 7-item checkpoint — whole-journey SPA check, a real match, a real zero-match, a
real tie, cold-open resume, a tokenless visitor, and prototype-removal confirmation — passed
live in **one pass, zero fixes required**, the first Phase 6 checkpoint this session that
didn't need a code change. Items 1 and 7 (SPA-only journey, Thymeleaf gone, `/ws` live) were
verified programmatically before handing off, so the developer's time went only to the four
items that genuinely need human judgment or live two-participant interaction. Three purpose-
built fixture sessions were seeded directly via SQL vote inserts (match / zero-match / a
genuine tie) rather than full swipe walkthroughs, since those three items test only the
results view's own selection logic — already unit-tested — not the swipe interaction 06-03
already proved live.

Two notable live confirmations, both correct-by-design rather than bugs:
- A genuine tie (both movies liked by both participants, equal like counts) deterministically
  resolved to the higher-`voteAverage` film, exactly matching `pickBestMatch`'s documented
  ordering (likeCount desc, voteAverage desc, tmdbId asc).
- A third participant joining an already-complete, already-matched session correctly reopened
  voting: becoming part of the active roster, and since they didn't unanimously like the same
  film, the match recomputed and flipped to no-match. This is A-01's intended behavior (the
  match is live off the current active set, never frozen at first completion), not a defect.

Test fixture cleanup: all three sessions' vote/participant/session rows deleted after
verification; nothing left in the dev database. Dev server stopped.

`06-05-SUMMARY.md` written. REQUIREMENTS.md: RSLT-01 and RSLT-02 both marked `Complete`.
ROADMAP.md: all five Phase 6 plan checkboxes now checked, Phase 6 row marked Complete
(2026-09-09). STATE.md: `completed_plans` 21/21 (100%), status moved to `verifying` — Phase 6
is the last phase in ROADMAP.md, so the project's plan-execution work is now fully done.

**Carried-forward gap:** 06-04's checkpoint items 4-6 (reconnect indicator, reconnect-reconcile,
no duplicate frames) are still **not verified** — the Chrome DevTools Offline throttle used to
test them turned out to block the STOMP client's own reconnect attempts, making the test
inconclusive rather than a confirmed pass or fail. Worth a dedicated retest with a real network
toggle (or DevTools' "Close connection" on the `/ws` row) before treating RTIME-03 as fully
proven end-to-end.

Full plan SUMMARY: `.planning/phases/06-frontend-spa/06-05-SUMMARY.md`.

Next: formal phase verification for Phase 6 (STATE.md status is `verifying`), then likely
milestone completion review — `/gsd-complete-milestone` or equivalent, developer's call. The
06-04 reconnect retest should happen before or as part of that.

### 2026-09-09 — Post-Phase-6 live testing: real gaps found and fixed against ROADMAP intent

After Phase 6 was marked execution-complete, the developer asked to actually test everything
originally intended, wired to a real TMDB token. Live testing surfaced a chain of real gaps that
automated tests and the phase checkpoints hadn't caught, since each one only shows up when a real
person tries to use the whole app end to end:

1. **No way to create a session through the SPA at all.** D-04's route table never allocated a
   route for it — the root route was static text. Added `CreateSessionScreen` (`fa16bf0`): calls
   the already-existing `POST /api/sessions`, joins as the creator like anyone else (no host role
   introduced, matching `SessionController` D-02), lands on the session route.
2. **No shareable link, no filters UI at all.** SESH-01 (shareable link) and CTLG-02/CTLG-03
   (genre/provider/region filtering) had backend support since Phase 2/3 but no frontend ever
   built for them. Added a copyable share link and `SessionFiltersForm` (`1f37435`).
3. **The share link/filters were unreachable in practice.** `resolveScreen` (06-04) had no
   "lobby" state — any bootstrapped participant fell straight through to `/swipe` instantly, even
   though D-04's own routing comment always called `/s/:code` the "(join/lobby)" route. Added a
   real `'lobby'` screen state gated on `deckPinned`, with a `LobbyScreen` participants actually
   stay on until someone clicks "Start swiping" (`094ef1b`).
4. **Visual design.** The app was still the unmodified Vite scaffold (light theme, purple accent,
   fixed 1126px desktop width). Replaced with a cinema-house dark palette, Fraunces/Work Sans
   typography, and a mobile-first layout, applied via global tokens so every screen picked it up
   (`95af1f2`).
5. **Mainstream providers missing from the picker.** TMDB's own `displayPriority` ranking doesn't
   reliably surface every regionally-mainstream service — RTL+ and HBO Max both sit outside a
   plain top-10 cut in real DE data. Pinned Netflix/Disney Plus/Amazon Prime Video/HBO
   Max/RTL+/Apple TV ahead of TMDB's ordering by name, with an exclusion list (`channel`, `store`,
   `kids`, `with ads`, `free`) discovered necessary after finding that same-brand bundle variants
   (e.g. "HBO Max Amazon Channel", priority 11) can rank *better* than the canonical service
   itself (plain "HBO Max", priority 28) — verified against real TMDB DE data both times (`4d1a16e`).
6. **No roster in the lobby.** Reused the existing `useRoster` hook (extended with an optional
   poll interval, since joining doesn't broadcast over the socket — no server event exists for
   it) to show who's actually joined (`95af1f2`).
7. **The filters UI didn't actually affect the fetched deck.** The most serious find: backend
   filtering was correct throughout (repeatedly verified live via direct API calls — a
   Netflix-only filter genuinely returns only Netflix titles). The bug was a separate "Save
   filters" button independent of "Start swiping" — toggling a provider and clicking "Start
   swiping" without saving first pinned the deck with the old (often empty) filters, so a selected
   provider silently had no effect. Fixed by lifting filter state into `LobbyScreen`, which now
   autosaves 500ms after the last edit *and* explicitly flushes the current selection before
   pinning the deck, closing the race a debounce alone can't close (`cbe4f1c`).

Also fixed as a bookkeeping-only correction: `RELI-01` (Phase 1, database survival) was verified
by `RestartSurvivalTest` back in Phase 1 but its REQUIREMENTS.md checkbox was never actually
ticked — marked `Complete`, no functional change.

Every fix above shipped with its own TDD test coverage (RED-then-GREEN, matching this project's
established discipline) and was independently verified live against the real TMDB API before and
after, not just unit-tested. All 84 frontend tests green, full `./gradlew build` green throughout.
Developer confirmed live: the full create → lobby → filter → swipe → wait → results flow now
works end to end, including a real multi-match tie resolving deterministically and a real
Netflix-only filter correctly restricting the deck.

All commits pushed to `origin/main` (GitHub) as they landed, keeping the push-after-every-wave
habit intact throughout.

**Carried-forward open items, unchanged from before this session:**
- 06-04's reconnect checkpoint items 4-6 (indicator/reconcile/no-duplicate-frames) still not
  verified — needs a real network-toggle retest, not DevTools' Offline throttle.
- STATE.md status is still `verifying` — formal phase verification / milestone completion review
  has not been run.

## Format for future entries

```
### YYYY-MM-DD — Short title
What happened, why it mattered, what was decided/resolved (or "unresolved,
carried to next session").
```
