---
phase: 01-persistence-foundation
plan: 01
subsystem: infra
tags: [gradle, kotlin, spring-boot, postgres, docker-compose, flyway, testcontainers]

requires: []
provides:
  - Boot 4.1.1 / Kotlin 2.3.20 / Gradle 8.14.3 toolchain
  - JPA + Flyway + PostgreSQL + Testcontainers dependency set on the classpath
  - Colima container runtime installed and running
  - Local PostgreSQL 18 dev database via docker-compose
  - Datasource/Flyway/ddl-auto=validate wiring in application.properties
affects: [01-02-tracer-slice, 01-03-participant-vote-tables]

actuals:
  tokens: 4200
  tasks: 3
  commits: 2

tech-stack:
  added: [colima, docker-compose, postgres:18, spring-boot-starter-data-jpa, spring-boot-starter-flyway, flyway-database-postgresql, postgresql-jdbc, testcontainers-postgresql]
  patterns: []

key-files:
  created: [docker-compose.yml]
  modified: [build.gradle.kts, gradle/wrapper/gradle-wrapper.properties, src/main/resources/application.properties]

key-decisions:
  - "Container runtime: Colima (user choice in Task 1 checkpoint) — Apache-2.0, fully CLI-installable via Homebrew, no GUI/license click-through."
  - "Task 2's edits were re-applied directly onto main instead of merging the leftover isolated-worktree branch from the interrupted prior session: the worktree had branched from a stale base (origin's early 'Update README.md' commit) and was missing files already on main (WebSocketConfig.kt, WebSocketController.kt, several templates). The diff content itself was correct and matched the plan exactly, so it was re-applied onto main's actual current tip and verified there instead of merged."
  - "Gradle 8.14.3's daemon cannot launch on this machine's default JDK (Amazon Corretto 25, resolved via /usr/libexec/java_home). All ./gradlew invocations for this phase require JAVA_HOME pinned to the installed temurin-21 JDK. Not fixed via a committed gradle.properties (out of this plan's file scope, machine-specific path) — flagged here for future sessions/plans in this phase."

patterns-established: []

requirements-completed: [RELI-01]

coverage:
  - id: D1
    description: "Container daemon installed and running (Colima); docker info / docker compose version both exit 0"
    verification:
      - kind: other
        ref: "docker info > /dev/null && docker compose version > /dev/null && echo RUNTIME_OK"
        status: pass
    human_judgment: false
  - id: D2
    description: "Build toolchain raised to Gradle 8.14.3 / Kotlin 2.3.20 / Spring Boot 4.1.1, all seven persistence/Testcontainers coordinates resolved, no pre-existing dependency removed, ./gradlew build green"
    verification:
      - kind: other
        ref: "./gradlew --version | grep -q 'Gradle 8.14.3'"
        status: pass
      - kind: other
        ref: "./gradlew build"
        status: pass
      - kind: other
        ref: "./gradlew dependencies --configuration runtimeClasspath | grep -q 'org.flywaydb:flyway-database-postgresql'"
        status: pass
    human_judgment: false
  - id: D3
    description: "Superseded placeholder test (MuviMatchrApplicationTests.kt, empty contextLoads()) deleted"
    verification:
      - kind: other
        ref: "test ! -e src/test/kotlin/org/example/muvimatchr/MuviMatchrApplicationTests.kt"
        status: pass
    human_judgment: false
  - id: D4
    description: "Local PostgreSQL 18 compose service, loopback-bound, named volume at /var/lib/postgresql, healthy via docker compose up -d --wait"
    verification:
      - kind: other
        ref: "docker compose config && docker compose up -d --wait && docker compose ps --format '{{.Service}} {{.Health}}' | grep -q 'db healthy'"
        status: pass
    human_judgment: false
  - id: D5
    description: "application.properties wired with matching datasource credentials and ddl-auto=validate on exactly one line; user confirmed acceptable to leave the container running locally"
    verification:
      - kind: other
        ref: "grep -c ddl-auto (excluding comments) == 1 && value == validate"
        status: pass
    human_judgment: true
    rationale: "Plan's Task 3 verify block includes a <human-check> step (resource footprint / willingness to leave a local Postgres container running) that automation cannot resolve on its own."

