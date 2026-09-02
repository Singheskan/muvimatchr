---
phase: "02"
slug: "session-lobby-flow"
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-09-02"
---

# Phase 02 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (`kotlin-test-junit5`) + Spring Boot Test (`spring-boot-starter-test`), Testcontainers Postgres — all already on the classpath from Phase 1 |
| **Config file** | none dedicated — configured via `build.gradle.kts` (`tasks.withType<Test> { useJUnitPlatform() }`) |
| **Quick run command** | `./gradlew test --tests "*.<NewTestClass>"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~10-15 seconds (6 existing tests from Phase 1 + new tests this phase adds) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "*.<NewTestClass>"`
- **After every plan wave:** Run `./gradlew test` (full suite)
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** ~15 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| TBD | TBD | TBD | SESH-01 | T-02-TBD | Join code is unique, DB-enforced, collision-retried | integration | `./gradlew test --tests "*.SessionServiceTest"` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | SESH-02 | T-02-TBD | Join returns a server-issued unguessable token, not an echo | integration | `./gradlew test --tests "*.ParticipantControllerTest"` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | SESH-03 | T-02-TBD | Missing/invalid token cannot resolve as any participant | integration | `./gradlew test --tests "*.CurrentParticipantArgumentResolverTest"` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | SESH-04 | T-02-TBD | 3+ distinct participants can join one session, each with a distinct token | integration | `./gradlew test --tests "*.ParticipantControllerTest"` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | SESH-05 | T-02-TBD | Re-auth with a previously issued token returns the same participant, no duplicate row | integration | `./gradlew test --tests "*.ParticipantControllerTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*
*Task IDs, plan, and wave columns are filled in by the planner once PLAN.md files exist.*

---

## Wave 0 Requirements

- [ ] `src/test/kotlin/org/example/muvimatchr/session/SessionServiceTest.kt` — covers SESH-01 (join-code uniqueness/retry behavior)
- [ ] `src/test/kotlin/org/example/muvimatchr/session/ParticipantControllerTest.kt` — covers SESH-02, SESH-03, SESH-04, SESH-05 (full join/resume/multi-participant flow, layered on the existing `PostgresTestSupport` base class per Phase 1's real-Postgres integration-test style — no mocking the repository layer)
- [ ] Framework install: none — JUnit 5, Spring Boot Test, and Testcontainers are already present from Phase 1; MockMvc auto-config comes free with `spring-boot-starter-test` (already a test dependency), no new Gradle coordinate needed

---

## Manual-Only Verifications

All phase behaviors have automated verification.

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 15s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
