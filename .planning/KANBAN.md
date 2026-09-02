# MuviMatchr — Kanban / Activity Log

Running log of work done and issues encountered, maintained every session. For a
machine-readable point-in-time resume snapshot of a paused phase, see
`.planning/HANDOFF.json` / `<phase-dir>/.continue-here.md` instead — this file is
the human-readable history across the whole project.

---

## Board

### In Progress
- **Phase 1: Persistence Foundation** — Plan 01-01 complete (all 3 tasks). Ready to
  execute Plan 01-02 (tracer slice — Session entity + restart-survival proof), which
  is already planned (`01-02-PLAN.md` exists, no SUMMARY yet).

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

### Next
- Execute Plan 01-02 (tracer slice — Session entity + restart-survival proof, Wave 2).
- Wave 3: Plan 01-03 (Participant + Vote tables, upsert, extended restart proof).

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

## Format for future entries

```
### YYYY-MM-DD — Short title
What happened, why it mattered, what was decided/resolved (or "unresolved,
carried to next session").
```