duration: unknown (resumed across two sessions; see Issues Encountered)
completed: 2026-09-02
status: complete
---

# Phase 01, Plan 01: Persistence Foundation — Toolchain & Local Database Summary

**Repository now builds on Spring Boot 4.1.1 / Kotlin 2.3.20 / Gradle 8.14.3 with the full JPA/Flyway/PostgreSQL/Testcontainers dependency set resolved, a Colima container runtime running, and a healthy local PostgreSQL 18 service wired into application.properties — the enabling prerequisite for Plan 01-02's tracer slice.**

## Accomplishments
- Bumped the Gradle wrapper to 8.14.3 and the Boot/Kotlin toolchain to 4.1.1/2.3.20, adding all seven persistence/Testcontainers dependency coordinates without removing any pre-existing dependency (Thymeleaf, WebSocket, etc. all retained).
- Installed and confirmed Colima as the container runtime (user's Task 1 decision), with `docker info` / `docker compose version` both green.
- Added `docker-compose.yml` (postgres:18, loopback-bound, named volume at the PG18-correct `/var/lib/postgresql` path, `pg_isready` healthcheck) and brought it up healthy.
- Wired `application.properties` with datasource/Flyway config and `spring.jpa.hibernate.ddl-auto=validate` on exactly one line — Flyway remains the sole owner of schema DDL.
- Deleted the superseded placeholder `MuviMatchrApplicationTests.kt` (empty `contextLoads()`, no assertions); Plan 01-02's `RestartSurvivalTest` supersedes it with real two-context startup against real PostgreSQL.

## Task Commits

1. **Task 1: Choose container runtime (checkpoint:decision)** — resolved via interactive prompt in the prior session (Colima); no separate commit, folded into context for Task 2.
2. **Task 2: Install container runtime + upgrade toolchain** - `a3845f2` (feat)
3. **Task 3: Add compose service + wire datasource/Flyway config** - `de4a3a2` (feat)

_Note: no separate "plan metadata" commit — this SUMMARY.md is the plan-closing artifact._

## Files Created/Modified
- `build.gradle.kts` - Boot 4.1.1 / Kotlin 2.3.20 plugin versions, `kotlin("plugin.jpa")` added, seven new dependency coordinates (JPA, Flyway starter, Flyway Postgres dialect, Postgres driver, Testcontainers Spring integration + Postgres + JUnit modules).
- `gradle/wrapper/gradle-wrapper.properties` - `distributionUrl` → gradle-8.14.3-bin.zip.
- `src/test/kotlin/org/example/muvimatchr/MuviMatchrApplicationTests.kt` - deleted (superseded).
- `docker-compose.yml` - new; `db` service, `postgres:18`, `127.0.0.1:5432:5432`, named volume `muvimatchr-pgdata` at `/var/lib/postgresql`, `pg_isready` healthcheck.
- `src/main/resources/application.properties` - added datasource URL/username/password, `spring.jpa.hibernate.ddl-auto=validate`, `spring.flyway.enabled=true`; pre-existing `spring.application.name=MuviMatchr` line preserved.

## Decisions Made
- Container runtime = Colima (Task 1 checkpoint, prior session): Apache-2.0, fully CLI-installable, no GUI/license click-through, unattended install.
- Task 2's changes were re-applied directly onto `main` rather than merging the leftover isolated-worktree branch — see Issues Encountered for why.
- Did not add a `gradle.properties` pinning `org.gradle.java.home`: fixing the JDK-25-vs-Gradle-8.14.3 mismatch that way would be a machine-specific hardcoded path outside this plan's declared file scope. Documented instead as a required `JAVA_HOME` export for future sessions running `./gradlew` in this phase.

## Deviations from Plan

### Auto-fixed Issues

**1. [Resume-from-interruption] Leftover agent worktree from prior session's Task 2 was stale, not directly mergeable**
- **Found during:** Session resume, before starting Task 2.
- **Issue:** The prior session's execution of Task 2 ran in an isolated git worktree (`isolation="worktree"`) that was interrupted mid-task by a budget constraint before any commit. On resume, the worktree's branch (`worktree-agent-a524fff5420ee3fb2`) turned out to be based on a stale commit (origin's early "Update README.md", `c395dd6`) that predates several files already on `main` (`WebSocketConfig.kt`, `WebSocketController.kt`, `error.html`, `totalResults.html`, `wait.html`). Merging that branch risked silently dropping those files or producing spurious conflicts.
- **Fix:** Verified the worktree's `build.gradle.kts`/`gradle-wrapper.properties`/test-deletion diff matched Plan 01-01 Task 2's spec exactly (all five plugin versions, all seven dependency coordinates, wrapper URL), then re-applied the identical edits directly onto `main`'s actual current tip (which was still at the plan's expected pre-edit baseline) instead of merging. Removed the stale worktree and its branch afterward (`git worktree remove --force` + `git branch -D`).
- **Files modified:** `build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, `src/test/kotlin/org/example/muvimatchr/MuviMatchrApplicationTests.kt` (same set the plan specified).
- **Verification:** Full Task 2 acceptance criteria re-run on `main` post-edit: `./gradlew build` green, `./gradlew dependencies` shows both new artifacts, wrapper reports 8.14.3, test file absent.
- **Committed in:** `a3845f2`

**2. [Environment] Gradle 8.14.3 daemon fails on this machine's default JDK**
- **Found during:** Task 2 verification (`./gradlew build` initially failed with a bare `25.0.4` error and no stack trace).
- **Issue:** `/usr/libexec/java_home` resolves to Amazon Corretto 25 by default on this machine, but Gradle 8.14.3's daemon cannot launch on JDK 25 (the project's own toolchain target is 21, per `build.gradle.kts`, and temurin-21 is already installed).
- **Fix:** Exported `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` before every `./gradlew` invocation this session. Not persisted into a committed file — flagged in Blockers/Concerns for future phase-01 sessions instead, since a hardcoded absolute path in `gradle.properties` is machine-specific and outside this plan's file scope.
- **Files modified:** none (environment-only, shell export).
- **Verification:** `./gradlew build` green after the export; reproduced consistently across both Task 2 and Task 3 command runs.
- **Committed in:** N/A (not a code change)

---

**Total deviations:** 2 auto-fixed (1 resume-reconciliation, 1 environment workaround)
**Impact on plan:** No scope creep — both were resolving obstacles to executing the plan exactly as written, not adding new behavior. The worktree reconciliation reproduced the plan's exact specified diff; the JDK workaround is a session-only environment export with no repo footprint.

## Issues Encountered
- Session was interrupted mid-Task-2 in a prior session (budget constraint) with an isolated-worktree executor; resumed this session via HANDOFF.json + `.continue-here.md`, reconciled per Deviation 1 above.
- `.planning/milestone.lock` from the prior session referenced a dead PID (confirmed via `ps -p`) and was removed as stale, gitignored local session state.
- Duration is not cleanly measurable as a single span since this plan spanned two sessions with a pause in between; wall-clock time actively working in this session was well under an hour.

## User Setup Required
None beyond what Task 1's checkpoint already covered (Colima, already installed and running). User confirmed via Task 3's human-check that leaving the local PostgreSQL 18 container running is acceptable.

## Next Steps
Plan 01-02 (tracer slice — Session entity + restart-survival proof) is next; it depends on nothing further from this plan beyond what's already in place.
