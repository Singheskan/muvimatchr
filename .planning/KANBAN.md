# MuviMatchr — Kanban / Activity Log

Running log of work done and issues encountered, maintained every session. For a
machine-readable point-in-time resume snapshot of a paused phase, see
`.planning/HANDOFF.json` / `<phase-dir>/.continue-here.md` instead — this file is
the human-readable history across the whole project.

---

## Board

### In Progress
- **Phase 2: Session & Lobby Flow** — context gathered (`02-CONTEXT.md`). Key decisions:
  short typable join code (not a full URL); resume via a personal link carrying the
  participant's token; no host special role; late joiners allowed anytime, no session
  lock; duplicate display names allowed. Not yet planned.

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

### Next
- Plan Phase 2 (`/gsd-plan-phase 02`) now that context is captured.

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

## Format for future entries

```
### YYYY-MM-DD — Short title
What happened, why it mattered, what was decided/resolved (or "unresolved,
carried to next session").
```
