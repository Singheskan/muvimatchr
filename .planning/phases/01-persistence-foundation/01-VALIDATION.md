---
phase: "1"
slug: "persistence-foundation"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-09-01"
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (Jupiter) — already present via `kotlin-test-junit5` and `junit-platform-launcher` `[VERIFIED: build.gradle.kts:29-31]`; this phase adds `spring-boot-starter-test` (already present) plus Testcontainers' JUnit 5 integration. |
| **Config file** | none dedicated yet — `tasks.withType<Test> { useJUnitPlatform() }` already configured `[VERIFIED: build.gradle.kts:43-45]` |
| **Quick run command** | `./gradlew test --tests "org.example.muvimatchr.voting.VoteRepositoryTest"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~60 seconds (Testcontainers Postgres startup dominates) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "*.VoteRepositoryTest"` (or the relevant single repository test for that task)
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 01-01-TBD | 01 | 0 | RELI-01 | — | Docker daemon + Testcontainers dependencies available before any integration test runs | setup | `docker info` (or `colima status`/equivalent) | ❌ W0 | ⬜ pending |
| 01-02-TBD | 01 | 1 | RELI-01 | — | Session/Participant/Vote rows written in one Spring context are readable from a fresh, independently-started context against the same Testcontainers container | integration | `./gradlew test --tests "*.RestartSurvivalTest"` | ❌ W0 | ⬜ pending |
| 01-03-TBD | 01 | 1 | RELI-01 | — | Duplicate `(session_id, participant_id, movie_id)` write via native upsert updates in place, never errors or duplicates | integration | `./gradlew test --tests "*.VoteRepositoryTest"` | ❌ W0 | ⬜ pending |
| 01-04-TBD | 01 | 1 | RELI-01 | — | Inserting two sessions with the same `join_code` violates the DB unique constraint | integration | `./gradlew test --tests "*.SessionRepositoryTest"` | ❌ W0 | ⬜ pending |
| 01-05-TBD | 01 | 1 | RELI-01 | V5 Input Validation | Native upsert query uses `@Param`-bound named parameters exclusively — never string-concatenated SQL | source | `grep -n "@Query" src/main/kotlin/**/voting/*.kt` shows no string templating of bound values | ❌ W0 | ⬜ pending |
| 01-06-TBD | 01 | 1 | RELI-01 | — | Flyway migrations apply cleanly to a fresh DB and are safe to reapply (implicitly exercised by every integration test's context startup) | integration | `./gradlew test` (full suite; any context-startup failure surfaces this) | — (covered by other tests' setup) | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

*Task IDs are placeholders (`TBD`) — the planner assigns real task IDs; this map's rows correspond 1:1 to RESEARCH.md's Phase Requirements → Test Map plus the Docker-availability precondition RESEARCH.md flagged as a blocking environment gap.*

---

## Wave 0 Requirements

- [ ] Confirm Docker (or Colima/Podman/OrbStack) is installed and running on the dev machine — RESEARCH.md confirmed Docker is **not currently installed**; this blocks every integration test below and must be resolved before Wave 1 can execute, not just planned around.
- [ ] `src/test/kotlin/org/example/muvimatchr/support/PostgresTestSupport.kt` — shared static `@Container` Postgres instance/helpers, reused by the repository tests (not by `RestartSurvivalTest`, which manages its own context lifecycle).
- [ ] `src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt` — the D-03 proof itself.
- [ ] `src/test/kotlin/org/example/muvimatchr/session/SessionRepositoryTest.kt`
- [ ] `src/test/kotlin/org/example/muvimatchr/voting/VoteRepositoryTest.kt`
- [ ] Testcontainers dependencies (`spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`) — none present yet; part of this phase's dependency-addition task.

---

## Manual-Only Verifications

*None — all phase behaviors have automated (integration/source-assertion) verification once Docker is available.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
