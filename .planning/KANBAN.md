# MuviMatchr — Kanban / Activity Log

Running log of work done and issues encountered, maintained every session. For a
machine-readable point-in-time resume snapshot of a paused phase, see
`.planning/HANDOFF.json` / `<phase-dir>/.continue-here.md` instead — this file is
the human-readable history across the whole project.

---

## Board

### In Progress
- **Phase 1: Persistence Foundation** — Plan 01-01 (Wave 1), Task 2 of 3. Container
  runtime chosen (Colima), installed and running. Toolchain-upgrade edits
  (`build.gradle.kts`, `gradle-wrapper.properties`, placeholder-test deletion) exist
  uncommitted in an isolated worktree (`.claude/worktrees/agent-a524fff5420ee3fb2`,
  branch `worktree-agent-a524fff5420ee3fb2`) — not yet merged or verified green.

### Done
- Project setup: PROJECT.md, REQUIREMENTS.md (22 v1 requirements), ROADMAP.md (6 phases).
- Phase 1 research, pattern mapping, validation strategy, planning (3 plans), plan
  verification (0 blockers/warnings), requirements + decision coverage gates.
- Discarded old in-memory prototype's uncommitted edits (superseded by Phase 1).
- Reconciled diverged remote history (see Issues below) and pushed to `origin/main`.

### Next
- Reconcile the leftover worktree from Plan 01-01 Task 2 (resume-and-verify or
  discard-and-redo), then finish Task 2 + Task 3.
- Wave 2: Plan 01-02 (tracer slice — Session entity + restart proof).
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

## Format for future entries

```
### YYYY-MM-DD — Short title
What happened, why it mattered, what was decided/resolved (or "unresolved,
carried to next session").
```
