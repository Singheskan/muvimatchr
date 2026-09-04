# MuviMatchr — Kanban / Activity Log

Running log of work done and issues encountered, maintained every session. For a
machine-readable point-in-time resume snapshot of a paused phase, see
`.planning/HANDOFF.json` / `<phase-dir>/.continue-here.md` instead — this file is
the human-readable history across the whole project.

---

## Board

### In Progress
- Phase 3 (TMDB Integration & Catalog Caching) — context gathered, ready to plan.

### Done
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

### Next
- Plan Phase 3 (TMDB Integration & Catalog Caching) — `/gsd-plan-phase 3`.

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

## Format for future entries

```
### YYYY-MM-DD — Short title
What happened, why it mattered, what was decided/resolved (or "unresolved,
carried to next session").
```
