---
phase: "05"
slug: "real-time-notification-layer"
status: validated
nyquist_compliant: true
wave_0_complete: true
created: "2026-09-06"
---

# Phase 05 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers (Postgres) |
| **Config file** | build.gradle.kts (Kotlin/Gradle project — no separate test config file) |
| **Quick run command** | `./gradlew test --tests "*.realtime.*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~40-45 seconds (full suite, 136 tests) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "*.realtime.*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 45 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 05-01-01 | 01 | 1 | RTIME-01 | T-05-03 | Client SEND to broker topics is dropped; no SockJS/widened origin | build+grep | `./gradlew build` + grep guards | ✅ | ✅ green |
| 05-01-02 | 01 | 1 | RTIME-01 | — | STOMP push field-for-field identical to REST status | integration | `./gradlew test --tests "*.realtime.SessionStatusBroadcastTest"` | ✅ | ✅ green |
| 05-02-01 | 02 | 2 | RTIME-01, RTIME-02 | — | finishedCount progression, fan-out completion, session isolation | integration | `./gradlew test --tests "*.realtime.SessionStatusBroadcastTest"` | ✅ | ✅ green |
| 05-02-02 | 02 | 2 | RTIME-03 | — | No replay on reconnect; REST reconciliation to true state | integration | `./gradlew test --tests "*.realtime.ReconnectReconciliationTest"` | ✅ | ✅ green |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

Full suite (136 tests, all phases including 05) re-run by the orchestrator during execute-phase's regression gate: 0 failures, 0 errors. Independently re-run by the phase verifier (10/10 realtime tests). All requirement IDs (RTIME-01, RTIME-02, RTIME-03) cross-referenced against REQUIREMENTS.md — no orphaned or missing IDs.

---

## Wave 0 Requirements

Existing infrastructure (JUnit 5 + Spring Boot Test + Testcontainers, already in place since Phase 2) covers all phase requirements. No new test framework or Wave 0 scaffolding needed.

---

## Manual-Only Verifications

Both plan-designated `<human-check>` items were end-of-phase sanity checks layered on top of already-passing automated proofs, not substitutes for them. Both were performed against a real running instance during `/gsd-verify-work 05` and passed:

| Behavior | Requirement | Why Manual | Test Instructions | Result |
|----------|-------------|------------|--------------------|--------|
| Startup log shows `/ws` STOMP endpoint with no SockJS fallback; old `lobby.html`-style socket no longer connects | RTIME-01 | Requires reading live app startup log output | Start app, inspect log for `/ws` STOMP registration | ✅ Confirmed 2026-09-06 |
| Reconnect walkthrough against a live running instance: disconnect, vote elsewhere, reconnect, confirm silence + correct REST status | RTIME-03 | Requires driving a real client against a real running server interactively | Open STOMP connection, kill it, vote, reconnect, check REST status | ✅ Confirmed 2026-09-06 — 0 frames on reconnected socket, REST returned `isComplete:true` |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references (none — no gaps found)
- [x] No watch-mode flags
- [x] Feedback latency < 45s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-09-06
