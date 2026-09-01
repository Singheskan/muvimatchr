---
name: close
description: "End-of-session closeout for MuviMatchr: syncs .planning/KANBAN.md and .planning/STATE.md, reviews uncommitted changes, and offers to stop any locally running dev services (Gradle bootRun, docker compose)."
argument-hint: "[short session summary]"
allowed-tools:
  - Read
  - Edit
  - Write
  - Bash
  - AskUserQuestion
---

<objective>
Close out the current working session cleanly, in this repo's own conventions:
1. Summarize what actually happened this session — grounded in real diffs/commits, not a generic recap.
2. Update `.planning/KANBAN.md` (the running activity/issues log — see its own header) with dated,
   specific entries in the same terse-but-concrete style as its existing entries.
3. Sync `.planning/STATE.md` so it doesn't drift from `KANBAN.md`.
4. Surface uncommitted changes and offer — never assume — a commit.
5. Offer to stop any locally running dev services started this session (Spring Boot `bootRun` on
   :8080, and/or `docker compose` services from `docker-compose.yml` once that file exists).

Run this inline in the current conversation — do NOT spawn a subagent. The whole point is using
this session's own memory of what happened; a fresh subagent has none of that context.
</objective>

<process>

<step name="gather">
Before writing anything, establish what actually happened this session:

```bash
cd "$(git rev-parse --show-toplevel)"
git log --oneline -20
git status --short
git diff --stat HEAD
```

Cross-reference against your own knowledge of this conversation. If `$ARGUMENTS` contains a session
summary, treat it as the user's framing and reconcile it with the git evidence rather than overriding
either silently — if they disagree, ask.

Do not invent accomplishments that aren't backed by an actual commit, file change, or verified action
from this session.
</step>

<step name="update_kanban">
Read `.planning/KANBAN.md`.

Update it to reflect this session, following the format already established at the bottom of the file:
- Move finished items out of **Board → In Progress** into **Board → Done**; add anything newly started
  to **In Progress**; keep **Next** current.
- Add a dated entry to **Issues Log** (`### YYYY-MM-DD — Short title`) for anything that arose this
  session worth remembering months from now — a real blocker, a non-obvious decision, a surprising
  discovery. Routine progress with no issue doesn't need an Issues Log entry, just a Board update.
- Use today's date (check via `date +%Y-%m-%d` — do not guess).

Show the user a short preview of what you're about to change before writing it, unless the change is a
single obvious bullet.
</step>

<step name="sync_state">
Read `.planning/STATE.md`.

This project is GSD-managed (`gsd-core` tooling under `~/.claude/gsd-core/` or `.claude/gsd-core/`).
Prefer an existing `gsd_run query state.*` verb when one fits what changed this session (e.g.
`state.begin-phase`, `state.planned-phase` — check `gsd_run query --help`-style discovery or existing
usage in `.planning/` git history for the right verb) rather than hand-editing frontmatter, since GSD
owns this file's schema and a verb keeps the format contract intact. Only fall back to a direct `Edit`
matching the existing frontmatter format exactly (see below) when no verb covers what happened.

Fields to keep in sync with what `KANBAN.md` now shows, whichever path you take:
- `last_updated` (ISO timestamp) and `last_activity` (date) in frontmatter
- `last_activity_desc` — one line, matches what you just wrote to KANBAN.md
- `state_head` — short hash of current `HEAD`, taken AFTER this step's own commit if you end up
  committing in `git_review` (see note there)
- `## Session Continuity` section at the bottom — "Last session" timestamp, "Stopped at" one-liner

Do NOT touch `ROADMAP.md` or `REQUIREMENTS.md` unless a phase or requirement's actual status changed
this session (rare — those are milestone-scoped, not session-scoped).

**If the session is pausing mid-plan-execution** (an incomplete `PLAN.md` in the current phase
directory with no matching `SUMMARY.md`, and no `VERIFICATION.md` for the phase): also refresh
`.planning/HANDOFF.json` and `<phase-dir>/.continue-here.md` — either by invoking the `gsd-pause-work`
skill, or by hand if a quick update suffices. Those are GSD's own point-in-time resume snapshots that
`/gsd-resume-work` and `/gsd-execute-phase` read directly; `KANBAN.md` is the human-readable narrative
log and does not substitute for them.
</step>

<step name="git_review">
Show the user:
```bash
git status --short
git diff --stat
```

If there are uncommitted changes (including the `KANBAN.md`/`STATE.md`/`HANDOFF.json`/
`.continue-here.md` edits just made): summarize what's staged/unstaged in plain language, then ask via
AskUserQuestion whether to commit now, and if so, propose a commit message following this repo's
existing convention (Conventional Commits — `type(scope): description`, e.g. `docs(01): ...`,
`chore: ...`, `wip: ...` — see `git log` for real examples).

Never commit without asking first, even though this skill just wrote to the planning files itself. If
the user declines, leave everything staged/unstaged as-is and say so clearly in the final summary —
don't silently drop the offer.

If the user agrees, stage exactly the files discussed — never a blanket `git add -A`. If they also want
to push, ask that separately (a commit and a push are two different blast radii).
</step>

<step name="dev_server">
Check for locally running services started during this session:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN 2>/dev/null   # Spring Boot bootRun default port
lsof -nP -iTCP:5173 -sTCP:LISTEN -o 2>/dev/null # future Vite/React dev server (Phase 6+, may not exist yet)
[ -f docker-compose.yml ] && docker compose ps 2>/dev/null
colima status 2>/dev/null   # or whichever container runtime KANBAN.md/HANDOFF.json records as chosen
```

For anything found running, tell the user what it is (port/PID for `lsof` hits, service name/health for
`docker compose ps`) and ask whether to stop it before ending the session, or leave it running — a
locally running Postgres or `bootRun` process is often intentional to leave up between sessions. Never
kill or stop a process the user didn't confirm. Note: a container runtime being "started" is host-machine
state, not session state — don't offer to stop Colima/Docker Desktop/OrbStack itself unless the user asks;
only offer to stop project-specific services (the `docker compose` stack, `bootRun`).
</step>

<step name="summary">
Print a short closing summary:

```
Session closed.

KANBAN.md: {one-line description of what changed}
.planning/STATE.md: synced (phase X, state_head {hash})
Git: {committed as <hash> | pushed | N files left uncommitted, see above}
Dev services: {stopped <what> | left running: <what> | none were running}
```

Keep it to what actually happened — no filler, no "next steps" essay (that's what KANBAN.md's Board →
Next section is for).
</step>

</process>

<notes>
- This skill is project-specific to MuviMatchr's actual files and conventions — it is not a generic
  "wrap up my session" template ported unchanged from elsewhere.
- If `.planning/KANBAN.md` or `.planning/STATE.md` don't exist (e.g. run from a different checkout
  state), stop and tell the user rather than recreating them from scratch.
- Idempotency: if run twice in the same session with nothing new having happened, say so plainly
  ("Nothing new since the last /close this session") rather than padding KANBAN.md with a duplicate
  entry.
</notes>
