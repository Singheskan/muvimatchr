---
phase: "04"
slug: "vote-recording-match-aggregation"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-09-04"
---

# Phase 04 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (`kotlin-test-junit5`) + Spring Boot Test + Testcontainers |
| **Config file** | none dedicated — `PostgresTestSupport.kt` base class (singleton-container pattern) |
| **Quick run command** | `./gradlew test --tests "org.example.muvimatchr.voting.*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~90 seconds (Testcontainers Postgres startup + suite) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "org.example.muvimatchr.voting.*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green, including the concurrency test run at least once with repeated invocation (concurrency bugs are probabilistic — a single green run is weaker evidence than for the rest of this phase's tests)
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 04-01-01 | 01 | 1 | VOTE-01 | — | Participant submits like/pass, vote persisted immediately | integration (Testcontainers) | `./gradlew test --tests "*VoteControllerTest*"` | ❌ W0 | ⬜ pending |
| 04-01-02 | 01 | 1 | VOTE-02 | — | Vote survives server restart | integration, extends `RestartSurvivalTest` | `./gradlew test --tests "*RestartSurvivalTest*"` | ✅ (extend existing) | ⬜ pending |
| 04-01-03 | 01 | 1 | VOTE-03 | — | Re-vote updates existing row, no duplicate | unit/integration | `./gradlew test --tests "*VoteRepositoryTest*"` | ✅ (extend existing repo test; new service/controller coverage needed) | ⬜ pending |
| 04-02-01 | 02 | 2 | VOTE-04 | — | Movie reported as match only when every currently-joined participant liked it | integration (Testcontainers) | `./gradlew test --tests "*MatchAggregationServiceTest*"` | ❌ W0 | ⬜ pending |
| 04-02-02 | 02 | 2 | VOTE-05 | — | "Has everyone finished" computed against live roster; late joiner flips completion back | integration (Testcontainers) | `./gradlew test --tests "*MatchAggregationServiceTest*"` | ❌ W0 | ⬜ pending |
| 04-02-03 | 02 | 2 | RSLT-03 | — | Per-movie like counts queryable, not just winner flag | unit/integration | `./gradlew test --tests "*VoteRepositoryTest*"` | ❌ W0 (new test method) | ⬜ pending |
| 04-02-04 | 02 | 2 | (ROADMAP success criterion 4) | — | Two-concurrent-clients final-vote race triggers match computation exactly once (no double/missed trigger) | integration, `ExecutorService`/`CountDownLatch`-synchronized concurrent threads against Testcontainers Postgres | `./gradlew test --tests "*VoteServiceConcurrencyTest*"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/kotlin/org/example/muvimatchr/voting/VoteControllerTest.kt` — stubs for VOTE-01, VOTE-02 (integration)
- [ ] `src/test/kotlin/org/example/muvimatchr/voting/MatchAggregationServiceTest.kt` — stubs for VOTE-04, VOTE-05, RSLT-03
- [ ] `src/test/kotlin/org/example/muvimatchr/voting/VoteServiceConcurrencyTest.kt` — stub for the two-concurrent-clients exactly-once completion criterion
- [ ] Extend `src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt` — add a vote-survives-restart assertion alongside existing session/participant assertions
- [ ] Framework install: none — Testcontainers Postgres, JUnit 5, and `PostgresTestSupport` base class already exist and are reused as-is

---

## Manual-Only Verifications

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
