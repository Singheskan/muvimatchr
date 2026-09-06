---
phase: "04"
slug: "vote-recording-match-aggregation"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: validated
nyquist_compliant: true
wave_0_complete: true
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
| 04-01-01 | 01 | 1 | VOTE-01 | T-04-01..05 | Participant submits like/pass, vote persisted immediately | integration (Testcontainers) | `./gradlew test --tests "*VoteControllerTest*"` | ✅ | ✅ green |
| 04-01-02 | 01 | 1 | VOTE-02 | — | Vote survives server restart | integration, `RestartSurvivalTest` | `./gradlew test --tests "*RestartSurvivalTest*"` | ✅ (extended) | ✅ green |
| 04-01-03 | 01 | 1 | VOTE-03 | — | Re-vote updates existing row, no duplicate | integration (`VoteControllerTest`) + unit (`VoteRepositoryTest`) | `./gradlew test --tests "*VoteControllerTest*" --tests "*VoteRepositoryTest*"` | ✅ (`a repeat POST with a different choice updates the existing vote row in place instead of duplicating`, `upsertVote for an existing tuple updates the choice in place instead of duplicating`) | ✅ green |
| 04-02-01 | 03 | 2 | VOTE-04 | — | Movie reported as match only when every currently-joined participant liked it | integration (Testcontainers) | `./gradlew test --tests "*MatchAggregationServiceTest*"` | ✅ (`a movie liked by two of three active participants is excluded from matchedMovieIds`) | ✅ green |
| 04-02-02 | 03 | 2 | VOTE-05 | — | "Has everyone finished" computed against live roster; late joiner flips completion back | integration (Testcontainers) | `./gradlew test --tests "*MatchAggregationServiceTest*"` | ✅ (idle/backdated-participant tests) | ✅ green |
| 04-02-03 | 03 | 2 | RSLT-03 | — | Per-movie like counts queryable, not just winner flag | integration (Testcontainers) | `./gradlew test --tests "*MatchAggregationServiceTest*"` | ✅ (`likeCounts orders by count descending then movieId ascending, and repeated calls are identical`) | ✅ green |
| 04-02-04 | 04 | 3 | (ROADMAP success criterion 4) | T-04-05 | Ten simultaneous final-vote races each trigger match computation exactly once (no double/missed trigger) | integration, `CountDownLatch`-synchronized concurrent threads against Testcontainers Postgres | `./gradlew test --tests "*VoteServiceConcurrencyTest*"` | ✅ | ✅ green |
| 04-03-01 | 04 | 3 | (code-review CR-01/WR-02/WR-03) | — | Concurrent first-time deck pins converge on exactly one persisted snapshot; concurrent filter-replacement/pin races are mutually excluded | integration, `CountDownLatch`-synchronized concurrent threads against Testcontainers Postgres | `./gradlew test --tests "*DeckPinConcurrencyTest*"` | ✅ (added during code-review fix pass) | ✅ green |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [x] `src/test/kotlin/org/example/muvimatchr/voting/VoteControllerTest.kt` — VOTE-01, VOTE-02, VOTE-03 (integration)
- [x] `src/test/kotlin/org/example/muvimatchr/voting/MatchAggregationServiceTest.kt` — VOTE-04, VOTE-05, RSLT-03
- [x] `src/test/kotlin/org/example/muvimatchr/voting/VoteServiceConcurrencyTest.kt` — the ten-simultaneous-finishes exactly-once completion criterion
- [x] Extended `src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt` — vote-survives-restart assertion alongside existing session/participant assertions
- [x] `src/test/kotlin/org/example/muvimatchr/session/DeckPinConcurrencyTest.kt` — added during the code-review fix pass (CR-01/WR-02/WR-03), not originally in this strategy's Wave 0 list
- [x] Framework install: none — Testcontainers Postgres, JUnit 5, and `PostgresTestSupport` base class already existed and were reused as-is

---

## Manual-Only Verifications

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 90s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** validated 2026-09-06 — 8/8 per-task rows green, full suite (`./gradlew test`) passes, zero gaps found.

## Validation Audit 2026-09-06

| Metric | Count |
|--------|-------|
| Gaps found | 0 |
| Resolved | 0 |
| Escalated | 0 |
