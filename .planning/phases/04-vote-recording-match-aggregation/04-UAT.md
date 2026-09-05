---
status: testing
phase: 04-vote-recording-match-aggregation
source: [04-VERIFICATION.md]
started: 2026-09-05T20:37:39Z
updated: 2026-09-05T20:37:39Z
---

## Current Test

number: 1
name: Concurrency tests hold under real machine load
expected: |
  Re-run VoteServiceConcurrencyTest (and DeckPinConcurrencyTest) while another
  build/process runs concurrently on the same machine. Same invariants hold:
  exactly one completion trigger per race, zero lost votes, and the Hikari
  pool-size sanity guard still passes (ruling out the connection pool, not
  Postgres's row lock, as the accidental serialiser).
awaiting: user response

## Tests

### 1. Concurrency test under real machine load
expected: Re-running `./gradlew test --tests "*.voting.VoteServiceConcurrencyTest" --tests "*.session.DeckPinConcurrencyTest"` while another CPU/IO-heavy process runs concurrently produces the same result as an idle-machine run: all iterations pass, no lost votes, exactly one completion signal per race, and the pool-size guard assertion passes.
result: [pending]

## Summary

total: 1
passed: 0
issues: 0
pending: 1
skipped: 0
blocked: 0

## Gaps
